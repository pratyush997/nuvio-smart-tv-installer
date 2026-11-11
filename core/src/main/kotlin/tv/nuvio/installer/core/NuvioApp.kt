// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlinx.serialization.Serializable

/**
 * The Nuvio apps this installer can put on a Samsung TV. They use different package ids, so both
 * can be on one TV at the same time.
 *
 * [TV] is the official Nuvio TV web app (.wgt) from NuvioMedia. [LEGACY] is Nuvio Native Legacy,
 * an unofficial native rewrite by a community developer. Its Samsung builds are .tpk files, one per
 * Tizen generation, shipped unsigned so tools like this one can sign them for the TV; each release
 * also has a WebAssembly .wgt (Tizen 5.5+, its own package id), used only when no .tpk fits. Legacy
 * updates itself on the TV, so the version the TV reports is its app shell's, not the release's.
 */
@Serializable
enum class NuvioApp(val label: String, val repo: String, val ids: List<String>) {
    TV(
        "Nuvio TV", "NuvioMedia/NuvioTVSmart",
        listOf("NuvioTV001.NuvioTV", "NuvioTV001", "NuvioTV001.Nuvio", "Nuvio", "NuvioTV.NuvioTV", "NuvioTV"),
    ),
    LEGACY(
        "Nuvio Legacy", "iqui27/nuvio-native-legacy",
        // The .tpk (package and app id), and the WebAssembly .wgt build's package and app id.
        listOf("NuvioTV002.Nuvio", "NuvioTV002", "NuvioTV002.NuvioNative"),
    );

    val repoUrl: String get() = "https://github.com/$repo"

    /** True when the TV reports the release's version, so versions can be compared. Legacy updates itself. */
    val tracksVersions: Boolean get() = this == TV

    /** Whether GitHub pre-releases are offered. Legacy's are the developer's test builds. */
    val offersPreReleases: Boolean get() = this == TV

    /** True when this release is worth listing: it has a Samsung package, and is not a hidden pre-release. */
    fun offers(release: Release): Boolean =
        !release.draft && (offersPreReleases || !release.prerelease) && when (this) {
            TV -> release.wgtAsset != null
            LEGACY -> release.assets.any { it.name.endsWith(".tpk", ignoreCase = true) }
        }

    /**
     * The file to install from [release] on a TV running Tizen [tizenVersion]. Nuvio TV has one
     * .wgt for every TV. Legacy has a .tpk per Tizen generation, so it needs the TV's version.
     */
    fun packageFor(release: Release, tizenVersion: String?): PackagePick = when (this) {
        TV -> release.wgtAsset?.let { PackagePick.Found(it) } ?: PackagePick.Missing("This release has no Samsung .wgt file.")
        LEGACY -> {
            val variant = legacyVariant(tizenVersion)
            when {
                tizenVersion.isNullOrBlank() || TizenInstaller.parseVersion(tizenVersion) == null ->
                    PackagePick.Missing("Connect to the TV first, so the app can pick the right Nuvio Legacy file for it.")
                variant == null -> PackagePick.Missing("Nuvio Legacy needs a Samsung TV from 2018 or later (Tizen 4 or newer). This TV runs Tizen $tizenVersion.")
                else -> release.assets.firstOrNull { it.name.endsWith("-$variant.tpk", ignoreCase = true) }?.let { PackagePick.Found(it) }
                    // No native build for this TV in this release: the WebAssembly .wgt runs on Tizen 5.5 and later.
                    ?: release.wgtAsset?.takeIf { (TizenInstaller.compareVersions(tizenVersion, "5.5") ?: -1) >= 0 }?.let { PackagePick.Found(it) }
                    ?: PackagePick.Missing("${release.tag_name} has no Nuvio Legacy file for this TV. Pick another version.")
            }
        }
    }

    /** True when this app can run on a TV with [tizenVersion]; true when the version is not known. */
    fun supports(tizenVersion: String?): Boolean =
        this == TV || tizenVersion.isNullOrBlank() || TizenInstaller.parseVersion(tizenVersion) == null || legacyVariant(tizenVersion) != null

    /** True when this entry from the TV's app list is this app. */
    fun matches(app: Map<String, String>): Boolean = APP_ID_KEYS.any { key -> app[key]?.trim() in ids }

    companion object {
        private val APP_ID_KEYS = listOf("app_id", "app_tizen_id", "app_package_id", "package_id")

        /**
         * This app's entry in the TV's app list. Nuvio TV's list has loose ids like "Nuvio", so an
         * entry that is Legacy never counts as Nuvio TV.
         */
        fun find(apps: List<Map<String, String>>, app: NuvioApp): Map<String, String>? =
            apps.firstOrNull { entry -> app.matches(entry) && (app == LEGACY || !LEGACY.matches(entry)) }

        /**
         * Which Legacy .tpk fits a TV on Tizen [tizenVersion], as the Legacy README lists them:
         * 4.0 to 5.5 (2018 to 2020), 6.0 (2021), 6.5 to 7 (2022 and 2023), 8 and later (2024 on).
         * Null below Tizen 4 or when the version is unknown.
         */
        fun legacyVariant(tizenVersion: String?): String? {
            val version = tizenVersion?.let(TizenInstaller::parseVersion) ?: return null
            val (major, minor) = version
            return when {
                major < 4 -> null
                major < 6 -> "NuvioTpk40"
                major == 6 && minor < 5 -> "NuvioTpk60"
                major < 8 -> "NuvioTpk65"
                else -> "NuvioTpk"
            }
        }

        /** True for TVs from 2018 to 2020 (Tizen 4 to 5.5), where Legacy's native app tends to run smoother. */
        fun isOlderTv(tizenVersion: String?): Boolean {
            val major = tizenVersion?.let(TizenInstaller::parseVersion)?.first() ?: return false
            return major in 4..5
        }
    }
}

/** The file to download from a release for one TV, or why there is none. */
sealed interface PackagePick {
    data class Found(val asset: ReleaseAsset) : PackagePick
    data class Missing(val reason: String) : PackagePick
}
