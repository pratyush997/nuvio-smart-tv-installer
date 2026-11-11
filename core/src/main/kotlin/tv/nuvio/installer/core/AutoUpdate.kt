// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.io.File
import java.security.MessageDigest

/** Thrown instead of opening the Samsung sign-in when installing without the user (automatic updates). */
class SignInNeededException : InstallerException(
    "This TV has no saved certificate, and an automatic update cannot open the Samsung sign-in. Open the app and tap Update."
)

/** Downloads a release's .wgt into [directory] and checks it against GitHub's sha256 digest. */
object ReleasePackage {
    fun fetch(asset: ReleaseAsset, directory: File, events: InstallerEvents): File {
        directory.mkdirs()
        val target = File(directory, asset.name)
        val expected = asset.digest?.removePrefix("sha256:")?.lowercase()
        if (target.isFile && target.length() == asset.size && (expected == null || sha256(target) == expected)) {
            events.info("Using downloaded ${asset.name}.")
            return target
        }
        events.info("Downloading ${asset.name}...")
        try {
            downloadFile(asset.browser_download_url, target, "Downloading", events)
        } catch (e: InstallerException) {
            if (asset.url.isBlank()) throw e
            events.info("Main download link failed; trying GitHub's API download route.")
            downloadFile(asset.url, target, "Downloading", events, mapOf("Accept" to "application/octet-stream"))
        }
        if (expected != null) {
            if (sha256(target) != expected) {
                target.delete()
                throw InstallerException("The download is corrupted (checksum mismatch). Try again.")
            }
            events.success("Download verified (sha256).")
        }
        return target
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** What the background check does about a newer Nuvio release. */
sealed interface AutoUpdatePlan {
    /** Nothing newer than what the TV has (or nothing known about the TV). */
    data object None : AutoUpdatePlan
    /** Install [release] on the TV now, without asking. */
    data class Install(val release: Release) : AutoUpdatePlan
    /** Tell the user about [release]; [reason] says why it was not installed automatically, if it was meant to be. */
    data class Notify(val release: Release, val reason: String?) : AutoUpdatePlan
}

object AutoUpdate {
    /** Tries per release before giving up and only notifying. */
    const val MAX_ATTEMPTS = 3

    fun plan(
        installed: String?,
        latest: Release?,
        enabled: Boolean,
        tvIp: String,
        onWifi: Boolean,
        hasCertificate: Boolean,
        attempts: Int,
    ): AutoUpdatePlan {
        if (installed == null || latest == null) return AutoUpdatePlan.None
        if (TizenInstaller.compareVersions(installed, latest.tag_name) != -1) return AutoUpdatePlan.None
        if (!enabled) return AutoUpdatePlan.Notify(latest, null)
        val reason = when {
            latest.wgtAsset == null -> "the release has no Samsung package"
            tvIp.isBlank() -> "no TV is selected"
            !onWifi -> "the phone was not on Wi-Fi"
            !hasCertificate -> "this TV's certificate is not saved on the phone"
            attempts >= MAX_ATTEMPTS -> "the TV could not be reached"
            else -> null
        }
        return if (reason == null) AutoUpdatePlan.Install(latest) else AutoUpdatePlan.Notify(latest, reason)
    }
}
