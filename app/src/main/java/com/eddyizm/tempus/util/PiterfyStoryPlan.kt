package com.eddyizm.tempus.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Piterfy: czysta logika „Wrapped story” (przesuwane slajdy 9:16): które slajdy powstają z danych okresu, jak długo
 * trwają, krzywe animacji, okno podglądu muzyki i kolor akcentu z okładki. Nic tu nie dotyka Androida, więc cały
 * plan da się sprawdzić testami na JVM; rysuje go PiterfyStoryRenderer, a prowadzi PiterfyStoryDialog.
 */
object PiterfyStoryPlan {
    enum class Kind { INTRO, TIME, ARTIST, TRACKS, GENRES, LISTENER, COMPARE, FINALE }

    /** [durationMs] == 0 oznacza „zostaje, aż użytkownik go zamknie” (finał). */
    data class Slide(val kind: Kind, val durationMs: Long)

    private const val NB = "\u00A0"   // twarda spacja, jak w PiterfyStatsFormat
    const val SLIDE_MS = 7000L
    const val TRACKS_MS = 12000L
    const val PREVIEW_MAX_MS = 25000L
    const val PREVIEW_FADE_IN_MS = 800L
    const val PREVIEW_FADE_OUT_MS = 600L
    /** Czas, po którym każda animacja slajdu jest skończona (eksport PNG rysuje właśnie ten stan). */
    const val END_STATE_MS = 60_000L

    fun duration(kind: Kind): Long = when (kind) {
        Kind.TRACKS -> TRACKS_MS
        Kind.FINALE -> 0L
        else -> SLIDE_MS
    }

    /** Slajdy dla okresu, w kolejności; te bez danych są pomijane (np. brak typu słuchacza, porównania, gatunków). */
    @JvmStatic fun slides(p: PiterfyStatsModel.Period): List<Slide> {
        val kinds = ArrayList<Kind>()
        kinds += Kind.INTRO
        kinds += Kind.TIME
        if (p.topArtists.any { it.label.isNotBlank() }) kinds += Kind.ARTIST
        if (p.topTracks.any { it.title.isNotBlank() }) kinds += Kind.TRACKS
        if (p.topGenres.any { it.label.isNotBlank() && it.n > 0 }) kinds += Kind.GENRES
        if (p.listener != null) kinds += Kind.LISTENER
        val c = p.compare
        if (c != null && (c.plays != null || c.minutes != null || c.artists != null)) kinds += Kind.COMPARE
        kinds += Kind.FINALE
        return kinds.map { Slide(it, duration(it)) }
    }

    // ── animacje ──
    @JvmStatic fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x

    /** Wyhamowanie (ease-out cubic): szybki start, miękkie lądowanie. */
    @JvmStatic fun ease(x: Float): Float { val k = 1f - clamp01(x); return 1f - k * k * k }

    /** Postęp elementu wjeżdżającego po [delayMs] od początku slajdu, przez [durationMs] (0..1 po wyhamowaniu). */
    @JvmStatic @JvmOverloads fun appear(t: Long, delayMs: Long, durationMs: Long = 650L): Float =
        ease((t - delayMs).toFloat() / durationMs)

    /** Opóźnienie i-tego elementu listy wjeżdżającej kaskadą. */
    @JvmStatic @JvmOverloads fun stagger(index: Int, base: Long = 120L, step: Long = 110L): Long = base + index * step

    /** Czas słuchania w trakcie licznika: jednostka (min/godz.) wynika z wartości końcowej, żeby napis nie przeskakiwał. */
    @JvmStatic @JvmOverloads fun listeningAt(minutes: Long, progress: Float, polish: Boolean = PiterfyStatsFormat.isPolish()): String {
        val p = clamp01(progress)
        if (p >= 1f) return PiterfyStatsFormat.listening(minutes, polish)
        return if (minutes >= 600) PiterfyStatsFormat.num(minutes * p / 60.0, polish) + NB + (if (polish) "godz." else "h")
        else PiterfyStatsFormat.num(minutes * p, polish) + NB + "min"
    }

    /** Napis zmiany procentowej w trakcie licznika: „▲ 125%”, „▼ 7%”, „=”. */
    @JvmStatic fun deltaAt(pct: Int, progress: Float): String {
        if (pct == 0) return "="
        val shown = (abs(pct) * clamp01(progress)).roundToInt()
        return (if (pct > 0) "▲ " else "▼ ") + shown + "%"
    }

    // ── podgląd muzyki na slajdzie z utworami ──
    /** Okno odtwarzania: skąd ([startMs]), jak długo ([lengthMs]) i jak długie są zanikanie i wygaszanie. */
    data class Preview(val startMs: Long, val lengthMs: Long, val fadeInMs: Long = PREVIEW_FADE_IN_MS, val fadeOutMs: Long = PREVIEW_FADE_OUT_MS)

