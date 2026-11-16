package com.eddyizm.tempus.ui.dialog

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.DialogPiterfyUpdateBinding
import com.eddyizm.tempus.github.utils.PiterfyUpdateLogic
import com.eddyizm.tempus.github.utils.PiterfyUpdateManager
import com.eddyizm.tempus.github.utils.PiterfyUpdateManager.Kind
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Piterfy: okno aktualizacji (jak karta „Aktualizacje” w Szpili i Kropi): wersje, rozmiar, co nowego, a potem
 * pobieranie z postępem, sprawdzenie pliku i systemowy instalator. Stan trzyma [PiterfyUpdateManager], więc okno
 * przeżywa obrót ekranu i można je zamknąć w trakcie pobierania.
 */
class PiterfyUpdateDialog : DialogFragment() {
    companion object {
        private var pending: PiterfyUpdateLogic.Update? = null

        @JvmStatic
        fun show(activity: FragmentActivity, update: PiterfyUpdateLogic.Update) {
            if (activity.supportFragmentManager.findFragmentByTag("piterfy_update") != null) return
            pending = update
            PiterfyUpdateDialog().show(activity.supportFragmentManager, "piterfy_update")
        }
    }

    private var binding: DialogPiterfyUpdateBinding? = null
    private val listener: (PiterfyUpdateManager.State) -> Unit = { render(it) }

    private fun update() = PiterfyUpdateManager.state.update ?: pending

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val b = DialogPiterfyUpdateBinding.inflate(layoutInflater)
        binding = b
        val update = update()
        if (update != null) {
            b.updateVersions.text = getString(R.string.update_versions, PiterfyUpdateManager.installedVersionName(requireContext()), update.version)
            if (update.size > 0) b.updateSize.text = getString(R.string.update_size, "%.1f".format(update.size / 1048576.0))
            else b.updateSize.visibility = View.GONE
            val notes = PiterfyUpdateLogic.notes(update.notes)
            if (notes.isEmpty()) {
                b.updateNotesTitle.visibility = View.GONE
                b.updateNotes.visibility = View.GONE
            } else b.updateNotes.text = notes.joinToString("\n\n") { "•  $it" }
        }
        return MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.update_title)
            .setView(b.root)
            .setPositiveButton(R.string.update_download_install, null)
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_on_github, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        if (update() == null) { dismissAllowingStateLoss(); return }
        val d = dialog as AlertDialog
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onPositive() }
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { onNegative() }
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            update()?.htmlUrl?.takeIf { it.isNotEmpty() }?.let {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        PiterfyUpdateManager.addListener(listener)
        render(PiterfyUpdateManager.state)
    }

    override fun onResume() {
        super.onResume()
        // Po powrocie z ekranu „Instaluj nieznane aplikacje”: jeśli zgoda jest, kończymy instalację.
        if (PiterfyUpdateManager.state.kind == Kind.NEED_PERMISSION && PiterfyUpdateManager.canInstall(requireContext()))
            PiterfyUpdateManager.resume(requireContext())
    }

    override fun onStop() {
        PiterfyUpdateManager.removeListener(listener)
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun onPositive() {
        val update = update() ?: return
        when (PiterfyUpdateManager.state.kind) {
            Kind.NEED_PERMISSION -> PiterfyUpdateManager.openInstallPermission(requireContext())
            Kind.READY -> dismiss()
            Kind.DOWNLOADING -> Unit
            else -> PiterfyUpdateManager.start(requireContext(), update)
        }
    }

    private fun onNegative() {
        when (PiterfyUpdateManager.state.kind) {
            Kind.DOWNLOADING -> PiterfyUpdateManager.cancel()
            Kind.READY -> dismiss()
            else -> {
                update()?.let { PiterfyUpdateManager.later(requireContext(), it) }
                PiterfyUpdateManager.reset()
                dismiss()
            }
        }
    }

    private fun render(state: PiterfyUpdateManager.State) {
        val b = binding ?: return
        val d = dialog as? AlertDialog ?: return
        val positive = d.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        val negative = d.getButton(AlertDialog.BUTTON_NEGATIVE)
        val neutral = d.getButton(AlertDialog.BUTTON_NEUTRAL)
        val status = b.updateStatus
        val progress = b.updateProgress
        positive.isEnabled = true
        neutral.visibility = View.VISIBLE
        negative.visibility = View.VISIBLE
        negative.setText(R.string.update_later)
        when (state.kind) {
            Kind.IDLE -> {
                progress.visibility = View.GONE; status.visibility = View.GONE
                positive.setText(R.string.update_download_install)
            }
            Kind.DOWNLOADING -> {
                progress.visibility = View.VISIBLE
                if (state.total > 0) { progress.isIndeterminate = false; progress.setProgressCompat(state.percent, true) }
                else progress.isIndeterminate = true
                status.visibility = View.VISIBLE
                status.text = getString(R.string.update_downloading, state.percent)
                positive.isEnabled = false
                positive.setText(R.string.update_download_install)
                negative.setText(R.string.update_cancel)
                neutral.visibility = View.GONE
            }
            Kind.NEED_PERMISSION -> {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE; status.text = state.message
                positive.setText(R.string.update_allow_button)
            }
            Kind.READY -> {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE; status.text = state.message
                positive.setText(android.R.string.ok)
                negative.visibility = View.GONE
            }
            Kind.ERROR -> {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE; status.text = state.message
                positive.setText(R.string.update_retry)
            }
        }
    }
}
