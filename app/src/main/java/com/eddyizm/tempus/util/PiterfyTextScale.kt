package com.eddyizm.tempus.util

import android.content.Context
import android.content.res.Configuration
import com.eddyizm.tempus.appearance.PiterfyAppearance

/**
 * Piterfy: rozmiar tekstu z ustawień Wyglądu (85–130% systemowej skali czcionki). Nakładany na kontekst aktywności w
 * `attachBaseContext`, więc działa na cały interfejs bez zmian w układach (sp przeskalują się same). Przy 100% kontekst
 * zostaje nietknięty.
 */
object PiterfyTextScale {
    @JvmStatic
    fun wrap(base: Context): Context {
        val percent = try { PiterfyAppearance.textScalePercent() } catch (e: RuntimeException) { return base }
        if (percent == PiterfyDisplayPlan.TEXT_SCALE_DEFAULT) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = PiterfyDisplayPlan.effectiveFontScale(config.fontScale, percent)
        return base.createConfigurationContext(config)
    }
}
