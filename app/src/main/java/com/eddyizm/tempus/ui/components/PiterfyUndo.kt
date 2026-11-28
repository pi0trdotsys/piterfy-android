package com.eddyizm.tempus.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.PiterfyHaptics
import com.google.android.material.snackbar.Snackbar

/**
 * Piterfy: jednolite „Cofnij” po akcjach, których nie da się odwrócić jednym stuknięciem (usunięcie z kolejki, wyczyszczenie
 * kolejki, ocena 1★, dodanie do kolejki). Snackbar siedzi nad mini odtwarzaczem lub dolną nawigacją (jak pozostałe w aplikacji),
 * przy pokazaniu daje lekki impuls, a przy stuknięciu „Cofnij” — potwierdzenie.
 */
@OptIn(markerClass = [UnstableApi::class])
object PiterfyUndo {
    /** Pokazuje [message] z akcją „Cofnij”, która woła [onUndo]; [host] to dowolny widok z okna aplikacji. */
    @JvmStatic
    fun show(host: View, message: CharSequence, onUndo: Runnable) {
        val snackbar = Snackbar.make(host, message, Snackbar.LENGTH_LONG)
        activityOf(host.context)?.getSnackbarAnchor()?.let { snackbar.setAnchorView(it) }
        snackbar.setAction(R.string.song_bottom_sheet_undo) {
            PiterfyHaptics.confirm(it)
            onUndo.run()
        }
        PiterfyHaptics.tick(host)
        snackbar.show()
    }

    /** Krótka informacja bez akcji (np. „Zagram następny: …”). */
    @JvmStatic
    fun info(host: View, message: CharSequence) {
        val snackbar = Snackbar.make(host, message, Snackbar.LENGTH_SHORT)
        activityOf(host.context)?.getSnackbarAnchor()?.let { snackbar.setAnchorView(it) }
        PiterfyHaptics.confirm(host)
        snackbar.show()
    }

    private fun activityOf(context: Context): MainActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is MainActivity) return c
            c = c.baseContext
        }
        return null
    }
}
