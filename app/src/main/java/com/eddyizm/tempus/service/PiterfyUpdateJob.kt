package com.eddyizm.tempus.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.eddyizm.tempus.R
import com.eddyizm.tempus.github.utils.PiterfyUpdateLogic
import com.eddyizm.tempus.github.utils.PiterfyUpdateManager
import com.eddyizm.tempus.util.Preferences
import java.util.concurrent.Executors

/**
 * Piterfy: sprawdzanie aktualizacji w tle (jak w Kropi i Szpili): co 6 godzin pyta GitHuba i raz na wersję pokazuje
 * powiadomienie, które otwiera okno aktualizacji. Wyłącza się razem z przełącznikiem sprawdzania w ustawieniach.
 */
class PiterfyUpdateJob : JobService() {
    companion object {
        private const val JOB = 72851
        private const val CHANNEL = "piterfy_update"
        private const val NOTIFICATION = 72851

        @JvmStatic
        fun schedule(context: Context) {
            if (!PiterfyUpdateManager.supported()) return
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB) != null) return  // nie zerujemy okresu przy każdym starcie aplikacji
            scheduler.schedule(
                JobInfo.Builder(JOB, ComponentName(context, PiterfyUpdateJob::class.java))
                    .setPeriodic(PiterfyUpdateLogic.BACKGROUND_EVERY_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build(),
            )
        }

        private fun notify(context: Context, update: PiterfyUpdateLogic.Update) {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return
            if (Build.VERSION.SDK_INT >= 26) {
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, context.getString(R.string.update_notif_channel), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val open = PendingIntent.getActivity(
                context, NOTIFICATION,
                launch.putExtra(PiterfyUpdateManager.EXTRA_OPEN_UPDATE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.update_notif_title, update.version))
                .setContentText(context.getString(R.string.update_notif_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try { manager.notify(NOTIFICATION, notification) } catch (_: SecurityException) { /* brak zgody na powiadomienia */ }
        }
    }

    private val io = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters): Boolean {
        io.execute {
            try {
                if (Preferences.isGithubUpdateEnabled()) {
                    PiterfyUpdateManager.backgroundCheck(applicationContext)?.let { notify(applicationContext, it) }
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
