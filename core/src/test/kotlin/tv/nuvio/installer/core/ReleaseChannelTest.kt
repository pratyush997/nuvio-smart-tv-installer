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

    @Test
    fun ordersPreReleasesBelowTheirFullRelease() {
        assertEquals(-1, ReleaseChannel.compare("1.4.0-beta.2", true, "1.4.0", false))
        assertEquals(1, ReleaseChannel.compare("1.4.0", false, "1.4.0-beta.2", true))
        assertEquals(-1, ReleaseChannel.compare("1.4.0-beta.2", true, "1.4.0-beta.10", true))
        assertEquals(-1, ReleaseChannel.compare("v1.4.0-beta.3", true, "1.4.0-rc.1", true))
        assertEquals(0, ReleaseChannel.compare("1.4.0-beta.2", true, "v1.4.0-beta.2", true))
        assertEquals(1, ReleaseChannel.compare("1.4.1-beta.1", true, "1.4.0", false))
        assertNull(ReleaseChannel.compare("nightly", true, "1.4.0", false))
    }

    @Test
    fun picksTheNewestBetaByItsNumber() {
        val beta2 = r("1.4.0-beta.2", pre = true)
        val beta10 = r("1.4.0-beta.10", pre = true)
        assertEquals(beta10, ReleaseChannel.latest(listOf(beta2, beta10), beta = true))
    }

    private fun tv(version: String?, tag: String? = null, beta: Boolean = false) =
        SavedTv("192.168.1.9", installedVersion = version, installedTag = tag, installedBeta = beta)

    @Test
    fun aTvOnAFullReleaseIsOnlyOfferedFullReleases() {
        val newerBeta = r("1.4.0-beta.1", pre = true)
        val current = r("1.3.11")
        assertNull(ReleaseChannel.updateFor(listOf(newerBeta, current), tv("1.3.11")))
        assertEquals(current, ReleaseChannel.updateFor(listOf(newerBeta, current), tv("1.3.10")))
        assertNull(ReleaseChannel.updateFor(listOf(current), tv(null)))
    }

    @Test
    fun aTvOnABetaIsOfferedNewerBetas() {
        // The TV reports only numbers: "1.4.0" for 1.4.0-beta.1.
        val onBeta = tv("1.4.0", tag = "1.4.0-beta.1", beta = true)
        val beta1 = r("1.4.0-beta.1", pre = true)
        val beta2 = r("1.4.0-beta.2", pre = true)
        val stable = r("1.3.11")
        assertNull(ReleaseChannel.updateFor(listOf(beta1, stable), onBeta))
        assertEquals(beta2, ReleaseChannel.updateFor(listOf(beta2, beta1, stable), onBeta))
    }

    @Test
    fun aTvOnABetaIsOfferedItsFullRelease() {
        val onBeta = tv("1.4.0", tag = "1.4.0-beta.2", beta = true)
        val final = r("1.4.0")
        assertEquals(final, ReleaseChannel.updateFor(listOf(final, r("1.4.0-beta.2", pre = true)), onBeta))
        // After installing it, the TV is back on full releases: a later beta is not offered.
        val onFinal = tv("1.4.0", tag = "1.4.0", beta = false)
        assertNull(ReleaseChannel.updateFor(listOf(r("1.5.0-beta.1", pre = true), final), onFinal))
    }

    @Test
    fun aBetaReplacedOutsideTheAppCountsAsAFullRelease() {
        // The app installed 1.4.0-beta.1, but the TV now reports 1.3.11: installed some other way.
        val replaced = tv("1.3.11", tag = "1.4.0-beta.1", beta = true)
        assertEquals(false, replaced.onBeta)
        assertEquals("1.3.11", replaced.installedLabel)
        assertNull(ReleaseChannel.updateFor(listOf(r("1.4.0-beta.2", pre = true), r("1.3.11")), replaced))
    }
}
