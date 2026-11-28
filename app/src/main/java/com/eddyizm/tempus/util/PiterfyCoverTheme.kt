package com.eddyizm.tempus.util

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Piterfy: czysta logika kolorów „z okładki” (bez Androida, żeby dało się ją testować na JVM). Kolor okładki trafia
 * do nagłówków stron albumu, wykonawcy i playlisty jako gradient; ta klasa pilnuje, żeby biały tekst na nim był czytelny
 * (kontrast WCAG ≥ 4,5), a prawie czarne okładki nadal dawały widoczny odcień.
 */
object PiterfyCoverTheme {
    const val MIN_CONTRAST = 4.5
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val MIN_VALUE = 0.18      // najciemniejszy odcień nagłówka (w skali HSV), żeby tint był widoczny
    private const val MIN_SATURATION_FOR_LIFT = 0.05  // szarości i czerń zostają bez zmian

    /** Względna luminancja (WCAG) koloru ARGB, 0 = czarny, 1 = biały. */
    @JvmStatic
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** Współczynnik kontrastu WCAG między dwoma kolorami (1–21). */
    @JvmStatic
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * Kolor nagłówka, na którym biały tekst ma kontrast co najmniej [MIN_CONTRAST]: zbyt jasny jest przyciemniany
     * (skalowanie jasności HSV), a bardzo ciemny, ale barwny — lekko rozjaśniany do [MIN_VALUE], żeby gradient
     * nie zlał się z tłem. Szarości i czerń zostają bez zmian. Zawsze zwraca kolor w pełni nieprzezroczysty.
     */
    @JvmStatic
    fun readableHeader(color: Int): Int {
        val opaque = color or (0xFF shl 24)
        val hsv = toHsv(opaque)
        var value = hsv[2]
        if (hsv[1] >= MIN_SATURATION_FOR_LIFT && value < MIN_VALUE) value = MIN_VALUE
        var result = fromHsv(hsv[0], hsv[1], value)
        var guard = 0
        while (contrast(WHITE, result) < MIN_CONTRAST && guard++ < 40) {
            value *= 0.92
            result = fromHsv(hsv[0], hsv[1], value)
        }
        return result
    }

    /** Czas przejścia akcentu między utworami (ms): wystarczająco długi, żeby zmiana była płynna, i za krótki, by się nie wlokła. */
    const val FADE_MS = 600L

    /** Łagodne wejście i wyjście (smoothstep) postępu przejścia 0–1. */
    @JvmStatic
    fun ease(t: Float): Float {
        val p = t.coerceIn(0f, 1f)
        return p * p * (3f - 2f * p)
    }

    /**
     * Kolor w trakcie przejścia z [from] do [to] po [t] (0–1) z łagodzeniem. Dla dwóch kolorów czytelnych z białym tekstem
     * każdy punkt pośredni też jest czytelny (luminancja mieszanki nie przekracza większej z dwóch), więc zmiana akcentu
     * nigdy nie przechodzi przez jasny „błysk” pod białym tekstem.
     */
    @JvmStatic
    fun fade(from: Int, to: Int, t: Float): Int = mix(from, to, ease(t))

    /**
     * Akcent szkła (przycisk odtwarzania, szklane powierzchnie) z koloru okładki: rozjaśniony, żeby na ciemnej karcie był
     * wyraźny, ale tylko do momentu, w którym biała ikona na nim nadal ma kontrast [MIN_CONTRAST]; jaśniejszy akcent jest
     * przyciemniany jak nagłówek. Zawsze w pełni nieprzezroczysty.
     */
    @JvmStatic
    fun readableAccent(ambient: Int, lighten: Float = 0.32f): Int {
        val lifted = mix(ambient or (0xFF shl 24), WHITE, lighten)
        return readableHeader(lifted)
    }

    /**
     * Kolor widoczny na ciemnym tle [background] (np. odtworzona część fali na karcie): [color] jest rozjaśniany ku bieli
     * tak długo, aż kontrast z tłem osiągnie [minContrast] (domyślnie 3, jak dla elementów graficznych w WCAG).
     */
    @JvmStatic
    fun visibleOnDark(color: Int, background: Int, minContrast: Double = 3.0): Int {
        val base = color or (0xFF shl 24)
        val bg = background or (0xFF shl 24)
        var result = base
        var step = 0
        while (contrast(result, bg) < minContrast && step++ < 20) result = mix(base, WHITE, step * 0.05f)
        return result
    }

    /** Mieszanie dwóch kolorów ARGB: 0 = [a], 1 = [b] (kanały liczone osobno, alfa też). */
    @JvmStatic
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun lerp(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * k + 0.5f).toInt().coerceIn(0, 255)
        }
        return (lerp(24) shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
    }

    @JvmStatic
    fun withAlpha(color: Int, alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (color and 0x00FFFFFF)

    /**
     * Trzy kolory gradientu nagłówka od góry do dołu. Zwykle: czytelny kolor okładki → jego mieszanka z powierzchnią →
     * powierzchnia. Przy [translucent] (Liquid Glass) gradient jest półprzezroczysty i kończy się przezroczystością,
     * żeby pod spodem było widać poświaty szkła.
     */
    @JvmStatic
    fun headerGradient(color: Int, surface: Int, translucent: Boolean): IntArray {
        val top = readableHeader(color)
        return if (translucent) {
            intArrayOf(withAlpha(top, 0.62f), withAlpha(top, 0.22f), 0x00000000)
        } else {
            intArrayOf(top, mix(top, surface or (0xFF shl 24), 0.55f), surface or (0xFF shl 24))
        }
    }

    // ── HSV bez android.graphics.Color (nie działa w testach JVM) ──

    /** Kolor ARGB → [odcień 0–360, nasycenie 0–1, jasność 0–1]. */
    internal fun toHsv(color: Int): DoubleArray {
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val hue = when {
            delta == 0.0 -> 0.0
            maxC == r -> 60.0 * (((g - b) / delta) % 6.0)
            maxC == g -> 60.0 * ((b - r) / delta + 2.0)
            else -> 60.0 * ((r - g) / delta + 4.0)
        }
        return doubleArrayOf(if (hue < 0) hue + 360.0 else hue, if (maxC == 0.0) 0.0 else delta / maxC, maxC)
    }

    /** Odcień 0–360, nasycenie 0–1, jasność 0–1 → kolor ARGB w pełni nieprzezroczysty. */
    @JvmStatic
    fun fromHsv(hue: Double, saturation: Double, value: Double): Int {
        val h = ((hue % 360.0) + 360.0) % 360.0
        val s = saturation.coerceIn(0.0, 1.0)
        val v = value.coerceIn(0.0, 1.0)
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun channel(f: Double) = ((f + m) * 255.0 + 0.5).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
