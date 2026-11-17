package com.eddyizm.tempus.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Piterfy: dane panelu „Mój gust” (statyczny plik gust/data/taste.json, który co noc liczy discover/taste.py, i opcjonalnie
 * spotify-baseline.json) jako zwykłe klasy Kotlina oraz układanie ich pod wykresy, tak samo jak robi to strona
 * connect/stack/gust/index.html. Parsowanie przez Gson, żeby dało się je testować na JVM (org.json w testach
 * jednostkowych Androida to atrapa). Format wersji 2; inne wersje i nie-JSON dają null.
 */
object PiterfyTasteModel {
    data class Totals(val plays: Long, val minutes: Long, val artists: Int, val tracks: Int)
    /** [img] to adres zdjęcia wykonawcy (Deezer) albo null. */
    data class Artist(val name: String, val plays: Long, val img: String?)
    data class Song(val title: String, val artist: String, val album: String?, val plays: Long)
    data class Day(val day: String, val n: Int)
    data class Count(val label: String, val n: Long)
    /** Jeden słuchany utwór na mapie nastroju: energia i walencja 0..1, tempo w BPM, [plays] odsłuchów. */
    data class Dot(val title: String, val artist: String, val energy: Double, val valence: Double, val tempo: Double, val plays: Long)
    data class Averages(val energy: Double, val dance: Double, val valence: Double, val tempo: Double)
    data class Mood(val coverage: Double, val averages: Averages?, val dots: List<Dot>)
    /** Siatka „dzień tygodnia × godzina” ze scrobbli Last.fm: [grid] ma 7 wierszy po 24 liczby. */
    data class Clock(val days: List<String>, val grid: List<List<Int>>)
    data class Liked(val title: String, val artist: String, val via: String)
    data class Discover(val downloaded: Int, val liked: Int, val trashed: Int, val hitRate: Double?, val recentLiked: List<Liked>)
    data class Taste(
        val generated: String?, val periodDays: Int, val estimated: Boolean, val historySince: String?, val totals: Totals,
        val daily: List<Day>, val artists: List<Artist>, val tracks: List<Song>, val genres: List<Count>, val decades: List<Count>,
        val mood: Mood, val clock: Clock?, val discover: Discover?,
    )
    /** Eksport Spotify z polubionymi: skąd przyszedł gust. Średnie mogą być nieznane (null). */
    data class Baseline(val totalTracks: Int, val artists: List<String>, val genres: List<String>, val averages: Averages?)

