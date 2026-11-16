package com.eddyizm.tempus.util

import com.eddyizm.tempus.subsonic.models.Child
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Piterfy: wybór utworów dla inteligentnego odtwarzania. Czysta logika bez sieci i Androida (testy jednostkowe),
 * ten sam algorytm co `smartCore` w connect/web/library.js.
 *
 * Wzorem jest kilka ostatnio granych utworów (nie tylko bieżący), podobieństwa z wielu źródeł się sumują,
 * wykonawcy sprzed chwili są wyciszani, a utwory losuje się ważonym losowaniem zamiast brać początek listy.
 */
object PiterfySmartPolicy {
    data class Candidate(val song: Child, val hits: Double, val best: Int, val rank: Int? = null)

    private const val DAY_MS = 86_400_000L
    private const val MEMORY_DAYS = 14
    private const val MEMORY_MAX = 600
    private val diacritics = Regex("\\p{Mn}+")
    private val nonAlnum = Regex("[^a-z0-9]+")
    private val featuring = Regex("\\s*(?:,|;|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s*", RegexOption.IGNORE_CASE)
    private val brackets = Regex("\\s*[(\\[].*?[)\\]]\\s*")

    fun fold(text: String?): String =
        Normalizer.normalize(text ?: "", Normalizer.Form.NFKD).replace(diacritics, "")
            .lowercase(Locale.ROOT).replace(nonAlnum, " ").trim()

    fun artistKey(song: Child): String = artistKey(song.artist)
    fun artistKey(name: String?): String = fold((name ?: "").split(featuring, limit = 2).first())
    fun songKey(song: Child): String = artistKey(song) + "|" + fold((song.title ?: "").replace(brackets, " "))

    /** Do kilku ostatnich wykonawców (najnowszy pierwszy), u których pytamy o podobne utwory. */
    fun seeds(history: List<Child>, max: Int = 4): List<Child> {
        val seen = HashSet<String>()
        val out = ArrayList<Child>()
        for (song in history) {
            val key = artistKey(song)
            if (key.isEmpty() || !seen.add(key)) continue
            out.add(song)
            if (out.size >= max) break
        }
        return out
    }

    /** Sumuje listy podobnych utworów: utwór polecany przez kilka wzorów ma większą wagę (`hits`). */
    fun merge(lists: List<List<Child>>): List<Candidate> {
        class Row(val song: Child, var hits: Double = 0.0, var best: Int, val sources: MutableSet<Int> = HashSet())
        val byId = LinkedHashMap<String, Row>()
        lists.forEachIndexed { source, songs ->
            songs.forEachIndexed { rank, song ->
                val row = byId.getOrPut(song.id) { Row(song, 0.0, rank) }
                if (row.sources.add(source)) row.hits += 1.0
                row.best = min(row.best, rank)
            }
        }
        return byId.values.map { Candidate(it.song, it.hits, it.best) }
    }

    /** Pamięć „już to było”: id → czas, z ostatnich dni, ograniczona liczbą wpisów. */
    fun remember(memory: Map<String, Long>, ids: Collection<String>, now: Long): Map<String, Long> {
        val next = HashMap(memory)
        ids.forEach { if (it.isNotEmpty()) next[it] = now }
        return next.entries.filter { now - it.value < MEMORY_DAYS * DAY_MS }
            .sortedByDescending { it.value }.take(MEMORY_MAX).associate { it.key to it.value }
    }

    fun recentIds(memory: Map<String, Long>, now: Long, days: Int = 7): Set<String> =
        memory.filterValues { now - it < days * DAY_MS }.keys

    private class Context(val size: Int, val exploration: Int, val artists: List<String>, val lastGenre: String, val genreRun: Int)

    private fun weight(row: Candidate, ctx: Context): Double {
        val song = row.song
        var w = 1.0 + 0.6 * (row.hits - 1) + 0.5 * (1 - min(row.best, 40) / 40.0)
        row.rank?.let { w += 0.8 * (1 - it.toDouble() / max(1, ctx.size)) }              // wyższe miejsce w rankingu gustu
        val novelty = (ctx.exploration - 50) / 50.0
        w += novelty * (if ((song.playCount ?: 0) > 0) -0.3 else 0.6)                  // suwak odkrywania
        if (song.starred != null) w += 0.7
        val age = ctx.artists.indexOf(artistKey(song))
        if (age >= 0) w *= if (age < 6) 0.12 else 0.45                                  // ten wykonawca grał przed chwilą
        val genre = fold(song.genre)
        if (genre.isNotEmpty() && ctx.genreRun >= 3 && genre == ctx.lastGenre) w *= 0.6 // nie utknij w jednym gatunku
        return max(w, 0.01)
    }

    /**
     * @param history ostatnio grane, najnowsze pierwsze
     * @param queue identyfikatory utworów już w kolejce
     * @param recent identyfikatory z pamięci (ostatnie dni)
     */
    fun pick(
        candidates: List<Candidate>,
        history: List<Child> = emptyList(),
        queue: Collection<String> = emptyList(),
        recent: Set<String> = emptySet(),
        count: Int = 5,
        exploration: Int = 50,
        maxPerArtist: Int = 1,
        random: Random = Random.Default,
    ): List<Child> {
        val inQueue = queue.toSet()
        val played = history.map { it.id }.toSet()
        val titles = history.map { songKey(it) }.toSet()
        val seen = HashSet<String>()
        val pool = candidates.filter { row ->
            val s = row.song
            if (s.id in inQueue || s.id in played || s.id in recent || PiterfyMedia.isPodcast(s)) return@filter false
            val key = songKey(s)
            if (key in titles) return@filter false
            seen.add(key)
        }
        val artists = ArrayList<String>()
        history.forEach { val a = artistKey(it); if (!artists.contains(a)) artists.add(a) }
        val genres = history.map { fold(it.genre) }
        val lastGenre = genres.firstOrNull() ?: ""
        var run = 0
        for (g in genres) { if (g.isNotEmpty() && g == lastGenre) run++ else break }
        val ctx = Context(pool.size, exploration.coerceIn(0, 100), artists, lastGenre, run)
        // ważone losowanie bez zwracania (Efraimidis–Spirakis): klucz = u^(1/w)
        val keyed = pool.map { row -> row to max(random.nextDouble(), 1e-9).pow(1.0 / weight(row, ctx)) }
            .sortedByDescending { it.second }
        val limit = max(1, if (pool.size < count * 2) maxPerArtist + 1 else maxPerArtist)
        val out = ArrayList<Child>()
        val perArtist = HashMap<String, Int>()
        for ((row, _) in keyed) {
            val a = artistKey(row.song)
            if ((perArtist[a] ?: 0) >= limit) continue
            perArtist[a] = (perArtist[a] ?: 0) + 1
            out.add(row.song)
            if (out.size >= count) break
        }
        return out
    }
}
