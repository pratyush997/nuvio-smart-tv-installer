// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import tv.nuvio.installer.core.AutoUpdate
import tv.nuvio.installer.core.AutoUpdatePlan
import tv.nuvio.installer.core.CertificateExpiry
import tv.nuvio.installer.core.InstallerEvents
import tv.nuvio.installer.core.LogLevel
import tv.nuvio.installer.core.Release
import tv.nuvio.installer.core.ReleaseChannel
import tv.nuvio.installer.core.ReleaseNotes
import tv.nuvio.installer.core.ReleaseAsset
import tv.nuvio.installer.core.ReleasePackage
import tv.nuvio.installer.core.SamsungCertificates
import tv.nuvio.installer.core.SavedTv
import tv.nuvio.installer.core.SavedTvs
import tv.nuvio.installer.core.ExpiryState
import tv.nuvio.installer.core.GitHubReleases
import tv.nuvio.installer.core.IdentityStore
import tv.nuvio.installer.core.TizenInstaller
import tv.nuvio.installer.core.TvNetwork
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val PREF_CERT_NOTIFIED = "certNotified"
private const val PREF_AUTO_ATTEMPTS = "autoUpdateAttempts"

/** One install at a time on the TV: the app's buttons and the automatic update share it. */
object TvAccess {
    private val semaphore = java.util.concurrent.Semaphore(1)
    fun tryAcquire(): Boolean = semaphore.tryAcquire()
    fun release() = semaphore.release()
}

/**
 * A newer release of this app. [apk] is the release's APK, which the app downloads and installs
 * itself; [pageUrl] is the release page, for when it cannot. [notes] is the release body (Markdown).
 */
data class AppUpdate(val tag: String, val version: String, val apk: ReleaseAsset?, val pageUrl: String, val notes: String? = null)

sealed interface AppUpdateStatus {
    data object Checking : AppUpdateStatus
    data object UpToDate : AppUpdateStatus
    data object Failed : AppUpdateStatus
    data class Available(val update: AppUpdate) : AppUpdateStatus
}

