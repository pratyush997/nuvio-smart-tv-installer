// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedProfileTest {
    private var serial = 5000L
    private val caKeys = TestCerts.keys()
    private val authorCa = cert("Author CA", "Author CA", caKeys, caKeys, ca = true)
    private val partnerCa = cert("Partner CA", "Partner CA", caKeys, caKeys, ca = true)

    private fun cert(
        subject: String, issuer: String, keys: KeyPair, signer: KeyPair, ca: Boolean = false,
        duids: List<String> = emptyList(), notAfter: Instant = Instant.now().plus(Duration.ofDays(365)),
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"), BigInteger.valueOf(serial++),
            Date.from(Instant.now().minus(Duration.ofDays(1))), Date.from(notAfter), X500Name("CN=$subject"), keys.public,
        )
        if (ca) builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        if (duids.isNotEmpty()) {
            val names = (listOf("URN:tizen:packageid=") + duids.map { "URN:tizen:deviceid=$it" }).map { GeneralName(GeneralName.uniformResourceIdentifier, it) }
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        }
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(signer.private)))
    }

    private fun author(notAfter: Instant = Instant.now().plus(Duration.ofDays(365))): String {
        val keys = TestCerts.keys()
        return Base64.getEncoder().encodeToString(Pkcs12.write(keys.private, listOf(cert("Nuvio", "Author CA", keys, caKeys, notAfter = notAfter), authorCa), "pw"))
    }

    private fun distributor(duids: List<String>): String {
        val keys = TestCerts.keys()
        return Base64.getEncoder().encodeToString(Pkcs12.write(keys.private, listOf(cert("TizenSDK", "Partner CA", keys, caKeys, duids = duids), partnerCa), "pw"))
    }

    private fun identity(authorCert: String, duid: String, listed: List<String> = listOf(duid), lastInstall: String? = null) =
        SigningIdentity(authorCert, distributor(listed), password = "pw", duid = duid, createdAt = "2026-10-10T00:00:00Z", lastSuccessfulInstallAt = lastInstall)

    @Test
    fun firstTvGetsANewProfile() {
        assertEquals(SharedProfile.Plan(null, listOf("TV-A")), SharedProfile.plan(emptyList(), "TV-A"))
    }

    @Test
    fun secondTvReusesTheAuthorAndListsBothTvs() {
        val a = identity(author(), "TV-A", lastInstall = "2026-11-01T00:00:00Z")
        val plan = SharedProfile.plan(listOf(a), "TV-B")
        assertSame(a, plan.base)
        assertEquals(listOf("TV-B", "TV-A"), plan.duids)
    }

    @Test
    fun keepsEveryTvTheDistributorAlreadyListed() {
        val shared = author()
        val a = identity(shared, "TV-A", listed = listOf("TV-A", "TV-B"))
        val b = identity(shared, "TV-B", listed = listOf("TV-A", "TV-B"))
        assertEquals(listOf("TV-C", "TV-A", "TV-B"), SharedProfile.plan(listOf(a, b), "TV-C").duids)
    }

    @Test
    fun atMostTenTvsMostRecentlyUsedFirst() {
        val shared = author()
        val saved = (1..12).map { identity(shared, "TV-$it", lastInstall = "2026-11-%02dT00:00:00Z".format(it)) }
        val duids = SharedProfile.plan(saved, "NEW").duids
        assertEquals(SharedProfile.MAX_TVS, duids.size)
        assertEquals(listOf("NEW", "TV-12", "TV-11"), duids.take(3))
        assertTrue("TV-1" !in duids && "TV-2" !in duids && "TV-3" !in duids)
    }

    @Test
    fun anAuthorAboutToExpireIsNotShared() {
        val soon = identity(author(notAfter = Instant.now().plus(Duration.ofDays(10))), "TV-A")
        assertNull(SharedProfile.plan(listOf(soon), "TV-B").base)
        val unusable = identity(author(), "TV-A").copy(profileName = "Some Tizen profile")
        assertNull(SharedProfile.plan(listOf(unusable), "TV-B").base)
    }

    @Test
    fun theMostRecentlyUsedAuthorWins() {
        val old = identity(author(), "TV-A", lastInstall = "2026-01-01T00:00:00Z")
        val recent = identity(author(), "TV-B", lastInstall = "2026-11-01T00:00:00Z")
        val plan = SharedProfile.plan(listOf(old, recent), "TV-C")
        assertSame(recent, plan.base)
        assertEquals(listOf("TV-C", "TV-B"), plan.duids)
    }

    @Test
    fun newDistributorKeepsTheAuthorByteIdentical() {
        val a = identity(author(), "TV-A", lastInstall = "2026-11-01T00:00:00Z")
        val keys = TestCerts.keys()
        val issued = cert("TizenSDK", "Partner CA", keys, caKeys, duids = listOf("TV-B", "TV-A"))
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(issued.encoded) + "\n-----END CERTIFICATE-----\n"
        val made = SamsungCertificates.assembleDistributor(a, keys, pem, "TV-B", partnerCa, expectedDuids = listOf("TV-B", "TV-A"))
        assertEquals(a.authorCert, made.authorCert)
        assertEquals(a.fingerprint, made.fingerprint)
        assertEquals(a.password, made.password)
        assertEquals("TV-B", made.duid)
        assertNull(made.lastSuccessfulInstallAt)
        assertEquals(listOf("TV-B", "TV-A"), SharedProfile.duidsOf(made))
        assertTrue(made.hasValidAuthorChain())

        assertFailsWith<InstallerException> {
            SamsungCertificates.assembleDistributor(a, keys, pem, "TV-B", partnerCa, expectedDuids = listOf("TV-B", "TV-A", "TV-C"))
        }
    }

    @Test
    fun savesEveryTvButNeverOverwritesAnotherAuthor() {
        val shared = author()
        val a = identity(shared, "TV-A", lastInstall = "2026-11-01T00:00:00Z")
        val other = identity(author(), "TV-X")
        val made = identity(shared, "TV-B", listed = listOf("TV-B", "TV-A", "TV-X"))
        val current = mapOf("TV-A" to a, "TV-X" to other)
        val saved = SharedProfile.identitiesToSave(made, "TV-B", listOf("TV-B", "TV-A", "TV-X")) { current[it] }
        assertEquals(listOf("TV-B", "TV-A"), saved.map { it.duid })
        assertEquals("2026-11-01T00:00:00Z", saved[1].lastSuccessfulInstallAt)
        assertTrue(saved.all { it.distributorCert == made.distributorCert })
    }

    @Test
    fun theRequestedTvIsAlwaysSaved() {
        // An old, unusable certificate of another author for the same TV is replaced.
        val made = identity(author(), "TV-B")
        val stale = identity(author(), "TV-B")
        assertEquals(listOf("TV-B"), SharedProfile.identitiesToSave(made, "TV-B", listOf("TV-B")) { stale }.map { it.duid })
    }

    @Test
    fun storeRoundTripForSeveralTvs() {
        val store = IdentityStore(Files.createTempDirectory("shared-profile").toFile())
        val shared = author()
        val made = identity(shared, "TV-A", listed = listOf("TV-A", "TV-B"))
        SharedProfile.identitiesToSave(made, "TV-A", listOf("TV-A", "TV-B")) { store.candidatesFor(it).firstOrNull() }.forEach(store::save)
        assertEquals(made.fingerprint, store.candidatesFor("TV-A").single().fingerprint)
        assertEquals(made.fingerprint, store.candidatesFor("TV-B").single().fingerprint)
    }
}
