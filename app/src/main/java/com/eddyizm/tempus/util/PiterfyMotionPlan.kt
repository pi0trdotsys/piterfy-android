package com.eddyizm.tempus.util

/**
 * Piterfy: czysta (bez Androida, testowana na JVM) część systemu ruchu: tryb ruchu z ustawień oraz jeden zestaw tokenów
 * (czasy, krzywe, stałe sprężyn), z którego korzystają wszystkie animacje Piterfy zamiast własnych liczb.
 *
 * Tryb ruchu (`piterfy_motion`): „system” (domyślnie) słucha systemowego wyłączenia animacji, „full” animuje zawsze,
 * „reduced” nigdy (zostają tylko natychmiastowe zmiany stanu).
 */
object PiterfyMotionPlan {
    const val SYSTEM = "system"
    const val FULL = "full"
    const val REDUCED = "reduced"
    val MODES = listOf(SYSTEM, FULL, REDUCED)

    // ── czasy ──
    /** Naciśnięcie, „pyknięcie”, drobne zmiany stanu. */
    const val FAST_MS = 120L
    /** Zwykłe przejścia: powrót z naciśnięcia, wejście ekranu, pauza okładki. */
    const val NORMAL_MS = 260L
    /** Duże, widoczne ruchy: wejście kaskadowe, morph kontenera, start odtwarzania okładki. */
    const val SLOW_MS = 420L
    /** Kolejny element kaskady wchodzi tyle później od poprzedniego, ale nie więcej niż [MAX_CASCADE_STEPS] kroków. */
    const val CASCADE_STEP_MS = 45L
    const val MAX_CASCADE_STEPS = 10

    // ── krzywe: czwórki (x1, y1, x2, y2) krzywej Béziera, żeby dało się je sprawdzić bez Androida ──
    /** Szybki start i miękkie wyhamowanie (jak sprężyna bez odbicia); używane przez morph kontenera. */
    val EMPHASIZED = floatArrayOf(0.2f, 0.9f, 0.25f, 1f)
    /** Łagodne wejście elementu, który się pojawia. */
    const val DECELERATE_FACTOR = 2f
    /** Lekkie odbicie przy powrocie z naciśnięcia i „pyknięciu”. */
    const val OVERSHOOT_RELEASE = 2f
    const val OVERSHOOT_POP = 3f

    // ── sprężyny (odpowiadają SpringForce.STIFFNESS_MEDIUM i DAMPING_RATIO_MEDIUM_BOUNCY / LOW_BOUNCY) ──
    const val SPRING_STIFFNESS = 1500f
    const val SPRING_DAMPING_BOUNCY = 0.5f
    const val SPRING_DAMPING_SOFT = 0.75f

    /** Zapisana wartość → jeden z trzech trybów (cokolwiek innego to „system”). */
    @JvmStatic
    fun normalize(raw: String?): String = MODES.firstOrNull { it == raw } ?: SYSTEM

    /** Czy animować: [systemAnimatorsOn] mówi, czy system pozwala na animacje (areAnimatorsEnabled; na starych systemach zawsze true). */
    @JvmStatic
    fun enabled(mode: String?, systemAnimatorsOn: Boolean): Boolean = when (normalize(mode)) {
        FULL -> true
        REDUCED -> false
        else -> systemAnimatorsOn
    }

    /** Czas animacji po uwzględnieniu trybu: przy wyłączonym ruchu 0 (zmiana natychmiast). */
    @JvmStatic
    fun duration(base: Long, mode: String?, systemAnimatorsOn: Boolean): Long = if (enabled(mode, systemAnimatorsOn)) base else 0L

    /** Opóźnienie elementu o danym numerze w kaskadzie (długa lista nie czeka w nieskończoność). */
    @JvmStatic
    fun cascadeDelay(index: Int): Long = index.coerceIn(0, MAX_CASCADE_STEPS) * CASCADE_STEP_MS
}
