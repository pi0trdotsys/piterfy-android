package com.eddyizm.tempus.util

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * Piterfy: wspólny ruch interfejsu: wejścia z lekkim opóźnieniem (kaskada), sprężyste naciśnięcie i „pyknięcie”.
 * Wszystkie funkcje szanują systemowe wyłączenie animacji ([ValueAnimator.areAnimatorsEnabled]).
 */
object PiterfyMotion {
    private const val STEP_MS = 45L
    private const val MAX_STEPS = 10

    // areAnimatorsEnabled() istnieje od API 26; na starszych systemach animujemy zawsze
    private fun enabled() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    /** Opóźnienie kaskady dla elementu o danym numerze (ograniczone, żeby długa lista nie czekała w nieskończoność). */
    @JvmStatic fun delayFor(index: Int): Long = min(index.coerceAtLeast(0), MAX_STEPS) * STEP_MS

    /** Element wjeżdża od dołu z zanikaniem; [index] ustawia opóźnienie w kaskadzie. */
    @JvmStatic @JvmOverloads fun enter(view: View, index: Int = 0, offsetDp: Int = 14) {
        if (!enabled()) return
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * offsetDp
        view.animate().alpha(1f).translationY(0f).setStartDelay(delayFor(index)).setDuration(420)
            .setInterpolator(DecelerateInterpolator(2f)).start()
    }

    /** Wejście całego ekranu (fragmentu): krótkie i subtelne, żeby nie spowalniać nawigacji. */
    @JvmStatic fun screen(view: View) {
        if (!enabled()) return
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 10
        view.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /** Sprężyste naciśnięcie: element lekko się zapada pod palcem i wraca z odbiciem. Nie przechwytuje dotyku. */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic @JvmOverloads fun pressable(view: View, depth: Float = 0.96f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (enabled()) v.animate().scaleX(depth).scaleY(depth).setDuration(90)
                    .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(if (enabled()) 220 else 0)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }
            false
        }
    }

    /** „Pyknięcie” potwierdzające akcję (np. start odtwarzania). */
    @JvmStatic fun pop(view: View) {
        if (!enabled()) return
        view.animate().scaleX(1.18f).scaleY(1.18f).setDuration(110).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
        }.start()
    }

    /** Okładka w pełnym odtwarzaczu: pełna wielkość, gdy gra, lekko zmniejszona, gdy pauza (jak w Spotify). */
    @JvmStatic @JvmOverloads fun cover(view: View, playing: Boolean, animate: Boolean = true) {
        val scale = if (playing) 1f else 0.9f
        if (!animate || !enabled()) { view.scaleX = scale; view.scaleY = scale; return }
        view.animate().scaleX(scale).scaleY(scale).setDuration(if (playing) 380 else 260)
            .setInterpolator(if (playing) OvershootInterpolator(1.4f) else DecelerateInterpolator()).start()
    }
}