/**
 * Every few hours (see [UpdateCheck.schedule]), compares the newest Nuvio release with the
 * version last seen on each TV. With automatic updates on, installs it on the TV (never opening
 * the Samsung sign-in); otherwise, or when that fails, notifies (tapping it opens the app and
 * starts the update). Also warns about certificates running out.
 *
 * Retries only when the Nuvio releases could not be fetched: nothing was installed then, so a
 * retry cannot use up a TV's automatic-update tries.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        runCatching { checkCertificates(prefs) }
        return if (runCatching { checkNuvio(prefs) }.isFailure) Result.retry() else Result.success()
    }

    /** Warns once when a saved certificate is about to expire, and once more when it has expired. */
    private fun checkCertificates(prefs: android.content.SharedPreferences) {
        val store = IdentityStore(File(applicationContext.filesDir, "identities"))
        val notified = prefs.getStringSet(PREF_CERT_NOTIFIED, emptySet()).orEmpty()
        for ((_, identity) in store.all()) {
            if (!identity.isUsable) continue
            val expiresAt = identity.expiresAt() ?: continue
            val state = CertificateExpiry.state(expiresAt)
            if (state == ExpiryState.VALID) continue
            val key = "${identity.fingerprint}:$state"
            if (key in notified) continue
            if (UpdateCheck.notifyCertificate(applicationContext, expiresAt, state == ExpiryState.EXPIRED)) {
                prefs.edit().putStringSet(PREF_CERT_NOTIFIED, notified + key).apply()
            }
            return
        }
    }

    /** Goes through every saved TV that has Nuvio on it. */
    private fun checkNuvio(prefs: android.content.SharedPreferences) {
        val tvs = TvList.load(prefs).filter { it.installedVersion != null }
        if (tvs.isEmpty()) return
        val releases = GitHubReleases().recent()
        val store = IdentityStore(File(applicationContext.filesDir, "identities"))
        val wifi = onWifi()
        for (tv in tvs) checkTv(prefs, tv, releases, store, wifi, nameTv = tvs.size > 1)
    }

    /** A TV on a beta (see [SavedTv.onBeta]) is told about newer betas too; other TVs only about full releases. */
    private fun checkTv(
        prefs: android.content.SharedPreferences,
        tv: SavedTv,
        releases: List<Release>,
        store: IdentityStore,
        onWifi: Boolean,
        nameTv: Boolean,
    ) {
        val installed = tv.installedLabel ?: return
        val update = ReleaseChannel.updateFor(releases, tv) ?: return
        val duid = tv.duid
        val hasCertificate = if (duid != null) store.candidatesFor(duid).isNotEmpty() else store.all().any { it.second.isUsable }
        val attemptsKey = "$PREF_AUTO_ATTEMPTS:${tv.key}:${update.tag_name}"
        fun plan(tvAwake: Boolean) = AutoUpdate.plan(
            update,
            enabled = prefs.getBoolean(PREF_AUTO_UPDATE, false),
            tvIp = tv.ip,
            onWifi = onWifi,
            hasCertificate = hasCertificate,
            attempts = prefs.getInt(attemptsKey, 0),
            tvAwake = tvAwake,
        )
        // Ask the TV only when everything else is ready. A TV that is off does not use up a try.
        val plan = plan(tvAwake = true).let {
            if (it is AutoUpdatePlan.Install && !TvNetwork.isAwake(tv.ip)) plan(tvAwake = false) else it
        }
        val label = tv.label.takeIf { nameTv }
        when (plan) {
            AutoUpdatePlan.None -> return
            is AutoUpdatePlan.Install -> {
                val error = autoInstall(tv.ip, plan.release, store)
                if (error == null) {
                    val version = plan.release.tag_name.removePrefix("v")
                    TvList.update(prefs) { list ->
                        list.map {
                            if (SavedTvs.sameTv(it, tv)) it.copy(installedVersion = version, installedTag = plan.release.tag_name, installedBeta = plan.release.prerelease)
                            else it
                        }
                    }
                    prefs.edit().remove(attemptsKey).apply()
                    if (prefs.getString("tvIp", null) == tv.ip) prefs.edit().putString(PREF_INSTALLED_VERSION, version).apply()
                    UpdateCheck.notifyAutoUpdated(applicationContext, tv, label, installed, UpdateCheck.versionName(plan.release))
                    return
                }
                prefs.edit().putInt(attemptsKey, prefs.getInt(attemptsKey, 0) + 1).apply()
                notifyOnce(prefs, tv, label, installed, plan.release, "Automatic update did not finish: $error")
            }
            is AutoUpdatePlan.Notify -> notifyOnce(prefs, tv, label, installed, plan.release, plan.reason?.let { "Not updated automatically: $it." })
        }
    }

    private fun notifyOnce(prefs: android.content.SharedPreferences, tv: SavedTv, label: String?, installed: String, release: Release, note: String?) {
        val key = "$PREF_NOTIFIED_TAG:${tv.key}"
        // Before several TVs were supported, one "notified" mark covered the only TV.
        val notified = prefs.getString(key, null) ?: prefs.getString(PREF_NOTIFIED_TAG, null).takeIf { label == null }
        if (notified == release.tag_name) return
        if (UpdateCheck.notify(applicationContext, tv, label, installed, UpdateCheck.versionName(release), note, ReleaseNotes.summary(release.body))) {
            prefs.edit().putString(key, release.tag_name).apply()
        }
    }

    /** Installs [release] on the TV. Returns null on success, or what went wrong. */
    private fun autoInstall(tvIp: String, release: Release, store: IdentityStore): String? {
        val asset = release.wgtAsset ?: return "the release has no Samsung package"
        if (!TvAccess.tryAcquire()) return "the app was using the TV"
        val errors = ArrayList<String>()
        val events = object : InstallerEvents {
            override fun log(level: LogLevel, text: String) {
                if (level == LogLevel.ERROR) errors += text
            }
        }
        return try {
            val cache = applicationContext.cacheDir
            val wgt = ReleasePackage.fetch(asset, File(cache, "downloads"), events)
            TizenInstaller(
                workDirectory = File(cache, "work"),
                identities = store,
                certificates = SamsungCertificates(File(applicationContext.filesDir, "samsung-ca")),
                events = events,
                openLogin = {},
            ).install(tvIp, wgt, allowSignIn = false)
            null
        } catch (e: Exception) {
            (e.message ?: errors.lastOrNull() ?: e.javaClass.simpleName).lineSequence().first()
        } finally {
            TvAccess.release()
        }
    }

    private fun onWifi(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

}

/** Twice a day: notifies once per version when a newer release of this app is out (tapping it updates the app). */
class AppUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val update = try {
            UpdateCheck.newerApp() ?: return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        }
        if (prefs.getString(PREF_APP_NOTIFIED_TAG, null) == update.tag) return Result.success()
        if (UpdateCheck.notifyApp(applicationContext, update)) {
            prefs.edit().putString(PREF_APP_NOTIFIED_TAG, update.tag).apply()
        }
        return Result.success()
    }
}

object UpdateCheck {
    const val ACTION_UPDATE = "tv.nuvio.installer.action.UPDATE"
    /** Tapping the "new version of this app" notification: opens the app and starts its update. */
    const val ACTION_APP_UPDATE = "tv.nuvio.installer.action.APP_UPDATE"
    /** Where this app's own releases are published. */
    const val APP_REPO = "pratyush997/nuvio-smart-tv-installer"
    /** Separate channels, so each kind can be turned off on its own in the phone's settings. */
    private const val CHANNEL_NUVIO = "nuvio-updates"
    private const val CHANNEL_APP = "app-updates"
    private const val CHANNEL_CERT = "certificate"
    /** The one channel versions up to 1.5 used for everything. */
    private const val OLD_CHANNEL = "updates"
    private const val NOTIFICATION_ID = 1
    private const val APP_NOTIFICATION_ID = 2
    private const val CERT_NOTIFICATION_ID = 3

