package com.eddyizm.tempus.util

/**
 * Piterfy: „Liquid Glass” — decyzja, jak mocno i jakim sposobem rysować szkło. Czysta funkcja (bez Androida), żeby dało
 * się ją testować: zależy od wersji systemu, ustawienia, siły (0–100), oszczędzania energii i systemowych animacji.
 *
 * Tryby: OFF — zwykły wygląd; TINT — półprzezroczyste powierzchnie z obwódką i połyskiem, bez rozmycia (Android poniżej 12 albo
 * oszczędzanie energii); BLUR — to samo plus prawdziwe rozmycie tła (Android 12+). Siła skaluje wartości monotonicznie:
 * im mocniej, tym przezroczystsze wypełnienie, większe rozmycie, wyraźniejsza obwódka i połysk.
 */
object PiterfyGlassPlan {
    enum class Mode { OFF, TINT, BLUR }

    /** [fillAlpha] 0–1 (krycie wypełnienia), [blurDp] promień rozmycia, [edgeAlpha] i [sheenAlpha] 0–1, [animate] czy ruszać. */
    data class Plan(
        val mode: Mode,
        val fillAlpha: Float,
        val blurDp: Float,
        val edgeAlpha: Float,
        val sheenAlpha: Float,
        val animate: Boolean,
    ) {
        val enabled get() = mode != Mode.OFF
        val blur get() = mode == Mode.BLUR
    }

    const val DEFAULT_INTENSITY = 60
    private const val BLUR_MIN_SDK = 31

    private val OFF = Plan(Mode.OFF, 1f, 0f, 0f, 0f, false)

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

    @JvmStatic
    fun clampIntensity(value: Int): Int = value.coerceIn(0, 100)

    @JvmStatic
    fun plan(sdkInt: Int, enabled: Boolean, intensity: Int, powerSave: Boolean, animatorsOn: Boolean): Plan {
        if (!enabled) return OFF
        val t = clampIntensity(intensity) / 100f
        val blur = sdkInt >= BLUR_MIN_SDK && !powerSave
        val animate = animatorsOn && !powerSave
        // bez rozmycia tło „prześwituje” bez ochrony, więc wypełnienie zostaje krótsze na przezroczystość
        val fill = if (blur) lerp(0.80f, 0.40f, t) else lerp(0.90f, 0.62f, t)
        return Plan(
            mode = if (blur) Mode.BLUR else Mode.TINT,
            fillAlpha = fill,
            blurDp = if (blur) lerp(6f, 28f, t) else 0f,
            edgeAlpha = lerp(0.16f, 0.55f, t),
            sheenAlpha = lerp(0.05f, 0.22f, t),
            animate = animate,
        )
    }
}
