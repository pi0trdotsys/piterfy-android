package com.eddyizm.tempus.util

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/**
 * Piterfy: „Nowości od Twoich artystów” (radar nowości) — czysty model odpowiedzi serwera GET /piterfy-api/radar
 * (connect/stack/discover/radar.py) i logika półki: parsowanie, kolejność, stan przycisku „Dociągnij”, wiek wydania.
 * Bez Androida (testy JVM: PiterfyRadarModelTest); teksty i okładki dokłada PiterfyHomePanel.
 */
object PiterfyRadarModel {
    /** Pozycja radaru: wydanie z Deezera plus stan prośby o dociągnięcie (new, queued, done, failed, disabled). */
    data class Album(
        val id: String,
        val artist: String,
        val title: String,
        val type: String,
        val released: String,
        val cover: String?,
        val tracks: Int,
        val status: String,
        val note: String,
        val have: Int,
        val total: Int,
    )

    data class Listing(val albums: List<Album>, val fill: Boolean)

    /** Odpowiedź na „Dociągnij”: zaktualizowana pozycja i kod serwera (queued, already, disabled). */
    data class Requested(val album: Album, val code: String)

    enum class Action { NEW, BUSY, DONE, FAILED, OFF }

    enum class Kind { ALBUM, EP, SINGLE, OTHER }

    /** Wiek wydania: [days] (−1, gdy data jest nieczytelna) i krótka data „dd.MM” na starsze wydania. */
    data class Age(val days: Int, val shortDate: String)

    const val POLL_MS = 15_000L
    const val RECENT_DAYS = 14

    private fun JsonElement?.text(): String = if (this != null && this.isJsonPrimitive) this.asString else ""
    private fun JsonElement?.number(): Int = runCatching { if (this != null && this.isJsonPrimitive) this.asInt else 0 }.getOrDefault(0)

    /** Odpowiedź serwera → [Listing]; null, gdy to nie jest obiekt z listą „albums”. Zepsute wiersze są pomijane. */
    @JvmStatic
    fun parse(body: String?): Listing? {
        val root = runCatching { JsonParser.parseString(body ?: "") }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val rows = root.asJsonObject.get("albums")
        if (rows == null || !rows.isJsonArray) return null
        val fill = root.asJsonObject.get("fill").let { it == null || !it.isJsonPrimitive || it.asString != "false" }
        val albums = rows.asJsonArray.mapNotNull { row ->
            if (!row.isJsonObject) return@mapNotNull null
            val o = row.asJsonObject
            val id = o.get("id").text()
            val title = o.get("title").text()
            if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
            Album(
                id = id, artist = o.get("artist").text(), title = title, type = o.get("type").text(), released = o.get("released").text(),
                cover = o.get("cover").text().takeIf { it.startsWith("https://") }, tracks = o.get("tracks").number(),
                status = o.get("status").text().ifEmpty { "new" }, note = o.get("note").text(),
                have = o.get("have").number(), total = o.get("total").number(),
            )
        }
        return Listing(sort(albums), fill)
    }

    /** Najnowsze najpierw; przy tej samej dacie po wykonawcy i tytule. */
    @JvmStatic
    fun sort(albums: List<Album>): List<Album> =
        albums.sortedWith(compareByDescending<Album> { it.released }.thenBy { it.artist.lowercase() }.thenBy { it.title.lowercase() })

    @JvmStatic
    fun kindOf(type: String?): Kind = when (type?.lowercase()) {
        "album" -> Kind.ALBUM
        "ep" -> Kind.EP
        "single" -> Kind.SINGLE
        else -> Kind.OTHER
    }

    /** Stan przycisku: prośba w toku, gotowa, nieudana (można ponowić), dociąganie wyłączone na serwerze albo nowa pozycja. */
    @JvmStatic
    fun actionOf(album: Album, fill: Boolean): Action = when {
        album.status == "queued" -> Action.BUSY
        album.status == "done" -> Action.DONE
        album.status == "failed" -> Action.FAILED
        !fill -> Action.OFF
        else -> Action.NEW
    }

    @JvmStatic
    fun shouldPoll(albums: List<Album>): Boolean = albums.any { it.status == "queued" }

    /** Numer dnia (liczba dni od 1970-01-01) dla daty kalendarzowej — bez java.time (minSdk 24). */
    internal fun dayNumber(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Ile dni temu wyszło wydanie („YYYY-MM-DD”); [offsetMillis] to przesunięcie strefy czasowej użytkownika. */
    @JvmStatic
    fun age(released: String?, nowMillis: Long, offsetMillis: Int): Age {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(released ?: "") ?: return Age(-1, "")
        val (y, mo, d) = m.destructured
        val month = mo.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..31) return Age(-1, "")
        val today = Math.floorDiv(nowMillis + offsetMillis, 86_400_000L)
        return Age((today - dayNumber(y.toInt(), month, day)).toInt(), "$d.$mo")
    }

    /** Kopia pozycji z nowym stanem prośby (dla widoku, zanim odpowie serwer). */
    @JvmStatic
    fun withStatus(album: Album, status: String, note: String): Album = album.copy(status = status, note = note)

    /** Pozycja po odpowiedzi na „Dociągnij” (serwer zwraca zaktualizowaną pozycję) albo bez zmian, gdy odpowiedź jest pusta. */
    @JvmStatic
    fun replace(albums: List<Album>, updated: Album?): List<Album> =
        if (updated == null) albums else albums.map { if (it.id == updated.id) updated else it }

    /** Odpowiedź POST /piterfy-api/radar/fetch → [Requested]; null przy złym formacie. */
    @JvmStatic
    fun parseFetch(body: String?): Requested? {
        val root = runCatching { JsonParser.parseString(body ?: "") }.getOrNull() ?: return null
        if (!root.isJsonObject) return null
        val code = root.asJsonObject.get("result").text()
        val album = parse("""{"albums":[${root.asJsonObject.get("album")}]}""")?.albums?.firstOrNull() ?: return null
        return Requested(album, code)
    }
}