    /**
     * Podgląd zaczyna się w 35% utworu (refren zwykle nie jest na samym początku), a przy krótkich (< 60 s) od zera;
     * trwa najwyżej 25 s i nie wychodzi poza koniec utworu. Nieznana długość ([seconds] <= 0) → od początku, 25 s.
     */
    @JvmStatic fun preview(seconds: Int): Preview {
        if (seconds <= 0) return Preview(0, PREVIEW_MAX_MS)
        val total = seconds * 1000L
        val start = if (seconds < 60) 0L else (total * 0.35).toLong()
        val length = min(PREVIEW_MAX_MS, max(total - start, 1000L))
        return Preview(start, length)
    }

    /** Głośność (0..1) po [elapsedMs] od startu podglądu: narastanie na początku i wyciszenie pod koniec okna. */
    @JvmStatic fun volumeAt(elapsedMs: Long, p: Preview): Float {
        if (elapsedMs < 0 || elapsedMs >= p.lengthMs) return 0f
        val up = if (p.fadeInMs > 0) elapsedMs.toFloat() / p.fadeInMs else 1f
        val down = if (p.fadeOutMs > 0) (p.lengthMs - elapsedMs).toFloat() / p.fadeOutMs else 1f
        return clamp01(min(up, down))
    }

    private val BITRATE = Regex("[&?]maxBitRate=\\d+")
    private val FORMAT = Regex("[&?]format=\\w+")

    /** Adres strumienia dla podglądu: zawsze lekkie MP3 128 kb/s, niezależnie od ustawień transkodowania odtwarzacza. */
    @JvmStatic fun previewUrl(url: String): String {
        val clean = url.replace(BITRATE) { if (it.value.startsWith("?")) "?" else "" }.replace(FORMAT) { if (it.value.startsWith("?")) "?" else "" }
            .replace("?&", "?").trimEnd('?', '&')
        return clean + (if (clean.contains('?')) "&" else "?") + "maxBitRate=128&format=mp3"
    }

    // ── kolor akcentu z okładki ──
    /**
     * Akcent z okładki: średnia z wyraźnie nasyconych i nie za ciemnych pikseli (próbka ok. 16×16), rozjaśniona tak,
     * by dobrze wyglądała na ciemnym tle. Null, gdy okładka jest szara/ciemna (wtedy zostaje akcent motywu).
     */
    @JvmStatic fun accentOf(argb: IntArray): Int? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (c in argb) {
            if ((c ushr 24) < 200) continue
            val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
            val hi = max(cr, max(cg, cb)); val lo = min(cr, min(cg, cb))
            if (hi < 90 || (hi - lo).toFloat() / hi < 0.35f) continue
            r += cr; g += cg; b += cb; n++
        }
        if (n == 0) return null
        var ar = (r / n).toInt(); var ag = (g / n).toInt(); var ab = (b / n).toInt()
        val hi = max(ar, max(ag, ab))
        if (hi < 200) {
            val k = 200f / hi
            ar = min(255, (ar * k).roundToInt()); ag = min(255, (ag * k).roundToInt()); ab = min(255, (ab * k).roundToInt())
        }
        return (0xFF shl 24) or (ar shl 16) or (ag shl 8) or ab
    }

    /** Długość słupka gatunku (0..1) względem największego; najmniejszy widoczny to 3%, zero zostaje zerem. */
    @JvmStatic fun barFraction(n: Long, max: Long): Float = if (n > 0 && max > 0) (n.toFloat() / max).coerceIn(0.03f, 1f) else 0f

    private val ISO_DAY = Regex("^(\\d{4})-(\\d{2})-(\\d{2})")

    /** Numer dnia (od 1970-01-01) z „2026-11-16”; algorytm Howarda Hinnanta, bez java.time (minSdk 24). Null przy złym zapisie. */
    @JvmStatic fun epochDay(iso: String?): Long? {
        val m = ISO_DAY.find(iso.orEmpty()) ?: return null
        val y0 = m.groupValues[1].toLong(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        val y = if (mo <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Liczba dni okresu (obie daty włącznie); przy nieczytelnych datach [fallback]. */
    @JvmStatic fun daysIn(start: String?, end: String?, fallback: Int): Int {
        val a = epochDay(start) ?: return fallback
        val b = epochDay(end) ?: return fallback
        return (abs(b - a) + 1).toInt()
    }

    @JvmStatic fun fileName(period: PiterfyStatsModel.Period, kind: Kind): String =
        "piterfy-wrapped-${period.key}-${kind.name.lowercase(Locale.ROOT)}.png"
}
