// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CertificateExpiryTest {
    // X.509 dates have one-second precision. One fixed "now", so two calls with the same n give
    // the same instant even when the clock ticks over a second between them.
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    private fun days(n: Long): Instant = now.plus(Duration.ofDays(n))

    @Test
    fun expiryIsTheEarlierOfAuthorAndDistributor() {
        val author = days(900)
        val distributor = days(400)
        assertEquals(distributor, TestCerts.identity(authorNotAfter = author, distributorNotAfter = distributor).expiresAt())
        assertEquals(days(100), TestCerts.identity(authorNotAfter = days(100), distributorNotAfter = days(400)).expiresAt())
    }

    @Test
    fun caCertificatesDoNotCount() {
        // The CAs expire first, but only the certificates issued for this user decide.
        val identity = TestCerts.identity(authorNotAfter = days(500), distributorNotAfter = days(600), caNotAfter = days(10))
        assertEquals(days(500), identity.expiresAt())
    }

    @Test
    fun unreadableIdentityHasNoExpiry() {
        val broken = TestCerts.identity().copy(password = "wrong")
        assertNull(broken.expiresAt())
    }

    @Test
    fun expiryStateThresholds() {
        val now = Instant.parse("2026-11-11T00:00:00Z")
        assertEquals(ExpiryState.VALID, CertificateExpiry.state(null, now))
        assertEquals(ExpiryState.VALID, CertificateExpiry.state(now.plus(Duration.ofDays(31)), now))
        assertEquals(ExpiryState.EXPIRES_SOON, CertificateExpiry.state(now.plus(Duration.ofDays(30)), now))
        assertEquals(ExpiryState.EXPIRES_SOON, CertificateExpiry.state(now.plusSeconds(1), now))
        assertEquals(ExpiryState.EXPIRED, CertificateExpiry.state(now, now))
        assertEquals(ExpiryState.EXPIRED, CertificateExpiry.state(now.minus(Duration.ofDays(1)), now))
    }

    @Test
    fun expiryIsNotWrittenToTheIdentityFile() {
        // The JSON stays compatible with the desktop installer's format.
        val json = TestCerts.identity().toJson()
        assertEquals(false, json.contains("expires", ignoreCase = true))
    }
}
