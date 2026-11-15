// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

/**
 * What an install, launch or uninstall is for: one of the Nuvio apps ([Known], with Nuvio's
 * rules), or any other app from a file the user picked ([Custom], no Nuvio rules).
 */
sealed interface InstallTarget {
    /** What the app is called in messages. */
    val label: String

    /** True when the TV reports the file's version, so an install is verified by it. */
    val tracksVersions: Boolean

    /** This app's entry in the TV's app list, or null when it is not installed. */
    fun find(apps: List<Map<String, String>>): Map<String, String>?

    data class Known(val app: NuvioApp) : InstallTarget {
        override val label get() = app.label
        override val tracksVersions get() = app.tracksVersions
        override fun find(apps: List<Map<String, String>>) = NuvioApp.find(apps, app)
    }

    data class Custom(val info: PackageInfo) : InstallTarget {
        override val label get() = info.label
        // Being listed is enough: other apps' version numbers are theirs to report.
        override val tracksVersions get() = false
        override fun find(apps: List<Map<String, String>>) = withIds(apps, listOf(info.metadata.packageId, info.metadata.appId))
    }

    /** True for Nuvio TV, whose messages mention the desktop installer. */
    val isNuvioTv: Boolean get() = this is Known && app == NuvioApp.TV

    /** The name in "uninstall X from the TV": "Nuvio" for Nuvio TV. */
    val shortLabel: String get() = if (isNuvioTv) "Nuvio" else label

    companion object {
        private val APP_ID_KEYS = listOf("app_id", "app_tizen_id", "app_package_id", "package_id")

        /** The first entry in the TV's app list with one of [ids]. */
        fun withIds(apps: List<Map<String, String>>, ids: Collection<String>): Map<String, String>? {
            val wanted = ids.filter { it.isNotBlank() }
            return apps.firstOrNull { entry -> APP_ID_KEYS.any { key -> entry[key]?.trim() in wanted } }
        }
    }
}
