package com.eddyizm.tempus.util

/**
 * Piterfy: czysta (bez Androida, testowana na JVM) logika ustawień „Wyglądu”: rozmiar tekstu, gęstość list i to, co pokazuje
 * podgląd na żywo. Zapisane wartości mogą być śmieciami (starsza wersja, ręczna edycja), więc wszystko przechodzi przez normalizację.
 */
object PiterfyDisplayPlan {
    // ── rozmiar tekstu (procent systemowej skali czcionki) ──
    const val TEXT_SCALE_MIN = 85
    const val TEXT_SCALE_MAX = 130
    const val TEXT_SCALE_DEFAULT = 100
    const val TEXT_SCALE_STEP = 5

    /** Do przedziału 85–130 i do wielokrotności 5, żeby suwak i zapis się zgadzały. */
    @JvmStatic
    fun clampTextScale(percent: Int): Int {
        val bounded = percent.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
        return Math.round(bounded / TEXT_SCALE_STEP.toDouble()).toInt() * TEXT_SCALE_STEP
    }

    /**
     * Skala czcionki do nałożenia na aplikację: systemowa skala × wybrany procent. Przy 100% zwraca dokładnie skalę systemu
     * (nie ruszamy konfiguracji). Wynik ograniczony do rozsądnego zakresu 0,7–2,0, żeby układ się nie rozpadł.
     */
    @JvmStatic
    fun effectiveFontScale(systemScale: Float, percent: Int): Float {
        val p = clampTextScale(percent)
        if (p == TEXT_SCALE_DEFAULT) return systemScale
        return (systemScale * p / 100f).coerceIn(0.7f, 2.0f)
    }

    // ── gęstość list ──
    const val COMFORTABLE = "comfortable"
    const val COMPACT = "compact"
    val DENSITIES = listOf(COMFORTABLE, COMPACT)

    @JvmStatic
    fun normalizeDensity(raw: String?): String = DENSITIES.firstOrNull { it == raw } ?: COMFORTABLE

    /** Mnożnik odstępów pionowych w wierszach list: wygodnie 1,0, gęsto 0,75. */
    @JvmStatic
    fun densityFactor(raw: String?): Float = if (normalizeDensity(raw) == COMPACT) 0.75f else 1f

    /** Odstęp (px) po uwzględnieniu gęstości, nigdy ujemny. */
    @JvmStatic
    fun scaledSpace(basePx: Int, raw: String?): Int = Math.round(basePx.coerceAtLeast(0) * densityFactor(raw))

    // ── podgląd na żywo ──
    /** Wszystko, co potrzeba do narysowania makiety odtwarzacza, wyliczone z bieżących ustawień. */
    data class Preview(
        val glass: Boolean,
        val glassAlpha: Float,     // przezroczystość karty makiety: im silniejsze szkło, tym bardziej przezroczysta
        val coverTint: Boolean,    // tło z koloru okładki
        val trueBlack: Boolean,
        val textScale: Float,      // 0,85–1,3: mnożnik tekstu makiety
        val density: Float,
        val animated: Boolean,
    )

    @JvmStatic
    fun preview(
        glass: Boolean,
        intensity: Int,
        coverColors: Boolean,
        trueBlack: Boolean,
        textScalePercent: Int,
        density: String?,
        motionMode: String?,
        systemAnimatorsOn: Boolean,
    ): Preview {
        val level = PiterfyGlassPlan.clampIntensity(intensity)
        return Preview(
            glass = glass,
            glassAlpha = if (glass) 0.88f - 0.5f * level / 100f else 1f,
            coverTint = coverColors,
            trueBlack = trueBlack,
            textScale = clampTextScale(textScalePercent) / 100f,
            density = densityFactor(density),
            animated = PiterfyMotionPlan.enabled(motionMode, systemAnimatorsOn),
        )
    }
}
