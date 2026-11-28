package com.eddyizm.tempus.util

import com.eddyizm.tempus.subsonic.models.Child
import kotlin.math.max
import kotlin.random.Random

/**
 * Piterfy: „Radio z utworu / wykonawcy” — nieskończona kolejka podobnych utworów z biblioteki.
 * Czysta logika bez sieci i Androida (testy jednostkowe), ten sam algorytm co `radio-core.js` w connect/web:
 * co odfiltrować, jak ułożyć (lekkie tasowanie, ten sam wykonawca nie dwa razy pod rząd), kiedy dociągnąć kolejne,
 * co zapamiętać, żeby się nie powtarzać, i kiedy radio ma się wyłączyć. Pobieranie podobnych i dokładanie do kolejki
 * robi PiterfyRadioManager. (Nazwa „radio” w aplikacji oznacza też stacje internetowe — tu chodzi o radio z utworu.)
 */
object PiterfyRadioPlan {
    const val MEMORY_SIZE = 100   // tyle ostatnich utworów pamiętamy (bez powtórek)
    const val TOP_UP_AT = 3       // dociągamy, gdy po bieżącym zostaje najwyżej tyle utworów
    const val BATCH = 10          // tyle utworów dokładamy za jednym razem
    const val GRACE_MS = 5_000L   // karencja po naszej zmianie kolejki, zanim uznamy obcy utwór za „coś innego”
    private const val JITTER = 0.35   // jak mocno miesza kolejność podobieństwa (ułamek długości listy)
    private const val LOOKAHEAD = 6   // w tylu najbliższych pozycjach szukamy innego wykonawcy

    /** Klucz wykonawcy do reguły „nie dwa razy pod rząd”: bez akcentów, interpunkcji i dopisku feat. */
    fun artistKey(song: Child?): String = PiterfySmartPolicy.artistKey(song?.artist)

    /** Odcinek podcastu nie wchodzi do radia. */
    fun podcastLike(song: Child?): Boolean = song != null && PiterfyMedia.isPodcast(song)

    /**
     * Oczyszcza listę podobnych: bez pustych, podcastów, ocenionych na 1★, utworów z [exclude] i [memory] oraz duplikatów.
     */
    fun clean(
        songs: List<Child?>?, exclude: Collection<String> = emptySet(), memory: Collection<String> = emptyList(),
        isPodcast: (Child) -> Boolean = ::podcastLike,
    ): List<Child> {
        val skip = HashSet<String>(exclude).apply { addAll(memory) }
        val seen = HashSet<String>()
        val result = ArrayList<Child>()
        for (song in songs.orEmpty()) {
            if (song == null) continue
            val id = song.id
            if (id.isEmpty() || id in skip || !seen.add(id) || isPodcast(song) || song.userRating == 1) continue
            result.add(song)
        }
        return result
    }

    /**
     * Układa utwory: kolejność z serwera (podobieństwo) z lekkim zaszumieniem, żeby radio za każdym razem było trochę inne,
     * a potem reguła: ten sam wykonawca nie dwa razy pod rząd (jeśli w najbliższych [LOOKAHEAD] pozycjach jest ktoś inny).
     * [previousArtist] to klucz wykonawcy utworu, po którym zaczyna się ta partia.
     */
    fun order(songs: List<Child>, rng: Random = Random.Default, previousArtist: String = ""): List<Child> {
        val spread = max(2.0, songs.size * JITTER)
        val pool = songs.mapIndexed { i, song -> song to i + rng.nextDouble() * spread }
            .sortedBy { it.second }.map { it.first }.toMutableList()
        val out = ArrayList<Child>(pool.size)
        var last = previousArtist
        while (pool.isNotEmpty()) {
            // z najbliższych pozycji bierzemy innego wykonawcę niż poprzedni, a spośród nich tego, który ma najwięcej
            // utworów jeszcze w puli (żeby na końcu partii nie został jednolity ogon jednego wykonawcy)
            val left = HashMap<String, Int>()
            for (s in pool) left.merge(artistKey(s), 1, Int::plus)
            var pick = -1
            for (i in 0 until minOf(LOOKAHEAD, pool.size)) {
                val key = artistKey(pool[i])
                if (key != last && (pick < 0 || left.getValue(key) > left.getValue(artistKey(pool[pick])))) pick = i
            }
            if (pick < 0) pick = 0
            val song = pool.removeAt(pick)
            out.add(song)
            last = artistKey(song)
        }
        return out
    }

    /** Kolejka na start: nasiono (jeśli jest i nie jest podcastem) pierwsze, potem podobne po oczyszczeniu i ułożeniu. */
    fun build(
        seed: Child?, similar: List<Child?>?, memory: Collection<String> = emptyList(),
        exclude: Collection<String> = emptySet(), rng: Random = Random.Default,
        isPodcast: (Child) -> Boolean = ::podcastLike,
    ): List<Child> {
        val skip = HashSet<String>(exclude).apply { seed?.id?.takeIf { it.isNotEmpty() }?.let { add(it) } }
        val rest = order(clean(similar, skip, memory, isPodcast), rng, seed?.let { artistKey(it) } ?: "")
        val first = if (seed != null && seed.id.isNotEmpty() && !isPodcast(seed)) listOf(seed) else emptyList()
        return first + rest
    }

    /** Czy dociągnąć kolejne: po bieżącym utworze zostaje najwyżej [threshold] pozycji. */
    fun needMore(queueLength: Int, currentIndex: Int, threshold: Int = TOP_UP_AT): Boolean {
        if (queueLength <= 0 || currentIndex < 0) return false
        return queueLength - 1 - currentIndex <= threshold
    }

    /** Pamięć ostatnich utworów: nowe na początku, bez duplikatów, najwyżej [max]. */
    fun remember(memory: List<String>, ids: Collection<String>, max: Int = MEMORY_SIZE): List<String> {
        val fresh = ids.filter { it.isNotEmpty() }.distinct()
        return (fresh + memory.filter { it !in fresh }).take(max)
    }

    /**
     * Czy radio ma się wyłączyć: gra utwór, którego radio nie dołożyło (użytkownik uruchomił coś innego), a od naszej
     * ostatniej zmiany kolejki minęła karencja. [known] — zbiór id dołożonych przez radio.
     */
    fun shouldStop(currentId: String?, known: Set<String>, sinceChangeMs: Long, graceMs: Long = GRACE_MS): Boolean {
        if (currentId.isNullOrEmpty()) return false
        if (sinceChangeMs < graceMs) return false
        return currentId !in known
    }

    /** Id, od których pytamy o podobne przy dociąganiu: bieżący i dwa poprzednie utwory kolejki (bez powtórek). */
    fun seedsForTopUp(queueIds: List<String>, currentIndex: Int): List<String> {
        val out = ArrayList<String>()
        var i = minOf(currentIndex, queueIds.size - 1)
        while (i >= 0 && out.size < 3) {
            val id = queueIds[i]
            if (id.isNotEmpty() && id !in out) out.add(id)
            i--
        }
        return out
    }

    /** Łączy listy podobnych z kilku zapytań „na zmianę” (pierwsze z każdej listy najpierw). */
    fun <T> interleave(lists: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (list in lists) if (i < list.size) out.add(list[i])
        return out
    }
}
