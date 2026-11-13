// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import tv.nuvio.installer.core.InstallerEvents
import tv.nuvio.installer.core.LogLevel
import tv.nuvio.installer.core.ReleasePackage
import java.io.File

/** Where updating this app itself has got to. */
sealed interface SelfUpdateState {
    data object Idle : SelfUpdateState
    /** [percent] is null while the size is not known yet. */
    data class Downloading(val percent: Int?) : SelfUpdateState
    /** Handed to Android, which asks the user to confirm. */
    data object Installing : SelfUpdateState
    data class Failed(val message: String) : SelfUpdateState
}

/**
 * Updates this app in place: downloads the release's APK (checked against GitHub's sha256) and
 * hands it to Android's package installer, which asks the user to confirm. The first time,
 * Android also asks to allow installing apps from this app.
 */
object SelfUpdate {
    var state by mutableStateOf<SelfUpdateState>(SelfUpdateState.Idle)
        internal set

    val busy: Boolean get() = state is SelfUpdateState.Downloading || state is SelfUpdateState.Installing

    /**
     * Android's "Update this app?" screen, when it could not be shown straight away because the
     * app was in the background. [MainActivity] shows it on its next resume.
     */
    @Volatile
    internal var pendingConfirm: Intent? = null

    /** True while [MainActivity] is on screen. */
    @Volatile
    internal var appVisible = false

    /** Blocking: call from a background thread. [update] must have an APK. */
    fun run(context: Context, update: AppUpdate) {
        val apk = update.apk ?: return
        state = SelfUpdateState.Downloading(null)
        try {
            val directory = File(context.cacheDir, "app-update")
            directory.listFiles()?.filter { it.name != apk.name }?.forEach { it.delete() }
            val events = object : InstallerEvents {
                override fun log(level: LogLevel, text: String) {}
                override fun progress(label: String, done: Long, total: Long) {
                    // GitHub's redirect does not always say how big the file is; the release does.
                    val size = if (total > 0) total else apk.size
                    state = SelfUpdateState.Downloading(if (size > 0) (done * 100 / size).toInt().coerceIn(0, 100) else null)
                }
            }
            val file = ReleasePackage.fetch(apk, directory, events)
            state = SelfUpdateState.Installing
            install(context, file)
        } catch (e: Exception) {
            state = SelfUpdateState.Failed(e.message?.lineSequence()?.first() ?: "The update could not be downloaded.")
        }
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Once this app has installed itself, later updates may go through without a prompt.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            session.openWrite("app.apk", 0, file.length()).use { output ->
                file.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // Mutable: the installer adds the result to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, 0, Intent(context, SelfUpdateReceiver::class.java), flags)
            session.commit(callback.intentSender)
            session.close()
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    /** Shows Android's confirm screen now if the app is on screen, else when it is next opened. */
    internal fun confirm(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (appVisible && runCatching { context.startActivity(intent) }.isSuccess) return
        pendingConfirm = intent
    }
}

/** Receives the package installer's answer for [SelfUpdate]. */
class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SelfUpdate.state = when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    SelfUpdate.confirm(context, confirm)
                    SelfUpdateState.Installing
                } else SelfUpdateState.Failed("Android did not show the update screen. Try again.")
            }
            // On success Android restarts the app, so this is rarely seen.
            PackageInstaller.STATUS_SUCCESS -> SelfUpdateState.Idle
            // The user said no.
            PackageInstaller.STATUS_FAILURE_ABORTED -> SelfUpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                SelfUpdateState.Failed("This version can't replace the one on your phone.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> SelfUpdateState.Failed("Not enough space on the phone for the update.")
            else -> SelfUpdateState.Failed("Android couldn't install the update.")
        }
    }
}
