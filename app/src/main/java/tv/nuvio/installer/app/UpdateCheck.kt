package tv.nuvio.installer.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import tv.nuvio.installer.core.GitHubReleases
import tv.nuvio.installer.core.TizenInstaller
import java.util.concurrent.TimeUnit

/**
 * Twice a day, compares the newest Nuvio release on GitHub with the version last seen on the TV
 * and posts a notification when a newer one is out. Tapping it opens the app and starts the update.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val installed = prefs.getString(PREF_INSTALLED_VERSION, null) ?: return Result.success()
        val latest = try {
            GitHubReleases().recent().firstOrNull { !it.prerelease }
        } catch (e: Exception) {
            return Result.retry()
        } ?: return Result.success()
        if (TizenInstaller.compareVersions(installed, latest.tag_name) != -1) return Result.success()
        if (prefs.getString(PREF_NOTIFIED_TAG, null) == latest.tag_name) return Result.success()
        if (UpdateCheck.notify(applicationContext, installed, latest.tag_name)) {
            prefs.edit().putString(PREF_NOTIFIED_TAG, latest.tag_name).apply()
        }
        return Result.success()
    }
}

object UpdateCheck {
    const val ACTION_UPDATE = "tv.nuvio.installer.action.UPDATE"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 1

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return manager.areNotificationsEnabled()
    }

    /** Returns false when notifications are not allowed, so the check tries again later. */
    fun notify(context: Context, installed: String, latest: String): Boolean {
        if (!canNotify(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Nuvio updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(ACTION_UPDATE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Nuvio $latest is out")
            .setContentText("Your TV has $installed. Tap to update it.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