    /** The newest release of this app when it is newer than the running one. Throws on network errors. */
    fun newerApp(current: String = BuildConfig.VERSION_NAME): AppUpdate? {
        val latest = GitHubReleases(APP_REPO).latest() ?: return null
        if (TizenInstaller.compareVersions(current, latest.tag_name) != -1) return null
        val page = latest.html_url.ifBlank { "https://github.com/$APP_REPO/releases/latest" }
        return AppUpdate(latest.tag_name, latest.tag_name.removePrefix("v"), latest.apkAsset, page, latest.body)
    }

    fun notifyApp(context: Context, update: AppUpdate): Boolean {
        if (!canNotify(context)) return false
        val manager = channels(context)
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).setAction(ACTION_APP_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "You have ${BuildConfig.VERSION_NAME}. Tap to update."
        val whatsNew = ReleaseNotes.summary(update.notes)
        val notification = Notification.Builder(context, CHANNEL_APP)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("TV Installer ${update.version} is available")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(text, whatsNew.ifBlank { null }).joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(APP_NOTIFICATION_ID, notification)
        return true
    }

    fun dismissApp(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(APP_NOTIFICATION_ID)
    }

    /**
     * Nuvio is checked every 3 hours while automatic updates are on, so a check lands while the
     * TV is on more often; every 12 hours otherwise. This app is checked every 12 hours.
     * Call again when automatic updates are turned on or off.
     */
    fun schedule(context: Context, autoUpdate: Boolean) {
        // So the three kinds show in the phone's notification settings from the first start.
        channels(context)
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = WorkManager.getInstance(context)
        val nuvio = PeriodicWorkRequestBuilder<UpdateCheckWorker>(if (autoUpdate) 3L else 12L, TimeUnit.HOURS).setConstraints(online).build()
        work.enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.UPDATE, nuvio)
        val app = PeriodicWorkRequestBuilder<AppUpdateWorker>(12, TimeUnit.HOURS).setConstraints(online).build()
        work.enqueueUniquePeriodicWork("app-update-check", ExistingPeriodicWorkPolicy.KEEP, app)
    }

    /** Creates the notification channels, and removes the single one older versions used. */
    private fun channels(context: Context): NotificationManager {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_NUVIO, "Nuvio updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A new Nuvio version is out, or was installed on your TV." },
            NotificationChannel(CHANNEL_APP, "TV Installer updates", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A new version of this app is out." },
            NotificationChannel(CHANNEL_CERT, "Certificate reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Your TV's certificate is about to run out." },
        ))
        manager.deleteNotificationChannel(OLD_CHANNEL)
        return manager
    }

    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return manager.areNotificationsEnabled()
    }

    /** Extra on [ACTION_UPDATE]: the [SavedTv.key] of the TV to update. */
    const val EXTRA_TV_KEY = "tvKey"

    /** One "new version" notification per TV, so one TV's notification does not replace another's. */
    private fun tvNotificationId(tvKey: String) = 1000 + (tvKey.hashCode() and 0x7fff)

    /** A Nuvio version as notifications show it: "1.3.11", or "1.4.0-beta.2 (beta)". */
    fun versionName(release: Release): String =
        release.tag_name.removePrefix("v") + if (release.prerelease) " (beta)" else ""

    /** "your TV", or the TV's name in quotes when the phone has several. */
    private fun tvName(label: String?) = if (label != null) "\"$label\"" else "your TV"

    /**
     * Tells the user a new Nuvio is out for [tv]; tapping it updates that TV. [label] names the
     * TV when the phone has several. Returns false when notifications are not allowed, so the
     * check tries again later.
     */
    fun notify(
        context: Context, tv: SavedTv, label: String?, installed: String, latest: String,
        note: String? = null, whatsNew: String = "",
    ): Boolean {
        if (!canNotify(context)) return false
        val manager = channels(context)
        val id = tvNotificationId(tv.key)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_TV_KEY, tv.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "${tvName(label).replaceFirstChar { it.uppercase() }} has $installed. Tap to update it."
        val notification = Notification.Builder(context, CHANNEL_NUVIO)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(if (label != null) "Nuvio $latest is out for \"$label\"" else "Nuvio $latest is out")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(text, note, whatsNew.ifBlank { null }).joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
        return true
    }

    fun notifyAutoUpdated(context: Context, tv: SavedTv, label: String?, from: String, to: String): Boolean {
        if (!canNotify(context)) return false
        val manager = channels(context)
        val id = tvNotificationId(tv.key)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_NUVIO)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("Nuvio $to is on ${tvName(label)}")
            .setContentText("Updated automatically from $from.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
        return true
    }

    fun notifyCertificate(context: Context, expiresAt: Instant, expired: Boolean): Boolean {
        if (!canNotify(context)) return false
        val manager = channels(context)
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val date = formatDate(expiresAt)
        val notification = Notification.Builder(context, CHANNEL_CERT)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(if (expired) "Your TV's certificate expired on $date" else "Your TV's certificate expires on $date")
            .setContentText("Tap to see what this means for Nuvio updates.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(CERT_NOTIFICATION_ID, notification)
        return true
    }

    /** Removes the "new version" notification for the TV with [tvKey], and the single one older versions showed. */
    fun dismiss(context: Context, tvKey: String?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
        if (tvKey != null) manager.cancel(tvNotificationId(tvKey))
    }
}
