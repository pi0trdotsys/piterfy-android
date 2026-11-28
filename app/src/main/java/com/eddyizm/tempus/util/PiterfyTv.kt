package com.eddyizm.tempus.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.eddyizm.tempus.App

/**
 * Piterfy: czy aplikacja działa na telewizorze (Google TV / Android TV). Wynik jest stały przez życie procesu, więc liczymy
 * go raz. Przed inicjalizacją aplikacji (np. w testach narzędziowych) zwraca false.
 */
object PiterfyTv {
    @Volatile private var cached: Boolean? = null

    @JvmStatic
    fun isTelevision(): Boolean {
        cached?.let { return it }
        val tv = runCatching { isTelevision(App.getContext()) }.getOrDefault(false)
        cached = tv
        return tv
    }

    @JvmStatic
    fun isTelevision(context: Context): Boolean {
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}
