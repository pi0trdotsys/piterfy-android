package com.eddyizm.tempus.util

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import com.eddyizm.tempus.appearance.PiterfyAppearance

/**
 * Piterfy: wspólny ruch interfejsu: wejścia z lekkim opóźnieniem (kaskada), sprężyste naciśnięcie i „pyknięcie”.
 * Czasy, krzywe i sprężyny pochodzą z jednego zestawu tokenów ([PiterfyMotionPlan]); wszystkie funkcje szanują tryb ruchu
 * z ustawień Wyglądu („system” słucha systemowego wyłączenia animacji, „full” animuje zawsze, „reduced” nigdy).
 */
object PiterfyMotion {
    // tokeny w jednym miejscu (PiterfyMotionPlan); tu tylko wygodne aliasy dla kodu Androida
    const val FAST = PiterfyMotionPlan.FAST_MS
    const val NORMAL = PiterfyMotionPlan.NORMAL_MS
    const val SLOW = PiterfyMotionPlan.SLOW_MS

    /** Krzywa „szybki start, miękkie wyhamowanie” (PathInterpolator od API 21; minSdk to 24). */
    @JvmStatic fun emphasized(): Interpolator = PiterfyMotionPlan.EMPHASIZED.let { PathInterpolator(it[0], it[1], it[2], it[3]) }

    // areAnimatorsEnabled() istnieje od API 26; na starszych systemach animujemy zawsze
    private fun systemAnimators() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    @JvmStatic fun enabled(): Boolean = PiterfyMotionPlan.enabled(modeOrSystem(), systemAnimators())

    /** Na telewizorze animacje są krótsze (słabszy układ graficzny, pilot przeskakuje szybciej niż dotyk). */
    private fun tv(ms: Long): Long = PiterfyTvPlan.motionDuration(ms, PiterfyTv.isTelevision())

    // przed inicjalizacją aplikacji (np. w testach narzędziowych) nie ma jeszcze ustawień: wtedy działa zachowanie systemowe
    private fun modeOrSystem(): String = try { PiterfyAppearance.motionMode() } catch (e: RuntimeException) { PiterfyMotionPlan.SYSTEM }

    /** Opóźnienie kaskady dla elementu o danym numerze (ograniczone, żeby długa lista nie czekała w nieskończoność). */
    @JvmStatic fun delayFor(index: Int): Long = PiterfyMotionPlan.cascadeDelay(index)

    /** Element wjeżdża od dołu z zanikaniem; [index] ustawia opóźnienie w kaskadzie. */
    @JvmStatic @JvmOverloads fun enter(view: View, index: Int = 0, offsetDp: Int = 14) {
        if (!enabled()) return
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * offsetDp
        view.animate().alpha(1f).translationY(0f).setStartDelay(delayFor(index)).setDuration(tv(SLOW))
            .setInterpolator(DecelerateInterpolator(PiterfyMotionPlan.DECELERATE_FACTOR)).start()
    }

    /** Wejście całego ekranu (fragmentu): krótkie i subtelne, żeby nie spowalniać nawigacji. */
    @JvmStatic fun screen(view: View) {
        if (!enabled()) return
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 10
        view.animate().alpha(1f).translationY(0f).setDuration(tv(NORMAL)).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /** Sprężyste naciśnięcie: element lekko się zapada pod palcem i wraca z odbiciem. Nie przechwytuje dotyku. */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic @JvmOverloads fun pressable(view: View, depth: Float = 0.96f) {
        // z włączonym Liquid Glass naciśnięcie jest prawdziwą sprężyną (SpringAnimation), nie krótką animacją
        if (com.eddyizm.tempus.ui.components.PiterfyGlass.springEnabled()) {
            com.eddyizm.tempus.ui.components.PiterfyGlass.springPress(view, depth)
            return
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (enabled()) v.animate().scaleX(depth).scaleY(depth).setDuration(tv(FAST))
                    .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(if (enabled()) tv(NORMAL) else 0)
                    .setInterpolator(OvershootInterpolator(PiterfyMotionPlan.OVERSHOOT_RELEASE)).start()
            }
            false
        }
    }

    /** „Pyknięcie” potwierdzające akcję (np. start odtwarzania). */
    @JvmStatic fun pop(view: View) {
        if (!enabled()) return
        view.animate().scaleX(1.18f).scaleY(1.18f).setDuration(tv(FAST)).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(tv(NORMAL)).setInterpolator(OvershootInterpolator(PiterfyMotionPlan.OVERSHOOT_POP)).start()
        }.start()
    }

    /** Okładka w pełnym odtwarzaczu: pełna wielkość, gdy gra, lekko zmniejszona, gdy pauza (jak w Spotify). */
    @JvmStatic @JvmOverloads fun cover(view: View, playing: Boolean, animate: Boolean = true) {
        val scale = if (playing) 1f else 0.9f
        if (!animate || !enabled()) { view.scaleX = scale; view.scaleY = scale; return }
        view.animate().scaleX(scale).scaleY(scale).setDuration(tv(if (playing) SLOW else NORMAL))
            .setInterpolator(if (playing) OvershootInterpolator(1.4f) else DecelerateInterpolator()).start()
    }
}
