package com.eddyizm.tempus.util

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Piterfy: dane „Statystyk dla nerdów” z serwera (GET /piterfy-api/stats, liczy je connect/stack/discover/stats.py)
 * jako zwykłe klasy Kotlina, plus układanie ich pod wykresy. Parsowanie przez Gson, żeby dało się je testować na JVM
 * (org.json w testach jednostkowych Androida to atrapa). Test kontraktu: src/test/resources/piterfy_stats_sample.json
 * jest prawdziwym wynikiem stats.build.
 */
object PiterfyStatsModel {
    data class Count(val label: String, val n: Long)
    data class Record(val title: String, val artist: String, val year: Int?, val seconds: Int?, val bytes: Long?)
    data class Replace(val id: String, val title: String, val artist: String, val format: String, val sourceKbps: Int?, val plays: Int)
    data class Quality(
        val checked: Int, val pending: Int, val ok: Int, val suspect: Int, val fake: Int, val transcoded: Int,
        val upsampled: Int, val unknown: Int, val genuineLossless: Int, val replaceTotal: Int, val replace: List<Replace>,
    )
    data class Library(
        val tracks: Long, val albums: Long, val artists: Long, val seconds: Long, val bytes: Long, val starred: Long,
        val played: Long, val unplayed: Long, val formats: List<Count>, val formatBytes: Map<String, Long>,
        val lossless: Long, val lossy: Long, val lossyBitrates: List<Count>, val sampleRates: List<Count>,
        val bitDepths: List<Count>, val hires: Long, val hiresUpsampled: Long, val decades: List<Count>,
        val oldest: Record?, val newest: Record?, val averageYear: Int?, val longest: Record?, val shortest: Record?,
        val biggest: Record?, val topGenres: List<Count>, val quality: Quality,
    )
    data class Week(val day: String, val n: Long, val bytes: Long)
    data class Rate(val tried: Int, val got: Int)
    data class Acquisition(
        val tried: Int, val got: Int, val missed: Int, val pending: Int, val soulseekRate: Double?,
        val outcomes: Map<String, Int>, val engines: Map<String, Rate>, val pools: Map<String, Rate>,
    )
    data class Disk(val total: Long, val used: Long, val free: Long)
    data class Server(
        val weeks: List<Week>, val added7: Int, val added30: Int, val firstAdded: String?, val acquisition: Acquisition,
        val liked: Int, val trashed: Int, val hitRate: Double?, val disk: Disk?, val trashBytes: Long,
        val lastRun: String?, val lastScan: String?, val scanning: Boolean,
    )
    data class Listener(val id: String, val name: String, val blurb: String, val why: List<String>)
    data class Compare(val plays: Int?, val minutes: Int?, val artists: Int?, val prevPlays: Int)
    data class Track(val title: String, val artist: String, val plays: Int)
    data class Period(
        val kind: String, val key: String, val label: String, val start: String, val end: String, val partial: Boolean,
        val plays: Long, val minutes: Long, val tracks: Int, val artists: Int, val activeDays: Int, val longestStreak: Int,
        val busiestDay: String?, val busiestPlays: Int, val topArtists: List<Count>, val topTracks: List<Track>,
        val newArtists: Int?, val nightShare: Double?, val peakHour: Int?, val listener: Listener?, val compare: Compare?,
    )
    data class Stats(val generated: String?, val library: Library, val server: Server, val periods: List<Period>, val historySince: String?)

