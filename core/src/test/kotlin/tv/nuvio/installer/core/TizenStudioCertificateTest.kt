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
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPair
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TizenStudioCertificateTest {
    private var serial = 1000L
    private val authorCaKeys = TestCerts.keys()
    private val partnerCaKeys = TestCerts.keys()
    private val authorCa = cert("VD Tizen Dev Author CA", "VD Tizen Dev Author CA", authorCaKeys, authorCaKeys, ca = true)
    private val partnerCa = cert("VD Tizen Dev Partner CA", "VD Tizen Dev Partner CA", partnerCaKeys, partnerCaKeys, ca = true)

    private fun cert(subject: String, issuer: String, keys: KeyPair, signer: KeyPair, ca: Boolean = false, duids: List<String> = emptyList()): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"), BigInteger.valueOf(serial++),
            Date.from(Instant.now().minus(Duration.ofDays(1))), Date.from(Instant.now().plus(Duration.ofDays(3650))),
            X500Name("CN=$subject"), keys.public,
        )
        if (ca) builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        if (duids.isNotEmpty()) {
            val names = (listOf("URN:tizen:packageid=") + duids.map { "URN:tizen:deviceid=$it" })
                .map { GeneralName(GeneralName.uniformResourceIdentifier, it) }
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        }
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(signer.private)))
    }

    private fun author(password: String = "studio", includeCa: Boolean = true): ByteArray {
        val keys = TestCerts.keys()
        val leaf = cert("Nuvio", "VD Tizen Dev Author CA", keys, authorCaKeys)
        return Pkcs12.write(keys.private, if (includeCa) listOf(leaf, authorCa) else listOf(leaf), password)
    }

    private fun distributor(password: String = "studio", duids: List<String> = listOf("TV-A"), includeCa: Boolean = true): ByteArray {
        val keys = TestCerts.keys()
        val leaf = cert("TizenSDK", "VD Tizen Dev Partner CA", keys, partnerCaKeys, duids = duids)
        return Pkcs12.write(keys.private, if (includeCa) listOf(leaf, partnerCa) else listOf(leaf), password)
    }

    private fun store() = IdentityStore(Files.createTempDirectory("tizen-studio").toFile())

    @Test
    fun importsOneIdentityPerTv() {
        val store = store()
        val saved = TizenStudioCertificate.import(store, author(), distributor(duids = listOf("TV-A", "TV-B")), "studio")
        assertEquals(listOf("TV-A", "TV-B"), saved.map { it.duid })
        assertEquals(1, store.candidatesFor("TV-A").size)
        assertEquals(1, store.candidatesFor("TV-B").size)
        val identity = store.candidatesFor("TV-B").single()
        assertTrue(identity.hasValidAuthorChain())
        // Re-encoded with one fresh password, readable like any other saved identity.
        assertFalse(identity.password == "studio")
        identity.author(); identity.distributor()
    }

    @Test
    fun signsAPackageWithTheImportedCertificate() {
        val identity = TizenStudioCertificate.read(author(), distributor(), "studio").single()
        val dir = Files.createTempDirectory("tizen-studio-sign").toFile()
        val wgt = File(dir, "in.wgt")
        java.util.zip.ZipOutputStream(wgt.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("config.xml")); zip.write("<widget/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("index.html")); zip.write("<html/>".toByteArray()); zip.closeEntry()
        }
        val out = File(dir, "out.wgt")
        TizenSigner.signWgt(wgt, out, identity.author(), identity.distributor())
        val names = java.util.zip.ZipFile(out).use { z -> z.entries().toList().map { it.name } }
        assertTrue("author-signature.xml" in names && "signature1.xml" in names, names.toString())
    }

    @Test
    fun separateDistributorPassword() {
        val identities = TizenStudioCertificate.read(author("a-pass"), distributor("d-pass"), "a-pass", "d-pass")
        assertEquals("TV-A", identities.single().duid)
    }

    @Test
    fun asksForMissingOrWrongPasswords() {
        val missing = assertFailsWith<TizenStudioPasswordException> { TizenStudioCertificate.read(author(), distributor(), null) }
        assertEquals(TizenStudioCertificate.Part.AUTHOR, missing.file)
        assertFalse(missing.wrongPassword)

        val wrong = assertFailsWith<TizenStudioPasswordException> { TizenStudioCertificate.read(author(), distributor(), "nope") }
        assertEquals(TizenStudioCertificate.Part.AUTHOR, wrong.file)
        assertTrue(wrong.wrongPassword)

        val distributorWrong = assertFailsWith<TizenStudioPasswordException> {
            TizenStudioCertificate.read(author("a-pass"), distributor("d-pass"), "a-pass")
        }
        assertEquals(TizenStudioCertificate.Part.DISTRIBUTOR, distributorWrong.file)
    }

    @Test
    fun refusesADistributorWithoutTvs() {
        val e = assertFailsWith<InstallerException> { TizenStudioCertificate.read(author(), distributor(duids = emptyList()), "studio") }
        assertTrue(e.message!!.contains("does not list any TV"), e.message)
    }

    @Test
    fun noticesSwappedFiles() {
        val e = assertFailsWith<InstallerException> { TizenStudioCertificate.read(distributor(), distributor(), "studio") }
        assertTrue(e.message!!.contains("swapped"), e.message)
    }

    @Test
    fun addsTheMissingCaFromSamsungAuthorities() {
        var asked = 0
        val identity = TizenStudioCertificate.read(author(includeCa = false), distributor(includeCa = false), "studio") {
            asked++; listOf(authorCa, partnerCa)
        }.single()
        assertEquals(1, asked)
        assertTrue(identity.hasValidAuthorChain())
        assertEquals(2, identity.distributor().chain.size)
    }

    @Test
    fun authoritiesAreNotFetchedWhenTheFilesHaveThem() {
        TizenStudioCertificate.read(author(), distributor(), "studio") { error("should not be called") }
    }

    @Test
    fun refusesANonSamsungAuthor() {
        val e = assertFailsWith<InstallerException> {
            TizenStudioCertificate.read(author(includeCa = false), distributor(), "studio") { emptyList() }
        }
        assertTrue(e.message!!.contains("not issued by Samsung"), e.message)
    }

    @Test
    fun refusesFilesThatAreNotCertificates() {
        val e = assertFailsWith<InstallerException> { TizenStudioCertificate.read("hello".toByteArray(), distributor(), "studio") }
        assertTrue(e.message!!.contains("not a .p12"), e.message)
    }

    @Test
    fun tellsAuthorFromDistributorByName() {
        assertEquals(true, TizenStudioCertificate.authorFirst("author.p12", "distributor.p12"))
        assertEquals(false, TizenStudioCertificate.authorFirst("distributor.p12", "author.p12"))
        assertEquals(false, TizenStudioCertificate.authorFirst("x.p12", "author (1).p12"))
        assertNull(TizenStudioCertificate.authorFirst("a.p12", "b.p12"))
    }
}
