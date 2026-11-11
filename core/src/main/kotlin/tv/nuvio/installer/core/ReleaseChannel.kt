// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlin.math.sign

/**
 * Stable or beta Nuvio versions. Stable means full releases only; beta adds GitHub pre-releases.
 * Releases are given newest first, as GitHub lists them. Each TV is on the beta channel while it
 * runs a beta this app installed (see [SavedTv.onBeta]); there is no setting for it.
 */
object ReleaseChannel {
    /** The releases to offer. Stable falls back to everything when there is no full release yet. */
    fun visible(releases: List<Release>, beta: Boolean): List<Release> =
        if (beta) releases else releases.filter { !it.prerelease }.ifEmpty { releases }

    /**
     * The version to install or announce: the highest on the channel, by [compare]. Ties (or
     * tags without a version number) go to the release GitHub lists first.
     */
    fun latest(releases: List<Release>, beta: Boolean): Release? {
        val candidates = visible(releases, beta)
        if (candidates.isEmpty()) return null
        return candidates.withIndex().maxWithOrNull(
            Comparator<IndexedValue<Release>> { a, b -> compare(a.value.tag_name, a.value.prerelease, b.value.tag_name, b.value.prerelease) ?: 0 }
                .thenByDescending { it.index }
        )?.value
    }

    /** The newest release for [tv]'s channel: betas included while it runs one. */
    fun latestFor(releases: List<Release>, tv: SavedTv?): Release? = latest(releases, beta = tv?.onBeta == true)

    /**
     * The release to offer [tv], or null when it is up to date (or Nuvio is not on it). A TV on
     * a beta is offered newer betas and any full release with the same or a higher number; any
     * other TV only newer full releases.
     */
    fun updateFor(releases: List<Release>, tv: SavedTv): Release? {
        val installed = tv.installedVersion ?: return null
        val latest = latestFor(releases, tv) ?: return null
        val order = if (tv.onBeta) compare(tv.installedTag.orEmpty(), true, latest.tag_name, latest.prerelease)
        else compare(installed, false, latest.tag_name, latest.prerelease)
        return latest.takeIf { order == -1 }
    }

    /**
     * Orders two versions: by number, then a pre-release below the full release with the same
     * number, then two pre-releases by what follows the number ("beta.2" before "beta.10",
     * "beta" before "rc"). Null when either has no version number.
     */
    fun compare(leftTag: String, leftPre: Boolean, rightTag: String, rightPre: Boolean): Int? {
        val numbers = TizenInstaller.compareVersions(leftTag, rightTag) ?: return null
        if (numbers != 0) return numbers
        if (leftPre != rightPre) return if (leftPre) -1 else 1
        if (!leftPre) return 0
        return naturalCompare(suffix(leftTag), suffix(rightTag)).sign
    }

    private val number = Regex("(?<![\\d.])\\d+(?:\\.\\d+){0,2}")
    private val chunk = Regex("\\d+|[^\\d.\\-+_]+")

    /** What follows the version number: "beta.2" in "v1.4.0-beta.2". */
    private fun suffix(tag: String): String = number.find(tag)?.let { tag.substring(it.range.last + 1) }.orEmpty()

    /** Compares runs of digits as numbers and the rest as text, ignoring case and separators. */
    private fun naturalCompare(a: String, b: String): Int {
        val left = chunk.findAll(a).map { it.value }.toList()
        val right = chunk.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val xs = x.trimStart('0')
                val ys = y.trimStart('0')
                if (xs.length != ys.length) xs.length - ys.length else xs.compareTo(ys)
            } else x.compareTo(y, ignoreCase = true)
            if (c != 0) return c
        }
        return left.size - right.size
    }
}
