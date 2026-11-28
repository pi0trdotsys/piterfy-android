package com.eddyizm.tempus.util

import com.google.gson.JsonParser
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Piterfy: czysty model fali dźwięku utworu (bez Androida, testy: PiterfyPeaksModelTest). Serwer (GET /piterfy-api/peaks/{id})
 * zwraca {"v":1,"n":120–240,"peaks":[0–255,…],"duration":sekundy}; 202 {"pending":true} oznacza „liczę, wróć za chwilę”,
 * każdy inny kod — brak fali (wtedy zostaje zwykły pasek). Tu: parsowanie z walidacją, zmiana liczby słupków do szerokości
 * widoku, wysokości słupków, indeks słupka pod palcem i opóźnienia ponowień.
 */
object PiterfyPeaksModel {
    const val VERSION = 1
    const val MAX_PEAKS = 4096
    const val MAX_ATTEMPTS = 6

    class Peaks(val values: IntArray, val durationSec: Double) {
        override fun equals(other: Any?) = other is Peaks && other.durationSec == durationSec && other.values.contentEquals(values)
        override fun hashCode() = values.contentHashCode() * 31 + durationSec.hashCode()
    }

    sealed class Outcome {
        class Ready(val peaks: Peaks) : Outcome()
        /** Serwer jeszcze liczy falę tego utworu. */
        object Pending : Outcome()
        /** Brak fali (nieobsługiwany utwór, stary serwer, błąd): pasek zostaje zwykły. */
        object Missing : Outcome()
    }

    /** Odpowiedź serwera ([code], [body]) → wynik. Zepsuta albo za duża odpowiedź to [Outcome.Missing], nigdy wyjątek. */
    @JvmStatic
    fun parse(code: Int, body: String?): Outcome {
        if (code == 202) return Outcome.Pending
        if (code !in 200..299) return Outcome.Missing
        val root = runCatching { JsonParser.parseString(body ?: "") }.getOrNull() ?: return Outcome.Missing
        if (!root.isJsonObject) return Outcome.Missing
        val o = root.asJsonObject
        if (o.get("pending")?.let { it.isJsonPrimitive && it.asBoolean } == true) return Outcome.Pending
        val version = runCatching { o.get("v").asInt }.getOrDefault(0)
        if (version != VERSION) return Outcome.Missing
        val array = o.get("peaks")
        if (array == null || !array.isJsonArray) return Outcome.Missing
        val rows = array.asJsonArray
        if (rows.size() < 2 || rows.size() > MAX_PEAKS) return Outcome.Missing
        val values = IntArray(rows.size()) { i -> runCatching { rows[i].asInt }.getOrDefault(0).coerceIn(0, 255) }
        val duration = runCatching { o.get("duration").asDouble }.getOrDefault(0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        return Outcome.Ready(Peaks(values, duration))
    }

    /**
     * Zmienia [peaks] na dokładnie [bars] słupków 0–1. Przy zmniejszaniu każdy słupek bierze największą wartość ze swojego
     * przedziału zmieszaną ze średnią (0,7 / 0,3) — fala nie „wygładza się” do płaskiej kreski; przy powiększaniu wartości
     * są interpolowane liniowo.
     */
    @JvmStatic
    fun downsample(peaks: IntArray, bars: Int): FloatArray {
        if (bars <= 0) return FloatArray(0)
        if (peaks.isEmpty()) return FloatArray(bars)
        val n = peaks.size
        val out = FloatArray(bars)
        if (bars >= n) {
            for (i in 0 until bars) {
                val pos = if (bars == 1) 0f else i * (n - 1f) / (bars - 1f)
                val lo = pos.toInt().coerceIn(0, n - 1)
                val hi = min(lo + 1, n - 1)
                val f = pos - lo
                out[i] = (peaks[lo] * (1f - f) + peaks[hi] * f) / 255f
            }
            return out
        }
        for (i in 0 until bars) {
            val from = (i.toLong() * n / bars).toInt()
            val to = max(from + 1, ((i + 1).toLong() * n / bars).toInt()).coerceAtMost(n)
            var peak = 0
            var sum = 0
            for (j in from until to) { peak = max(peak, peaks[j]); sum += peaks[j] }
            val mean = sum.toFloat() / (to - from)
            out[i] = (0.7f * peak + 0.3f * mean) / 255f
        }
        return out
    }

    /**
     * Wysokość słupka (0–1 wysokości widoku) dla poziomu [level] (0–1): pierwiastek podnosi ciche fragmenty, a minimum
     * [floor] sprawia, że nawet cisza ma widoczną kreskę.
     */
    @JvmStatic
    fun barHeight(level: Float, floor: Float = 0.08f): Float = floor + (1f - floor) * sqrt(level.coerceIn(0f, 1f))

    /** Który słupek (0 until [bars]) leży pod pozycją [x] w widoku o szerokości [width]; poza widokiem — najbliższy brzeg. */
    @JvmStatic
    fun barAt(x: Float, width: Float, bars: Int): Int {
        if (bars <= 0 || width <= 0f) return 0
        return (x / width * bars).toInt().coerceIn(0, bars - 1)
    }

    /** Ułamek utworu 0–1 dla pozycji [x] w widoku (poza widokiem przycięty). */
    @JvmStatic
    fun fractionAt(x: Float, width: Float): Float = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

    /** Czas „mm:ss” (albo „h:mm:ss”) do dymka przy przeciąganiu. */
    @JvmStatic
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) java.lang.String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) else java.lang.String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }

    /** Opóźnienie przed ponowieniem po „pending” (ms): 2, 4, 8, 16, 30, 30 s; po [MAX_ATTEMPTS] próbach null (poddajemy się). */
    @JvmStatic
    fun retryDelayMs(attempt: Int): Long? {
        if (attempt < 0 || attempt >= MAX_ATTEMPTS) return null
        return min(30_000L, 2_000L shl attempt)
    }
}
