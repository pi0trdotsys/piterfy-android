package com.eddyizm.tempus.util

import kotlin.math.roundToInt

/**
 * Piterfy: czysta (bez Androida, testowana na JVM) logika dopracowania wersji na telewizor (Google TV / Android TV):
 * przewijanie pilotem z przyspieszeniem, bezpieczne marginesy (overscan), wybór pierwszego fokusu na ekranie,
 * ponawianie szukania fokusu po zmianie ekranu oraz ustawienia lżejsze dla słabych układów TV (szkło, ruch, haptyka, „Cofnij”).
 */
object PiterfyTvPlan {
    // ── przewijanie pilotem (lewo/prawo na pasku postępu) ──

    /** Pojedyncze naciśnięcie przewija o tyle (pasek Media3 domyślnie skacze o 1/20 utworu, czyli ok. 12 s w 4-minutowym). */
    const val TAP_STEP_MS = 5_000L
    private const val MIN_STEP_MS = 1_000L
    /** Przy przytrzymaniu: prędkość przewijania w „sekundach utworu na sekundę trzymania”, rosnąca z czasem trzymania. */
    private const val HOLD_SPEED_1 = 8     // do 0,6 s
    private const val HOLD_SPEED_2 = 16    // do 1,5 s
    private const val HOLD_SPEED_3 = 32    // do 3 s
    private const val HOLD_SPEED_4 = 64    // dłużej
    /** Najwięcej, o ile jedno zdarzenie klawisza może przewinąć: 1/20 utworu (jak domyślnie), ale nie mniej niż [TAP_STEP_MS]. */
    private const val MAX_FRACTION = 20
    /** Powtórzenia klawisza przychodzą co kilkadziesiąt ms; gdy zegar zawiedzie (0 lub zbyt dużo), liczymy ten odstęp. */
    private const val DEFAULT_REPEAT_MS = 50L
    private const val MAX_REPEAT_MS = 250L

    /** Prędkość (x czasu rzeczywistego) dla danego czasu trzymania klawisza. */
    @JvmStatic
    fun holdSpeed(heldMs: Long): Int = when {
        heldMs < 600 -> HOLD_SPEED_1
        heldMs < 1_500 -> HOLD_SPEED_2
        heldMs < 3_000 -> HOLD_SPEED_3
        else -> HOLD_SPEED_4
    }

    /**
     * Krok przewijania (ms utworu, zawsze dodatni) dla jednego zdarzenia klawisza.
     * @param repeatCount KeyEvent.getRepeatCount(): 0 = pierwsze naciśnięcie
     * @param heldMs jak długo klawisz jest trzymany
     * @param sinceLastMs czas od poprzedniego zdarzenia tego klawisza (0 = nieznany)
     * @param durationMs długość utworu (<= 0, gdy nieznana, np. radio)
     */
    @JvmStatic
    fun scrubStepMs(repeatCount: Int, heldMs: Long, sinceLastMs: Long, durationMs: Long): Long {
        val cap = if (durationMs > 0) maxOf(TAP_STEP_MS, durationMs / MAX_FRACTION) else Long.MAX_VALUE
        if (repeatCount <= 0) return minOf(TAP_STEP_MS, cap)
        val dt = if (sinceLastMs in 1..MAX_REPEAT_MS) sinceLastMs else DEFAULT_REPEAT_MS
        val step = holdSpeed(heldMs) * dt          // prędkość (x) × czas = ms utworu
        return step.coerceIn(MIN_STEP_MS, cap)
    }

    // ── bezpieczne marginesy (overscan) ──

    const val SAFE_FRACTION = 0.05f

    /** Margines z lewej i prawej w dp: 5% szerokości ekranu (960 dp → 48 dp), w rozsądnych granicach. */
    @JvmStatic
    fun safeHorizontalDp(screenWidthDp: Float): Int = (screenWidthDp * SAFE_FRACTION).roundToInt().coerceIn(24, 64)

    /** Margines u góry w dp: 5% wysokości ekranu (540 dp → 27 dp), w rozsądnych granicach. */
    @JvmStatic
    fun safeVerticalDp(screenHeightDp: Float): Int = (screenHeightDp * SAFE_FRACTION).roundToInt().coerceIn(12, 40)

    // ── fokus ──

    /** Element, na który można ustawić fokus: położenie na ekranie i to, czy w ogóle się nadaje (widoczny, włączony, nie „chrome”). */
    data class FocusCandidate(val top: Int, val left: Int, val usable: Boolean)

    /** Numer elementu, który dostaje fokus jako pierwszy: użyteczny, najwyżej, a przy równej wysokości najbardziej z lewej. */
    @JvmStatic
    fun pickDefaultFocus(candidates: List<FocusCandidate>): Int? =
        candidates.withIndex().filter { it.value.usable }
            .minWithOrNull(compareBy({ it.value.top }, { it.value.left }, { it.index }))?.index

    private const val MAX_FOCUS_ATTEMPTS = 8

    /** Po zmianie ekranu lista ładuje się z opóźnieniem: kolejne próby co coraz dłużej (120, 200, 280 ms…); null = koniec prób. */
    @JvmStatic
    fun focusRetryDelayMs(attempt: Int): Long? = if (attempt in 0 until MAX_FOCUS_ATTEMPTS) 120L + 80L * attempt else null

    // ── lżejszy interfejs na słabych układach TV ──

    /** Siła Liquid Glass na TV nie przekracza tej wartości (rozmycie na słabym GPU kosztuje klatki). */
    const val TV_GLASS_CAP = 40

    @JvmStatic
    fun glassIntensity(stored: Int, tv: Boolean): Int = if (tv) minOf(stored, TV_GLASS_CAP) else stored

    /** Animacje na TV są krótsze (ok. 70%), bo pilot „przeskakuje” szybciej niż dotyk, a SoC ma mniej klatek w zapasie. */
    private const val TV_MOTION_PERCENT = 70

    @JvmStatic
    fun motionDuration(base: Long, tv: Boolean): Long = if (tv) (base * TV_MOTION_PERCENT / 100).coerceAtLeast(if (base > 0) 1 else 0) else base

    /** Telewizor nie ma silnika haptycznego: pomijamy cały kod haptyki. */
    @JvmStatic
    fun hapticsAllowed(preference: Boolean, tv: Boolean): Boolean = preference && !tv

    /** Jak długo „Cofnij” czeka na pilota: dłużej niż na telefonie, bo trzeba dojść do przycisku strzałkami. */
    private const val TV_UNDO_MS = 9_000

    /** @param defaultMs czas z Snackbar.LENGTH_* przy dotyku (długość stałej Material to ok. 2750 ms, więc podajemy własny) */
    @JvmStatic
    fun undoDurationMs(defaultMs: Int, tv: Boolean): Int = if (tv) maxOf(defaultMs, TV_UNDO_MS) else defaultMs
}