    // ── parsowanie ──
    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.arr(name: String): JsonArray = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonElement?.present() = this != null && !this.isJsonNull
    private fun JsonObject.str(name: String): String? = get(name).takeIf { it.present() && it!!.isJsonPrimitive }?.asString
    private fun JsonObject.long(name: String, default: Long = 0): Long = get(name).takeIf { it.present() && it!!.isJsonPrimitive }?.runCatching { asDouble.toLong() }?.getOrNull() ?: default
    private fun JsonObject.int(name: String, default: Int = 0): Int = long(name, default.toLong()).toInt()
    private fun JsonObject.intOrNull(name: String): Int? = get(name).takeIf { it.present() && it!!.isJsonPrimitive }?.runCatching { asDouble.toInt() }?.getOrNull()
    private fun JsonObject.doubleOrNull(name: String): Double? = get(name).takeIf { it.present() && it!!.isJsonPrimitive }?.runCatching { asDouble }?.getOrNull()
    private fun JsonObject.bool(name: String): Boolean = get(name).takeIf { it.present() && it!!.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull() ?: false
    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
    private fun JsonArray.pairs(): List<JsonArray> = mapNotNull { it.takeIf { e -> e.isJsonArray }?.asJsonArray }

    private fun counts(array: JsonArray): List<Count> =
        array.pairs().filter { it.size() >= 2 }.map { Count(it[0].asString, it[1].asDouble.toLong()) }

    private fun record(o: JsonObject?): Record? = o?.let {
        Record(it.str("title").orEmpty(), it.str("artist").orEmpty(), it.intOrNull("year"), it.intOrNull("seconds"),
            it.get("bytes")?.takeIf { e -> e.isJsonPrimitive }?.asLong)
    }

    private fun quality(o: JsonObject?): Quality {
        o ?: return Quality(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList())
        return Quality(o.int("checked"), o.int("pending"), o.int("ok"), o.int("suspect"), o.int("fake"), o.int("transcoded"),
            o.int("upsampled"), o.int("unknown"), o.int("genuine_lossless"), o.int("replace_total"),
            o.arr("replace").objects().map { Replace(it.str("id").orEmpty(), it.str("title").orEmpty(), it.str("artist").orEmpty(),
                it.str("format").orEmpty(), it.intOrNull("source_kbps"), it.int("plays")) })
    }

    private fun library(o: JsonObject?): Library {
        o ?: return library(JsonObject())
        val formats = o.arr("formats").pairs().filter { it.size() >= 3 }
        val years = o.obj("years")
        val records = o.obj("records")
        return Library(
            o.long("tracks"), o.long("albums"), o.long("artists"), o.long("seconds"), o.long("bytes"), o.long("starred"),
            o.long("played"), o.long("unplayed"),
            formats.map { Count(it[0].asString, it[1].asLong) }, formats.associate { it[0].asString to it[2].asLong },
            o.long("lossless"), o.long("lossy"), counts(o.arr("lossy_bitrates")), counts(o.arr("sample_rates")),
            counts(o.arr("bit_depths")), o.long("hires"), o.long("hires_upsampled"), counts(o.arr("decades")),
            record(years?.obj("oldest")), record(years?.obj("newest")), years?.intOrNull("average"),
            record(records?.obj("longest")), record(records?.obj("shortest")), record(records?.obj("biggest")),
            counts(o.arr("top_genres")), quality(o.obj("quality")),
        )
    }

    private fun rates(o: JsonObject?): Map<String, Rate> =
        o?.entrySet()?.filter { it.value.isJsonObject }?.associate { (k, v) -> k to Rate(v.asJsonObject.int("tried"), v.asJsonObject.int("got")) } ?: emptyMap()

    private fun server(o: JsonObject?): Server {
        o ?: return server(JsonObject())
        val growth = o.obj("growth")
        val acq = o.obj("acquisition")
        val discover = o.obj("discover")
        val disk = o.obj("disk")?.let { Disk(it.long("total"), it.long("used"), it.long("free")) }
        return Server(
            growth?.arr("weeks")?.pairs()?.filter { it.size() >= 3 }?.map { Week(it[0].asString, it[1].asLong, it[2].asLong) } ?: emptyList(),
            growth?.int("added_7") ?: 0, growth?.int("added_30") ?: 0, growth?.str("first"),
            Acquisition(acq?.int("tried") ?: 0, acq?.int("got") ?: 0, acq?.int("missed") ?: 0, acq?.int("pending") ?: 0,
                acq?.doubleOrNull("soulseek_rate"),
                acq?.obj("outcomes")?.entrySet()?.associate { (k, v) -> k to v.asInt } ?: emptyMap(),
                rates(acq?.obj("engines")), rates(acq?.obj("pools"))),
            discover?.int("liked") ?: 0, discover?.int("trashed") ?: 0, discover?.doubleOrNull("hit_rate"),
            disk, o.obj("trash")?.long("bytes") ?: 0L, o.str("last_run"), o.str("last_scan"), o.bool("scanning"),
        )
    }

    private fun period(o: JsonObject): Period {
        val busiest = o.obj("busiest_day")
        val listener = o.obj("listener")?.let { Listener(it.str("id").orEmpty(), it.str("name").orEmpty(), it.str("blurb").orEmpty(),
            it.arr("why").map { w -> w.asString }) }
        val compare = o.obj("compare")?.let { Compare(it.intOrNull("plays"), it.intOrNull("minutes"), it.intOrNull("artists"), it.int("prev_plays")) }
        return Period(
            o.str("kind").orEmpty(), o.str("key").orEmpty(), o.str("label").orEmpty(), o.str("start").orEmpty(), o.str("end").orEmpty(),
            o.bool("partial"), o.long("plays"), o.long("minutes"), o.int("tracks"), o.int("artists"), o.int("active_days"),
            o.int("longest_streak"), busiest?.str("day"), busiest?.int("plays") ?: 0,
            o.arr("top_artists").objects().map { Count(it.str("name").orEmpty(), it.long("plays")) },
            o.arr("top_tracks").objects().map { Track(it.str("title").orEmpty(), it.str("artist").orEmpty(), it.int("plays")) },
            o.intOrNull("new_artists"), o.doubleOrNull("night_share"), o.intOrNull("peak_hour"), listener, compare,
        )
    }

    /** Dane z serwera albo null, gdy to nie JSON statystyk. */
    @JvmStatic fun parse(json: String?): Stats? = runCatching {
        val root = JsonParser.parseString(json ?: return null).asJsonObject
        if (!root.has("library")) return null
        val wrapped = root.obj("wrapped")
        Stats(root.str("generated"), library(root.obj("library")), server(root.obj("server")),
            wrapped?.arr("periods")?.objects()?.map(::period) ?: emptyList(), wrapped?.str("history_since"))
    }.getOrNull()

    // ── układanie pod wykresy ──
    enum class Tone { GOOD, WARN, BAD, MUTED, ACCENT, FREE }
    data class Segment(val key: String, val n: Double, val fraction: Float, val tone: Tone)

    /** Segmenty paska jakości: bez zastrzeżeń, podejrzane (też „podbite” hi-res), udawane (też przekodowane), niezbadane. */
    @JvmStatic fun qualitySegments(q: Quality): List<Segment> {
        val raw = listOf(
            Triple("ok", q.ok, Tone.GOOD), Triple("suspect", q.suspect + q.upsampled, Tone.WARN),
            Triple("fake", q.fake + q.transcoded, Tone.BAD), Triple("pending", q.pending + q.unknown, Tone.MUTED),
        )
        val total = raw.sumOf { it.second }.toDouble()
        return raw.map { Segment(it.first, it.second.toDouble(), if (total > 0) (it.second / total).toFloat() else 0f, it.third) }
    }

    /** Części paska dysku (biblioteka, kosz, pozostałe, wolne) i procent zajętego miejsca; null bez danych. */
    @JvmStatic fun diskSegments(disk: Disk?, libraryBytes: Long, trashBytes: Long): Pair<List<Segment>, Int>? {
        if (disk == null || disk.total <= 0) return null
        val library = minOf(libraryBytes, disk.used).coerceAtLeast(0)
        val trash = minOf(trashBytes, (disk.used - library).coerceAtLeast(0)).coerceAtLeast(0)
        val other = (disk.used - library - trash).coerceAtLeast(0)
        val total = disk.total.toDouble()
        fun seg(key: String, bytes: Long, tone: Tone) = Segment(key, bytes.toDouble(), (bytes / total).toFloat(), tone)
        return listOf(seg("library", library, Tone.ACCENT), seg("trash", trash, Tone.WARN), seg("other", other, Tone.MUTED),
            seg("free", disk.free, Tone.FREE)) to Math.round(disk.used * 100.0 / disk.total).toInt()
    }
}
