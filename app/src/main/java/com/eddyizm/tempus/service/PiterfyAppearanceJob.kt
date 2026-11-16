package com.eddyizm.tempus.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.eddyizm.tempus.appearance.PiterfyAppIcon

/**
 * Piterfy: raz na kilkanaście godzin ustawia ikonę zgodnie z dniem okolicznościowym, także gdy aplikacja jest zamknięta
 * (o północy zaczyna się albo kończy okno święta). Zmiana działa bez zamykania aplikacji (DONT_KILL_APP).
 */
class PiterfyAppearanceJob : JobService() {
    companion object {
        private const val JOB = 72861
        private const val EVERY_MS = 6L * 60 * 60 * 1000

        @JvmStatic
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB, ComponentName(context, PiterfyAppearanceJob::class.java))
                    .setPeriodic(EVERY_MS).setPersisted(true).build(),
            )
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        try { PiterfyAppIcon.apply(applicationContext) } catch (_: RuntimeException) { /* następnym razem */ }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
