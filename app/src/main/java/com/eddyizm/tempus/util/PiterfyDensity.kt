package com.eddyizm.tempus.util

import android.view.View
import android.view.ViewGroup
import com.eddyizm.tempus.appearance.PiterfyAppearance

/**
 * Piterfy: gęstość list z ustawień Wyglądu. W trybie „gęsto” wiersze utworów są niższe (okładka 44 zamiast 52 dp i bez
 * pionowych odstępów); w trybie „wygodnie” nic się nie zmienia. Wywoływane raz przy tworzeniu wiersza.
 */
object PiterfyDensity {
    private const val ROW_FULL_DP = 52
    private const val ROW_COMPACT_DP = 44

    @JvmStatic fun compact(): Boolean = PiterfyDisplayPlan.normalizeDensity(safeDensity()) == PiterfyDisplayPlan.COMPACT

    private fun safeDensity(): String? = try { PiterfyAppearance.density() } catch (e: RuntimeException) { null }

    /**
     * Zmniejsza wiersz utworu: [sized] to widoki o stałej wysokości okładki (okładka, nakładka, numer, separator),
     * [playIcon] to ikona odtwarzania wyśrodkowana na okładce (jej margines zależy od rozmiaru okładki).
     */
    @JvmStatic
    fun songRow(root: View, sized: List<View>, playIcon: View?) {
        if (!compact()) return
        val d = root.resources.displayMetrics.density
        val height = Math.round(ROW_COMPACT_DP * d)
        root.setPadding(root.paddingLeft, 0, root.paddingRight, 0)
        sized.forEach { v ->
            val lp = v.layoutParams ?: return@forEach
            lp.height = height
            if (lp.width == Math.round(ROW_FULL_DP * d)) lp.width = height
            v.layoutParams = lp
        }
        (playIcon?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = Math.round((16 + (ROW_COMPACT_DP - 28) / 2) * d)
            playIcon.layoutParams = it
        }
    }
}
