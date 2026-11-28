package com.eddyizm.tempus.util

import android.annotation.SuppressLint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.eddyizm.tempus.App

/**
 * Piterfy: wspólna haptyka odtwarzacza. Cztery „słowa” zamiast surowych stałych systemu: [tick] (lekkie kliknięcie przy
 * przeciąganiu i przyciskach), [confirm] (akcja się udała: polubienie, dodanie do kolejki), [reject] (coś nie wyszło albo
 * koniec zakresu) i [heavy] (długie przytrzymanie, rozpoczęcie przeciągania). Nowsze stałe (Android 11 i 14) są używane
 * tylko tam, gdzie istnieją; na starszych systemach działa najbliższy odpowiednik, a na API 24 — zwykłe stuknięcie.
 *
 * Wyłącznik: ustawienie `piterfy_haptics` (domyślnie włączone; przełącznik jest na stronie „Wygląd”). Respektujemy też
 * systemowe „wibracje przy dotknięciu” — nie używamy FLAG_IGNORE_GLOBAL_SETTING.
 */
object PiterfyHaptics {
    const val KEY = "piterfy_haptics"

    /** Rodzaje wrażeń; mapowanie na stałe systemu jest w [constantFor] (czyste, testowane na JVM). */
    enum class Kind { TICK, CLICK, CONFIRM, REJECT, HEAVY, START, END }

    /** Minimalny odstęp między dwoma impulsami tego samego źródła (np. przeciąganie paska), żeby nie bzyczało. */
    const val MIN_GAP_MS = 28L

    // Stałe wpisane liczbowo (nowsze istnieją tylko w nowszych systemach, a lint ostrzega o InlinedApi); zgodność z
    // android.view.HapticFeedbackConstants pilnuje PiterfyHapticsTest.
    private const val LONG_PRESS = 0
    private const val VIRTUAL_KEY = 1
    private const val KEYBOARD_TAP = 3
    private const val CLOCK_TICK = 4
    private const val CONTEXT_CLICK = 6          // API 23
    private const val GESTURE_START = 12         // API 30
    private const val GESTURE_END = 13           // API 30
    private const val CONFIRM = 16               // API 30
    private const val REJECT = 17                // API 30
    private const val SEGMENT_FREQUENT_TICK = 27 // API 34

    /** Stała systemu dla wrażenia [kind] na urządzeniu z API [sdk]. */
    @JvmStatic
    fun constantFor(kind: Kind, sdk: Int): Int = when (kind) {
        Kind.TICK -> if (sdk >= 34) SEGMENT_FREQUENT_TICK else CLOCK_TICK
        Kind.CLICK -> if (sdk >= 23) CONTEXT_CLICK else KEYBOARD_TAP
        Kind.CONFIRM -> if (sdk >= 30) CONFIRM else VIRTUAL_KEY
        Kind.REJECT -> if (sdk >= 30) REJECT else LONG_PRESS
        Kind.HEAVY -> LONG_PRESS
        Kind.START -> if (sdk >= 30) GESTURE_START else VIRTUAL_KEY
        Kind.END -> if (sdk >= 30) GESTURE_END else KEYBOARD_TAP
    }

    /** Czy minął wystarczający odstęp od poprzedniego impulsu (pierwszy zawsze przechodzi). */
    @JvmStatic
    fun gapElapsed(lastMs: Long, nowMs: Long, minGapMs: Long = MIN_GAP_MS): Boolean = lastMs < 0 || nowMs - lastMs >= minGapMs

    /** Numer „zatrzasku” pozycji paska (0..steps) — impuls przy każdej zmianie, jak w skokowym suwaku. */
    @JvmStatic
    fun detent(fraction: Float, steps: Int): Int = (fraction.coerceIn(0f, 1f) * steps.coerceAtLeast(1) + 0.5f).toInt()

    @JvmStatic
    fun enabled(): Boolean = runCatching { PiterfyTvPlan.hapticsAllowed(App.getInstance().preferences.getBoolean(KEY, true), PiterfyTv.isTelevision()) }.getOrDefault(true)

    @JvmStatic fun perform(view: View?, kind: Kind) {
        if (view == null || !enabled()) return
        view.performHapticFeedback(constantFor(kind, Build.VERSION.SDK_INT))
    }

    @JvmStatic fun tick(view: View?) = perform(view, Kind.TICK)
    @JvmStatic fun click(view: View?) = perform(view, Kind.CLICK)
    @JvmStatic fun confirm(view: View?) = perform(view, Kind.CONFIRM)
    @JvmStatic fun reject(view: View?) = perform(view, Kind.REJECT)
    @JvmStatic fun heavy(view: View?) = perform(view, Kind.HEAVY)
    @JvmStatic fun gestureStart(view: View?) = perform(view, Kind.START)
    @JvmStatic fun gestureEnd(view: View?) = perform(view, Kind.END)

    /**
     * Impuls przy samym dotknięciu [view] (ACTION_DOWN), niezależnie od tego, co przycisk potem zrobi — jak fizyczny klawisz.
     * Nie przechwytuje dotyku. Zastępuje wcześniejszy OnTouchListener widoku, więc wołać tylko na widokach, które żadnego
     * nie mają (przy Liquid Glass dotyk obsługuje [com.eddyizm.tempus.ui.components.PiterfyGlass.springPress] z parametrem haptic).
     */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic @JvmOverloads
    fun attachPress(view: View?, kind: Kind = Kind.CLICK) {
        view?.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) perform(v, kind)
            false
        }
    }

    /** Impulsy jednego źródła (np. paska): [tick] odpala tylko po minimalnym odstępie. */
    class Throttle(private val minGapMs: Long = MIN_GAP_MS) {
        private var last = -1L
        fun tick(view: View?, nowMs: Long = android.os.SystemClock.uptimeMillis()) {
            if (!gapElapsed(last, nowMs, minGapMs)) return
            last = nowMs
            PiterfyHaptics.tick(view)
        }
    }
}
