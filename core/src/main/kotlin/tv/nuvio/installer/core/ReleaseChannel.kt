// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

/**
 * Stable or beta Nuvio versions. Stable shows only full releases; beta adds GitHub pre-releases.
 * Releases are given newest first, as GitHub lists them.
 */
object ReleaseChannel {
    /** The releases to offer. Stable falls back to everything when there is no full release yet. */
    fun visible(releases: List<Release>, beta: Boolean): List<Release> =
        if (beta) releases else releases.filter { !it.prerelease }.ifEmpty { releases }

    /**
     * The version to install or announce: the highest version number on the channel. When a
     * pre-release and a full release have the same number, the full release wins.
     */
    fun latest(releases: List<Release>, beta: Boolean): Release? {
        val candidates = visible(releases, beta)
        if (candidates.isEmpty()) return null
        return candidates.withIndex().maxWithOrNull(
            Comparator<IndexedValue<Release>> { a, b -> TizenInstaller.compareVersions(a.value.tag_name, b.value.tag_name) ?: 0 }
                .thenBy { if (it.value.prerelease) 0 else 1 }
                .thenByDescending { it.index }
        )?.value
    }
}