    // ── JSON ──
    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonElement?.present() = this != null && !this.isJsonNull
    private fun JsonObject.str(name: String): String? = get(name).takeIf { it.present() && it!!.isJsonPrimitive }?.asString
    private fun JsonObject.long(name: String, default: Long = 0): Long = get(name).takeIf { it.present() && it!!.isJsonPrimitive }?.runCatching { asDouble.toLong() }?.getOrNull() ?: default
    private fun JsonObject.int(name: String, default: Int = 0): Int = long(name, default.toLong()).toInt()
    private fun JsonObject.double(name: String, default: Double = 0.0): Double = doubleOrNull(name) ?: default
    private fun JsonObject.doubleOrNull(name: String): Double? = get(name).takeIf { it.present() && it!!.isJsonPrimitive }?.runCatching { asDouble }?.getOrNull()
    private fun JsonObject.bool(name: String): Boolean = get(name).takeIf { it.present() && it!!.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull() ?: false
    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
    private fun JsonArray.pairs(): List<JsonArray> = mapNotNull { it.takeIf { e -> e.isJsonArray && e.asJsonArray.size() >= 2 }?.asJsonArray }
    private fun JsonElement.stringOrNull(): String? = takeIf { it.present() && it.isJsonPrimitive }?.asString
    private fun JsonElement.doubleOrZero(): Double = takeIf { it.present() && it.isJsonPrimitive }?.runCatching { asDouble }?.getOrNull() ?: 0.0

    private fun averages(o: JsonObject?, energy: String, dance: String, valence: String, tempo: String): Averages? {
        o ?: return null
        val e = o.doubleOrNull(energy) ?: return null
        val v = o.doubleOrNull(valence) ?: return null
        return Averages(e, o.double(dance), v, o.double(tempo))
    }

    /** Dane panelu albo null, gdy to nie JSON w wersji 2 (np. serwer jeszcze nie policzył panelu). */
    @JvmStatic fun parse(json: String?): Taste? = runCatching {
        val root = JsonParser.parseString(json ?: return null).asJsonObject
        if (root.int("version") != 2) return null
        val totals = root.obj("totals")
        val mood = root.obj("mood")
        val clock = root.obj("clock")?.let { c ->
            val grid = c.arr("grid").map { row -> if (row.isJsonArray) row.asJsonArray.map { it.doubleOrZero().toInt() } else emptyList() }
            if (grid.isEmpty()) null else Clock(c.arr("days").mapNotNull { it.stringOrNull() }, grid)
        }
        val discover = root.obj("discover")?.let { d ->
            Discover(d.int("downloaded"), d.int("liked"), d.int("trashed"), d.doubleOrNull("hit_rate"),
                d.arr("recent_liked").objects().map { Liked(it.str("title").orEmpty(), it.str("artist").orEmpty(), it.str("via").orEmpty()) })
        }
        Taste(
            root.str("generated"), root.int("period_days", 30), root.bool("estimated"), root.str("history_since"),
            Totals(totals?.long("plays") ?: 0, totals?.long("minutes") ?: 0, totals?.int("artists") ?: 0, totals?.int("tracks") ?: 0),
            root.arr("daily").pairs().map { Day(it[0].asString, it[1].asInt) },
            root.arr("top_artists").objects().map { Artist(it.str("name").orEmpty(), it.long("plays"), it.str("img")?.takeIf { s -> s.isNotBlank() }) },
            root.arr("top_tracks").objects().map { Song(it.str("title").orEmpty(), it.str("artist").orEmpty(), it.str("album")?.takeIf { s -> s.isNotBlank() }, it.long("plays")) },
            counts(root.arr("top_genres")), counts(root.arr("decades")),
            Mood(mood?.double("coverage") ?: 0.0, averages(mood?.obj("averages"), "energy", "dance", "valence", "tempo"),
                mood?.arr("tracks")?.objects()?.map { Dot(it.str("n").orEmpty(), it.str("a").orEmpty(), it.double("e"), it.double("v"), it.double("t"), it.long("p")) } ?: emptyList()),
            clock, discover,
        )
    }.getOrNull()

    /** Eksport Spotify (data/spotify-baseline.json) albo null, gdy go nie ma lub jest nieczytelny. */
    @JvmStatic fun parseBaseline(json: String?): Baseline? = runCatching {
        val root = JsonParser.parseString(json ?: return null).asJsonObject
        Baseline(root.int("total_tracks"), root.arr("top_artists").pairs().map { it[0].asString }, root.arr("top_genres").pairs().map { it[0].asString },
            averages(root.obj("averages"), "Energy", "Danceability", "Valence", "Tempo"))
    }.getOrNull()

    private fun counts(array: JsonArray): List<Count> =
        array.pairs().mapNotNull { runCatching { Count(it[0].asString, it[1].asLong) }.getOrNull() }

    // ── układanie pod wykresy ──
    enum class Quadrant { EUFORIA, NAPIECIE, SPOKOJ, MELANCHOLIA }

    /** Ćwiartka mapy nastroju: energia ≥ 0,5 i walencja ≥ 0,5 to euforia, sama energia napięcie, sama walencja spokój, reszta melancholia. */
    @JvmStatic fun quadrant(energy: Double, valence: Double): Quadrant =
        if (energy >= 0.5) (if (valence >= 0.5) Quadrant.EUFORIA else Quadrant.NAPIECIE) else (if (valence >= 0.5) Quadrant.SPOKOJ else Quadrant.MELANCHOLIA)

    /** Udział każdej ćwiartki w odsłuchach (ważone liczbą odsłuchów, razem 1); bez odsłuchów wszystko 0. */
    @JvmStatic fun moodShares(dots: List<Dot>): Map<Quadrant, Double> {
        val sums = Quadrant.values().associateWith { 0.0 }.toMutableMap()
        dots.forEach { sums[quadrant(it.energy, it.valence)] = sums.getValue(quadrant(it.energy, it.valence)) + it.plays }
        val total = sums.values.sum()
        return sums.mapValues { if (total > 0) it.value / total else 0.0 }
    }

    /** Promień kropki względem największej (0..1): rośnie z pierwiastkiem odsłuchów, jak na stronie. */
    @JvmStatic fun dotSize(plays: Long, maxPlays: Long): Float = if (maxPlays > 0) sqrt(plays.toDouble() / maxPlays).toFloat().coerceIn(0f, 1f) else 0f

    /** Poziom jasności komórki zegara słuchania 0..1 względem najgorętszej godziny. */
    @JvmStatic fun clockLevel(n: Int, max: Int): Float = if (n > 0 && max > 0) (n.toFloat() / max).coerceIn(0f, 1f) else 0f

    @JvmStatic fun clockMax(clock: Clock): Int = max(1, clock.grid.maxOfOrNull { row -> row.maxOrNull() ?: 0 } ?: 0)

    /** Wysokość słupka (0..1) względem największego; zero zostaje zerem, najmniejszy widoczny ma 3%. */
    @JvmStatic fun barFraction(n: Long, max: Long): Float = if (n > 0 && max > 0) (n.toFloat() / max).coerceIn(0.03f, 1f) else 0f

    /** Czy dzień „rrrr-mm-dd” to sobota albo niedziela (1970-01-01 był czwartkiem); nieczytelny zapis → false. */
    @JvmStatic fun isWeekend(day: String): Boolean {
        val d = PiterfyStoryPlan.epochDay(day) ?: return false
        val dow = Math.floorMod(d + 4, 7L)   // 0 = niedziela
        return dow == 0L || dow == 6L
    }

    /** Które z etykiet [items] nie występują na liście [other] (bez względu na wielkość liter) — „nowe” w porównaniu z Spotify. */
    @JvmStatic fun newOnes(items: List<String>, other: List<String>): Set<String> {
        val known = other.map { it.lowercase() }.toSet()
        return items.filter { it.lowercase() !in known }.toSet()
    }

    /** Różnica jednej cechy względem eksportu Spotify: [diff] w jednostkach [unit] (punkty procentowe albo BPM). */
    data class Delta(val key: String, val diff: Double, val unit: String) {
        /** Kierunek zmiany: 1 w górę, −1 w dół, 0 bez istotnej zmiany (mniej niż 2 jednostki). */
        val direction: Int get() = if (kotlin.math.abs(diff) < 2) 0 else if (diff > 0) 1 else -1
    }

    @JvmStatic fun deltas(now: Averages?, base: Averages?): List<Delta> {
        if (now == null || base == null) return emptyList()
        return listOf(
            Delta("energy", (now.energy - base.energy) * 100, "pp"),
            Delta("dance", (now.dance - base.dance) * 100, "pp"),
            Delta("valence", (now.valence - base.valence) * 100, "pp"),
            Delta("tempo", now.tempo - base.tempo, "BPM"),
        )
    }

    /** Ułamek łuku miernika tempa: 60 BPM to 0, 180 BPM to 1. */
    @JvmStatic fun tempoFraction(bpm: Double): Float = ((bpm - 60) / 120).toFloat().coerceIn(0f, 1f)

    /** Części pierścienia odkryć: polubione, w koszu i pozostałe (pobrane bez oceny), razem [Discover.downloaded]. */
    @JvmStatic fun discoverParts(d: Discover): Triple<Int, Int, Int> = Triple(d.liked, d.trashed, max(0, d.downloaded - d.liked - d.trashed))
}
