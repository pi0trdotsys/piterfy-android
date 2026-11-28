package com.eddyizm.tempus.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.PiterfyHaptics
import com.eddyizm.tempus.util.PiterfyTv
import com.eddyizm.tempus.util.PiterfyTvPlan
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
        val tv = PiterfyTv.isTelevision()
        val snackbar = Snackbar.make(host, message, if (tv) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_LONG)
        activityOf(host.context)?.getSnackbarAnchor()?.let { snackbar.setAnchorView(it) }
        snackbar.setAction(R.string.song_bottom_sheet_undo) {
            PiterfyHaptics.confirm(it)
            onUndo.run()
        }
        PiterfyHaptics.tick(host)
        if (tv) showForRemote(snackbar, host)
        snackbar.show()
    }

    /**
     * Na telewizorze „Cofnij” musi być osiągalne pilotem: przycisk dostaje fokus (strzałki nie dochodzą do Snackbara same),
     * pasek znika po [PiterfyTvPlan.undoDurationMs], a po zamknięciu fokus wraca tam, gdzie był, zamiast skakać na początek listy.
     */
    private fun showForRemote(snackbar: Snackbar, host: View) {
        val previous = host.rootView.findFocus()
        val hide = Runnable { if (snackbar.isShown) snackbar.dismiss() }
        snackbar.view.postDelayed(hide, PiterfyTvPlan.undoDurationMs(0, true).toLong())
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onShown(sb: Snackbar) {
                sb.view.findViewById<View>(com.google.android.material.R.id.snackbar_action)?.requestFocus()
            }

            override fun onDismissed(sb: Snackbar, event: Int) {
                sb.view.removeCallbacks(hide)
                if (previous != null && previous.isAttachedToWindow && previous.isShown) previous.requestFocus()
            }
        })
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
