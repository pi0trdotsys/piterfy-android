package com.eddyizm.tempus.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Piterfy: czysta logika tekstów w stylu Apple Music (bez Androida, testy: LyricsKaraokeTest). Te same liczby i wzory ma
 * connect/web/lyrics-core.js (`progress`, `style`), więc strona i aplikacja wyglądają tak samo.
 */
object PiterfyLyricsKaraoke {
    const val LINE_MAX_MS = 9000
    const val LAST_LINE_MS = 4000
    const val INACTIVE_SCALE = 0.86f
    /** Od jakiej odległości od aktywnej linii rozmywamy (rozmycie sprzętowe tylko API 31+, niżej samo przygaszenie). */
    const val BLUR_MIN_DISTANCE = 2

    private val ALPHA = floatArrayOf(1f, 0.56f, 0.42f, 0.36f, 0.3f, 0.26f)
    private val BLUR = floatArrayOf(0f, 0.5f, 1.4f, 2.4f, 3.2f, 4f)

    data class LineStyle(val scale: Float, val alpha: Float, val blurDp: Float)

    /**
     * Postęp „karaoke” (0–1) linii zaczynającej się w `start`: od jej początku do początku następnej (`nextStart`, null dla
     * ostatniej linii, która trwa LAST_LINE_MS). Długie przerwy (np. solówka) skracamy do LINE_MAX_MS, żeby wypełnienie nie pełzło.
     * Dodatni `offset` pokazuje linię wcześniej (jak w LyricsTiming.active).
     */
    @JvmStatic
    fun progress(start: Int, nextStart: Int?, position: Long, offset: Int): Float {
        val span = if (nextStart == null) LAST_LINE_MS else min(nextStart - start, LINE_MAX_MS)
        if (span <= 0) return 1f
        return ((position + offset - start).toFloat() / span).coerceIn(0f, 1f)
    }

    /** Wygląd linii wg odległości od aktywnej (0 = aktywna): skala, przezroczystość i rozmycie w dp; ujemne odległości jak dodatnie. */
    @JvmStatic
    fun style(distance: Int): LineStyle {
        val d = min(abs(distance), ALPHA.size - 1)
        return LineStyle(if (d == 0) 1f else INACTIVE_SCALE, ALPHA[d], BLUR[d])
    }

    /** Rozmycie w dp, które faktycznie stosujemy: dopiero od BLUR_MIN_DISTANCE (bliżej wystarcza przygaszenie). */
    @JvmStatic
    fun blurDp(distance: Int): Float = if (abs(distance) < BLUR_MIN_DISTANCE) 0f else style(distance).blurDp

    /**
     * Gdy tekst linii zawija się na kilka wierszy, wypełnienie idzie po kolei: zwraca stopień wypełnienia (0–1) każdego wiersza
     * dla postępu `progress` całej linii, proporcjonalnie do szerokości wierszy.
     */
    @JvmStatic
    fun fillPerLine(widths: FloatArray, progress: Float): FloatArray {
        val total = widths.sum()
        if (total <= 0f) return FloatArray(widths.size) { if (progress >= 1f) 1f else 0f }
        val reached = progress.coerceIn(0f, 1f) * total
        var before = 0f
        return FloatArray(widths.size) { i ->
            val w = max(widths[i], 0.0001f)
            val fill = ((reached - before) / w).coerceIn(0f, 1f)
            before += widths[i]
            fill
        }
    }
}
