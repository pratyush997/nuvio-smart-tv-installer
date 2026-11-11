// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CertificatePreflightTest {
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private fun days(n: Long): Instant = now.plus(Duration.ofDays(n))
    private val installed = mapOf("app_id" to "NuvioTV001.NuvioTV", "app_version" to "1.2.0")

    private val valid = TestCerts.identity(lastSuccessfulInstallAt = "2026-11-01T00:00:00Z")
    private val expired = TestCerts.identity(authorNotAfter = days(-3), distributorNotAfter = days(400))

    @Test
    fun noCertificatesAndNuvioNotInstalledMeansCreateOne() {
        val result = CertificatePreflight.check(emptyList(), installed = null, now = now)
        assertTrue(result.usable.isEmpty())
        assertNull(result.blocked)
    }

    @Test
    fun noCertificatesAndNuvioInstalledIsBlocked() {
        val result = CertificatePreflight.check(emptyList(), installed, now)
        val blocked = assertIs<MissingIdentityException>(result.blocked)
        assertTrue(blocked !is CertificateExpiredException)
        assertTrue(blocked.message!!.contains("Nuvio TV 1.2.0 is already installed"))
    }

    @Test
    fun expiredCertificatesAreSkipped() {
        val result = CertificatePreflight.check(listOf(expired, valid), installed, now)
        assertEquals(listOf(valid), result.usable)
        assertNull(result.blocked)
        assertTrue(result.notes.any { it.startsWith("Skipping a saved certificate") })
    }

    @Test
    fun onlyExpiredAndNuvioNotInstalledMeansCreateOne() {
        val result = CertificatePreflight.check(listOf(expired), installed = null, now = now)
        assertTrue(result.usable.isEmpty())
        assertNull(result.blocked)
    }

    @Test
    fun onlyExpiredAndNuvioInstalledIsBlockedWithTheExpiryDate() {
        val result = CertificatePreflight.check(listOf(expired), installed, now)
        val blocked = assertIs<CertificateExpiredException>(result.blocked)
        assertTrue(blocked.message!!.contains("expired on"), blocked.message)
        assertTrue(blocked.message!!.contains("Uninstall Nuvio from the TV"), blocked.message)
        // Still a MissingIdentityException, so the app offers import and uninstall.
        assertIs<MissingIdentityException>(blocked)
    }

    @Test
    fun expiringSoonIsStillUsable() {
        val soon = TestCerts.identity(authorNotAfter = days(5), lastSuccessfulInstallAt = "2026-11-01T00:00:00Z")
        val result = CertificatePreflight.check(listOf(soon), installed, now)
        assertEquals(listOf(soon), result.usable)
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun warnsWhenNoCertificateHasInstalledNuvioBefore() {
        val fresh = TestCerts.identity()
        val result = CertificatePreflight.check(listOf(fresh), installed, now)
        assertEquals(listOf(fresh), result.usable)
        assertNull(result.blocked)
        assertTrue(result.notes.single().contains("has not installed Nuvio on this TV before"))
    }

    @Test
    fun noWarningWhenNuvioIsNotInstalled() {
        val result = CertificatePreflight.check(listOf(TestCerts.identity()), installed = null, now = now)
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun unreadableExpiryCountsAsUsable() {
        val unreadable = TestCerts.identity().copy(password = "wrong")
        val result = CertificatePreflight.check(listOf(unreadable), installed = null, now = now)
        assertEquals(listOf(unreadable), result.usable)
    }

    @Test
    fun rejectedUpdateKeepsTheCause() {
        val cause = InstallerException("install failed[118012]")
        val e = CertificatePreflight.rejectedUpdate(installed, cause)
        assertEquals(cause, e.cause)
        assertTrue(e.message!!.startsWith("The TV refused the update: Nuvio TV 1.2.0"))
    }

    @Test
    fun missingVersionReadsCleanly() {
        val e = CertificatePreflight.missingIdentity(mapOf("app_id" to "NuvioTV001.NuvioTV"))
        assertTrue(e.message!!.startsWith("Nuvio TV is already installed"), e.message)
    }
}
