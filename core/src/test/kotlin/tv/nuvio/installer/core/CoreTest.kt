// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 pratyush997 and contributors

package tv.nuvio.installer.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private fun cert(subject: String, issuer: String, keys: java.security.KeyPair, signer: java.security.PrivateKey) =
        JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                X500Name("CN=$issuer"), BigInteger.valueOf(System.nanoTime()), Date(), Date(System.currentTimeMillis() + 86_400_000),
                X500Name("CN=$subject"), keys.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(signer))
        )

    @Test
    fun pkcs12RoundTripAndChainCheck() {
        val gen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val caKeys = gen.generateKeyPair()
        val leafKeys = gen.generateKeyPair()
        val ca = cert("CA", "CA", caKeys, caKeys.private)
        val leaf = cert("Leaf", "CA", leafKeys, caKeys.private)
        val p12 = Pkcs12.write(leafKeys.private, listOf(leaf, ca), "secret")
        val read = Pkcs12.read(p12, "secret")
        assertEquals(leafKeys.private.encoded.toList(), read.privateKey.encoded.toList())
        assertEquals(listOf(leaf, ca), read.chain)
        val b64 = Base64.getEncoder().encodeToString(p12)
        val identity = SigningIdentity(authorCert = b64, distributorCert = b64, password = "secret", duid = "X")
        assertTrue(identity.hasValidAuthorChain())
        assertEquals(identity, SigningIdentity.parse(identity.toJson()))
    }

    @Test
    fun loginCallbackParsing() {
        val server = SamsungLoginServer()
        val json = """{"access_token":"tok","userId":"u1","inputEmailID":"a@b.c"}"""
        val get = server.extractAccess("/signin/callback?code=" + java.net.URLEncoder.encode(json, "UTF-8"), "")
        assertEquals("tok", get?.accessToken); assertEquals("u1", get?.userId); assertEquals("a@b.c", get?.email)
        val post = server.extractAccess("/signin/callback", "code=" + java.net.URLEncoder.encode(json, "UTF-8"))
        assertEquals("tok", post?.accessToken)
        val plain = server.extractAccess("/signin/callback?access_token=t2&user_id=u2", "")
        assertEquals("t2", plain?.accessToken); assertEquals("u2", plain?.userId)
        assertNull(server.extractAccess("/favicon.ico", ""))
    }

    @Test
    fun appListAndVersions() {
        val sep = "---------------------------------------------------------------------------------------------"
        val output = "$sep\napp_tizen_id        = NuvioTV001.NuvioTV\napp_id      = NuvioTV001.NuvioTV\napp_version = 1.3.9\n$sep\napp_id = other.app\n$sep\n"
        val apps = TizenInstaller.parseAppList(output)
        assertEquals(2, apps.size)
        assertEquals("1.3.9", apps[0]["app_version"])
        assertEquals(-1, TizenInstaller.compareVersions("1.3.9", "1.3.10"))
        assertEquals(1, TizenInstaller.compareVersions("6.5", "2.3"))
        assertEquals(1, TizenInstaller.compareVersions("1.3.0", "v1.2.0"))
        assertEquals(0, TizenInstaller.compareVersions("v1.3.11", "1.3.11"))
        assertEquals(-1, TizenInstaller.compareVersions("1.2.0", "v1.3.0"))
        assertEquals(listOf(1, 3, 11), TizenInstaller.parseVersion("Nuvio TV v1.3.11 (pre-release)"))
        assertTrue(TizenInstaller.isCertificateRejection("install failed[118012]") && TizenInstaller.isCertificateRejection("install failed[118, -12]"))
        assertTrue(TizenInstaller.isPlatformIncompatibility("install failed[118019]"))
    }

    @Test
    fun tvStatusAndIpReport() {
        assertTrue(TvNetwork.isValidIpv4("192.168.1.9"))
        assertTrue(!TvNetwork.isValidIpv4("192.168.1.256") && !TvNetwork.isValidIpv4("192.168.1") && !TvNetwork.isValidIpv4("tv.local"))
        assertTrue(TvNetwork.sameSubnet("192.168.1.9", "192.168.1.200"))
        assertTrue(!TvNetwork.sameSubnet("192.168.1.9", "192.168.2.9"))
        assertTrue(TvNetwork.sameSubnet("192.168.1.9", "192.168.2.9", 16))

        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" +
            """{"device":{"developerIP":"192.168.1.11","developerMode":"1","modelName":"QA55Q60","name":"Living Room"},"name":"Living Room"}"""
        val status = TvNetwork.parseStatus(response)
        assertEquals(SamsungTvStatus("Living Room", "QA55Q60", true, "192.168.1.11"), status)
        assertNull(TvNetwork.parseStatus("HTTP/1.1 200 OK\r\n\r\n{\"device\":{\"na"))
        assertNull(TvNetwork.parseStatus("""{"device":{"developerIP":"0.0.0.0","developerMode":"0"}}""")?.developerIp)

        fun fix(phone: String?, tv: String, s: SamsungTvStatus?, connected: Boolean = false, lastGood: String? = null) =
            TvNetwork.report(phone, 24, tv, s, connected, "boom", lastGood)
        assertNull(fix("192.168.1.11", "192.168.1.9", status, connected = true).fix)
        assertTrue(fix("192.168.1.14", "192.168.1.9", status).fix!!.text.contains("Change Host PC IP from 192.168.1.11 to 192.168.1.14."))
        assertTrue(fix("192.168.1.11", "192.168.1.9", status).fix!!.reason!!.contains("has not applied them"))
        assertTrue(fix("192.168.1.11", "192.168.1.9", status!!.copy(developerMode = false)).fix!!.steps.contains("Switch Developer Mode on."))
        assertTrue(fix("192.168.1.11", "192.168.2.9", null).rows.last().text.contains("different network"))
        assertTrue(fix("192.168.1.11", "192.168.1.9", null).rows.last().text.contains("No Samsung TV answered"))
        assertTrue(fix("192.168.1.11", "192.168.1.11", null).rows.last().text.contains("this phone's IP"))
        assertTrue(fix("192.168.1.11", "abc", null).rows.last().text.contains("not a valid address"))
        assertTrue(fix(null, "192.168.1.9", null).fix!!.text.contains("same Wi-Fi"))
        val unknown = SamsungTvStatus("TV", "", null, null)
        assertTrue(fix("192.168.1.14", "192.168.1.9", unknown, lastGood = "192.168.1.11").fix!!.reason!!.contains("changed from 192.168.1.11 to 192.168.1.14"))
        assertTrue(fix("192.168.1.14", "192.168.1.9", unknown).fix!!.reason == "boom")

        // Real response shape from a 2023 Crystal UHD: the name is HTML-escaped.
        val crystal = TvNetwork.parseStatus("""{"device":{"name":"65&quot; Crystal UHD","modelName":"UA65CUE70AKLXL","developerMode":"1","developerIP":"192.168.1.8"}}""")
        assertEquals("65\" Crystal UHD", crystal?.name)
        assertEquals("Tom & Jerry's TV", TvNetwork.decodeEntities("Tom &amp; Jerry&#39;s TV"))

        // Emulators: never tell the user to type the emulator's 10.0.2.x address on the TV.
        assertTrue(TvNetwork.isEmulatorAddress("10.0.2.15") && !TvNetwork.isEmulatorAddress("10.0.20.15"))
        val emu = fix("10.0.2.15", "192.168.1.9", crystal)
        assertTrue(emu.fix!!.steps.contains("Change Host PC IP from 192.168.1.8 to your computer's Wi-Fi IP. Run ipconfig on the PC to find it."))
        assertEquals("1. On the TV, open Apps and press 1 2 3 4 5 on the remote.", emu.fix!!.text.lines()[1])
        assertTrue(emu.rows.none { it.state == CheckState.FAIL && it.text.contains("Host PC IP") })
        assertTrue(!fix("10.0.2.15", "192.168.1.9", null).rows.last().text.contains("different network"))
    }

    @Test
    fun jsCompatibleHelpers() {
        assertEquals("css%2Fa%20b.js", TizenSigner.encodeUriComponent("css/a b.js"))
        assertEquals("-_.!~*'()", TizenSigner.encodeUriComponent("-_.!~*'()"))
        assertEquals("a".repeat(76) + "\n", TizenSigner.wrap76("a".repeat(76)))
        assertNotNull(TizenSigner.wrap76(""))
    }
}
