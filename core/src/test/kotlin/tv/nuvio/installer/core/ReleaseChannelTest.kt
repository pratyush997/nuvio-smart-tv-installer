// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseChannelTest {
    private var id = 0L
    private fun r(tag: String, pre: Boolean = false) = Release(++id, tag, prerelease = pre)

    private val beta = r("v1.4.0-beta.1", pre = true)
    private val stable = r("v1.3.0")
    private val older = r("v1.2.0")

    @Test
    fun stableHidesPreReleases() {
        assertEquals(listOf(stable, older), ReleaseChannel.visible(listOf(beta, stable, older), beta = false))
        assertEquals(stable, ReleaseChannel.latest(listOf(beta, stable, older), beta = false))
    }

    @Test
    fun betaIncludesThem() {
        assertEquals(listOf(beta, stable, older), ReleaseChannel.visible(listOf(beta, stable, older), beta = true))
        assertEquals(beta, ReleaseChannel.latest(listOf(beta, stable, older), beta = true))
    }

    @Test
    fun betaStillGetsANewerStableHotfix() {
        // A hotfix published after the beta is listed first, but the beta has the higher number.
        val hotfix = r("v1.3.1")
        assertEquals(beta, ReleaseChannel.latest(listOf(hotfix, beta, stable), beta = true))
        val final = r("v1.4.1")
        assertEquals(final, ReleaseChannel.latest(listOf(final, beta), beta = true))
    }

    @Test
    fun fullReleaseWinsOverItsBeta() {
        val final = r("v1.4.0")
        assertEquals(final, ReleaseChannel.latest(listOf(beta, final), beta = true))
        assertEquals(final, ReleaseChannel.latest(listOf(final, beta), beta = true))
    }

    @Test
    fun onlyPreReleasesStillShowOnStable() {
        assertEquals(listOf(beta), ReleaseChannel.visible(listOf(beta), beta = false))
        assertEquals(beta, ReleaseChannel.latest(listOf(beta), beta = false))
    }

    @Test
    fun unparsableTagsKeepGitHubOrder() {
        val a = r("nightly")
        val b = r("snapshot")
        assertEquals(a, ReleaseChannel.latest(listOf(a, b), beta = false))
        assertNull(ReleaseChannel.latest(emptyList(), beta = true))
    }
}
