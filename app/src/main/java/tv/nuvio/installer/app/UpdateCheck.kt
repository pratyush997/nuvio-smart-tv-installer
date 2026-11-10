// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 pratyush997 and contributors

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
import android.net.Uri
import tv.nuvio.installer.core.GitHubReleases
import tv.nuvio.installer.core.TizenInstaller
import java.util.concurrent.TimeUnit

/** A newer release of this app. [downloadUrl] is the APK itself when the release has one. */
data class AppUpdate(val tag: String, val version: String, val downloadUrl: String, val pageUrl: String)

/**
 * Twice a day:
 * - compares the newest Nuvio release with the version last seen on the TV, and notifies when a
 *   newer one is out (tapping it opens the app and starts the update);
 * - compares this app's newest release on GitHub with the running version, and notifies when a
 *   newer one is out (tapping it downloads the APK).
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val nuvio = runCatching { checkNuvio(prefs) }
        val app = runCatching { checkApp(prefs) }
        return if (nuvio.isFailure || app.isFailure) Result.retry() else Result.success()
    }

    private fun checkNuvio(prefs: android.content.SharedPreferences) {
        val installed = prefs.getString(PREF_INSTALLED_VERSION, null) ?: return
        val latest = GitHubReleases().recent().firstOrNull { !it.prerelease } ?: return
        if (TizenInstaller.compareVersions(installed, latest.tag_name) != -1) return
        if (prefs.getString(PREF_NOTIFIED_TAG, null) == latest.tag_name) return
        if (UpdateCheck.notify(applicationContext, installed, latest.tag_name)) {
            prefs.edit().putString(PREF_NOTIFIED_TAG, latest.tag_name).apply()
        }
    }

    private fun checkApp(prefs: android.content.SharedPreferences) {
        val update = UpdateCheck.newerApp() ?: return
        if (prefs.getString(PREF_APP_NOTIFIED_TAG, null) == update.tag) return
        if (UpdateCheck.notifyApp(applicationContext, update)) {
            prefs.edit().putString(PREF_APP_NOTIFIED_TAG, update.tag).apply()
        }
    }
}

object UpdateCheck {
    const val ACTION_UPDATE = "tv.nuvio.installer.action.UPDATE"
    /** Where this app's own releases are published. */
    const val APP_REPO = "pratyush997/nuvio-smart-tv-installer"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 1
    private const val APP_NOTIFICATION_ID = 2

    /** The newest release of this app when it is newer than the running one. Throws on network errors. */
    fun newerApp(current: String = BuildConfig.VERSION_NAME): AppUpdate? {
        val latest = GitHubReleases(APP_REPO).latest() ?: return null
        if (TizenInstaller.compareVersions(current, latest.tag_name) != -1) return null
        val page = latest.html_url.ifBlank { "https://github.com/$APP_REPO/releases/latest" }
        return AppUpdate(latest.tag_name, latest.tag_name.removePrefix("v"), latest.apkAsset?.browser_download_url ?: page, page)
    }

    fun notifyApp(context: Context, update: AppUpdate): Boolean {
        if (!canNotify(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val download = PendingIntent.getActivity(
            context, 1,
            Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("TV Installer ${update.version} is available")
            .setContentText("You have ${BuildConfig.VERSION_NAME}. Tap to download the update.")
            .setContentIntent(download)
            .setAutoCancel(true)
            .build()
        manager.notify(APP_NOTIFICATION_ID, notification)
        return true
    }

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
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
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
