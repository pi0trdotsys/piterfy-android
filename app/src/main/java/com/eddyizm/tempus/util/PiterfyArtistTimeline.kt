package com.eddyizm.tempus.util

import java.text.Normalizer

/**
 * Piterfy: czysta (bez Androida, testowana na JVM) logika osi czasu dyskografii na stronie wykonawcy: albumy z biblioteki i
 * nowe wydania z radaru (GET /piterfy-api/radar), pogrupowane według roku, od najnowszego. Wydanie z radaru, które już jest
 * w bibliotece (ten sam tytuł), nie dubluje się; reszta dostaje oznaczenie „Nowość”.
 */
object PiterfyArtistTimeline {
    /** Pozycja osi: [albumId] ustawione dla albumu z biblioteki, [radarId] dla nowego wydania spoza biblioteki. */
    data class Entry(val year: Int, val title: String, val albumId: String?, val radarId: String?, val coverId: String?, val released: String) {
        val isNew: Boolean get() = radarId != null
    }

    data class Year(val year: Int, val entries: List<Entry>)

    /** Album z biblioteki w postaci niezależnej od modelu Subsonic (żeby testować bez Androida). */
    data class Library(val id: String, val title: String, val year: Int, val coverId: String?)

    /** Tytuł do porównań: małe litery, bez akcentów, znaków specjalnych i dopisków „(Deluxe Edition)” itp. */
    @JvmStatic
    fun key(title: String?): String {
        // „ł” i „đ” nie rozkładają się na literę z akcentem, więc zamieniamy je ręcznie
        val plain = Normalizer.normalize((title ?: "").replace('ł', 'l').replace('Ł', 'L').replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return plain.lowercase().replace(Regex("[\\(\\[][^\\)\\]]*[\\)\\]]"), " ").replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /** Czy pozycja radaru dotyczy tego wykonawcy (porównanie nazw bez akcentów i wielkości liter). */
    @JvmStatic
    fun sameArtist(artist: String?, radarArtist: String?): Boolean {
        val a = key(artist)
        return a.isNotEmpty() && a == key(radarArtist)
    }

    private fun yearOf(released: String): Int = Regex("^(\\d{4})").find(released)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Oś czasu: lata malejąco, w roku najpierw nowości (od najnowszych), potem albumy z biblioteki wg tytułu. Lata 0 (brak daty) na końcu. */
    @JvmStatic
    fun build(artist: String?, library: List<Library>, radar: List<PiterfyRadarModel.Album>): List<Year> {
        val have = library.map { key(it.title) }.toSet()
        val fresh = radar.filter { sameArtist(artist, it.artist) && key(it.title) !in have }
            .map { Entry(yearOf(it.released), it.title, null, it.id, it.cover, it.released) }
        val owned = library.map { Entry(it.year, it.title, it.id, null, it.coverId, "") }
        val all = fresh.sortedByDescending { it.released } + owned.sortedBy { key(it.title) }
        return all.groupBy { it.year }
            .toSortedMap(compareBy<Int> { if (it == 0) Int.MIN_VALUE else it }.reversed())
            .map { (year, entries) -> Year(year, entries) }
    }

    /** Ile nowości (spoza biblioteki) ma ten wykonawca. */
    @JvmStatic
    fun newCount(timeline: List<Year>): Int = timeline.sumOf { y -> y.entries.count { it.isNew } }
}
