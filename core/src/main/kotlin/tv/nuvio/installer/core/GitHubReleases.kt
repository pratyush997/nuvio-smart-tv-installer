// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 pratyush997 and contributors

package tv.nuvio.installer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReleaseAsset(
    val name: String,
    val size: Long = 0,
    val browser_download_url: String,
    val digest: String? = null,
    /** API URL; serves the file with `Accept: application/octet-stream` (fallback download route). */
    val url: String = "",
)

@Serializable
data class Release(
    val id: Long,
    val tag_name: String,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val published_at: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val wgtAsset: ReleaseAsset? get() = assets.firstOrNull { it.name.endsWith(".wgt", ignoreCase = true) }
    val label: String get() = (name?.takeIf { it.isNotBlank() } ?: tag_name) + if (prerelease) " (pre-release)" else ""
}

/** Reads Nuvio TV releases from GitHub. */
class GitHubReleases(private val repo: String = "NuvioMedia/NuvioTVSmart") {
    private val json = Json { ignoreUnknownKeys = true }

    /** Recent non-draft releases that contain a Samsung .wgt asset, newest first. */
    fun recent(limit: Int = 5): List<Release> {
        val text = Http.getText(
            "https://api.github.com/repos/$repo/releases?per_page=20",
            mapOf("Accept" to "application/vnd.github+json")
        )
        return json.decodeFromString<List<Release>>(text)
            .filter { !it.draft && it.wgtAsset != null }
            .take(limit)
    }
}
