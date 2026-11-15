// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Installing any picked package: what is read from it, what is refused, and how it is signed. */
class TizenPackageTest {
    private object NoEvents : InstallerEvents {
        override fun log(level: LogLevel, text: String) = Unit
    }

    private fun zip(entries: Map<String, String>): File {
        val file = File.createTempFile("pkg", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) -> out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry() }
        }
        return file
    }

    private fun config(pkg: String, id: String, version: String, name: String = "Demo", required: String = "2.3") =
        """<?xml version="1.0" encoding="UTF-8"?>
        <widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" id="http://x" version="$version">
          <tizen:application id="$id" package="$pkg" required_version="$required"/>
          <content src="index.html"/>
          <name xml:lang="de">Demo DE</name>
          <name>$name</name>
        </widget>"""

    private fun wgt(pkg: String = "AbCdEf1234", id: String = "$pkg.Demo", version: String = "1.0.0", vararg extra: Pair<String, String>) =
        zip(mapOf("config.xml" to config(pkg, id, version), "index.html" to "<html/>") + extra)

    private val manifest = """<?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns="http://tizen.org/ns/packages" api-version="6.0" package="org.example.player" version="2.1.0">
          <ui-application appid="org.example.player" exec="player.dll" type="dotnet">
            <label>  Example
              Player </label>
          </ui-application>
        </manifest>"""

    // ---------------------------------------------------------------- inspect

    @Test
    fun inspectsAWgt() {
        val info = TizenPackage.inspect(wgt())
        assertEquals(PackageKind.WGT, info.kind)
        assertEquals(PackageMetadata("AbCdEf1234", "AbCdEf1234.Demo", "1.0.0", "2.3"), info.metadata)
        assertEquals("Demo", info.name)
        assertEquals("Demo", info.label)
        assertNull(info.known)
        assertTrue(info.size > 0)
    }

    @Test
    fun inspectsATpk() {
        val info = TizenPackage.inspect(zip(mapOf("tizen-manifest.xml" to manifest, "bin/player.dll" to "x")))
        assertEquals(PackageKind.TPK, info.kind)
        assertEquals("org.example.player", info.metadata.packageId)
        assertEquals("6.0", info.metadata.requiredVersion)
        assertEquals("Example Player", info.name)
    }

    @Test
    fun anAppWithoutANameIsCalledByItsPackageId() {
        val noName = """<?xml version="1.0"?><widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="1.0">
            <tizen:application id="AbCdEf1234.Demo" package="AbCdEf1234"/></widget>"""
        val info = TizenPackage.inspect(zip(mapOf("config.xml" to noName)))
        assertEquals("", info.name)
        assertEquals("AbCdEf1234", info.label)
    }

    @Test
    fun recognisesTheNuvioApps() {
        assertEquals(NuvioApp.TV, TizenPackage.inspect(wgt("NuvioTV001", "NuvioTV001.NuvioTV")).known)
        assertEquals(NuvioApp.LEGACY, TizenPackage.inspect(wgt("NuvioTV002", "NuvioTV002.NuvioNative")).known)
        val legacyTpk = manifest.replace("org.example.player", "NuvioTV002.Nuvio")
        assertEquals(NuvioApp.LEGACY, TizenPackage.inspect(zip(mapOf("tizen-manifest.xml" to legacyTpk))).known)
        // "Nuvio" is one of Nuvio TV's loose ids; a .tpk with it is not Nuvio TV.
        val loose = manifest.replace("org.example.player", "Nuvio")
        assertNull(TizenPackage.inspect(zip(mapOf("tizen-manifest.xml" to loose))).known)
    }

    @Test
    fun aFileThatIsNotAnAppSaysSo() {
        val e = assertFailsWith<InstallerException> { TizenPackage.inspect(zip(mapOf("readme.txt" to "hi"))) }
        assertTrue(e.message!!.contains("not a Samsung TV app"), e.message)
        val notZip = File.createTempFile("pkg", ".wgt").apply { writeText("hello"); deleteOnExit() }
        assertFailsWith<InstallerException> { TizenPackage.inspect(notZip) }
    }

    // ---------------------------------------------------------------- issue 1 and 6: ids and versions are checked

    @Test
    fun refusesIdsThatCouldRunCommandsOnTheTv() {
        val evil = wgt("../../../evil", "x; reboot #", "../../9")
        assertFailsWith<InstallerException> { TizenPackage.inspect(evil) }
        assertFailsWith<InstallerException> { TizenPackage.read(evil, NuvioApp.LEGACY, NoEvents) }
        assertFailsWith<InstallerException> { TizenPackage.inspect(wgt("AbCdEf1234", "AbCdEf1234.x;reboot")) }
        assertFailsWith<InstallerException> { TizenPackage.inspect(wgt("AbCdEf1234", "Other12345.Demo")) }
        val tpk = manifest.replace("appid=\"org.example.player\"", "appid=\"a b\"")
        assertFailsWith<InstallerException> { TizenPackage.inspect(zip(mapOf("tizen-manifest.xml" to tpk))) }
    }

    @Test
    fun refusesVersionsThatAreNotNumbers() {
        // Issue 2's path came from the version.
        val e = assertFailsWith<InstallerException> { TizenPackage.inspect(wgt(version = "../../9")) }
        assertTrue(e.message!!.contains("unusual version"), e.message)
        assertFailsWith<InstallerException> { TizenPackage.inspect(wgt(version = "1.0.0-beta")) }
        val tpk = manifest.replace("version=\"2.1.0\"", "version=\"2.1;x\"")
        assertFailsWith<InstallerException> { TizenPackage.inspect(zip(mapOf("tizen-manifest.xml" to tpk))) }
        listOf("1", "1.0", "1.2.3", "1.2.3.4", "").forEach { TizenPackage.inspect(wgt(version = it)) }
    }

    // ---------------------------------------------------------------- the Nuvio cards take only their own files

    @Test
    fun theNuvioCardsRefuseOtherApps() {
        val other = wgt()
        val legacy = assertFailsWith<InstallerException> { TizenPackage.read(other, NuvioApp.LEGACY, NoEvents) }
        assertTrue(legacy.message!!.contains("not Nuvio Legacy"), legacy.message)
        val tv = assertFailsWith<InstallerException> { TizenPackage.read(other, NuvioApp.TV, NoEvents) }
        assertTrue(tv.message!!.contains("not Nuvio TV"), tv.message)
    }

    // ---------------------------------------------------------------- issue 3: every old signature is removed

    @Test
    fun signingRemovesEveryOldSignature() {
        val presigned = wgt(
            extra = arrayOf(
                "author-signature.xml" to "old", "signature1.xml" to "old", "signature2.xml" to "old", "signature12.xml" to "old",
                "res/signature3.xml" to "kept: not at the top",
            ),
        )
        val id = TestCerts.identity()
        val out = File.createTempFile("signed", ".wgt").apply { deleteOnExit() }
        TizenSigner.signWgt(presigned, out, id.author(), id.distributor())
        ZipFile(out).use { z ->
            val files = z.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            assertEquals(listOf("signature1.xml", "author-signature.xml", "config.xml", "index.html", "res/signature3.xml"), files)
            assertNotEquals("old", z.getInputStream(z.getEntry("signature1.xml")).readBytes().decodeToString())
        }
    }

    /** The signer reads files as streams now; what it writes must be what the in-memory version wrote. */
    @Test
    fun streamingSignerMatchesTheInMemoryOne() {
        val big = (1..50_000).joinToString("\n") { "line $it" }
        val input = wgt(extra = arrayOf("js/app.js" to big, "js/lib/a b.js" to "x", "img/logo.png" to "png"))
        val id = TestCerts.identity()
        val out = File.createTempFile("signed", ".wgt").apply { deleteOnExit() }
        TizenSigner.signWgt(input, out, id.author(), id.distributor())

        // The in-memory signer, as it was before streaming.
        val files = ZipFile(input).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.map { TizenSigner.Entry(it.name, zip.getInputStream(it).readBytes()) }.toList()
        }
        val authorXml = TizenSigner.signatureXml("AuthorSignature", files, id.author())
        val withAuthor = listOf(TizenSigner.Entry(TizenSigner.AUTHOR_SIGNATURE, authorXml)) + files
        val distributorXml = TizenSigner.signatureXml("DistributorSignature", withAuthor, id.distributor())
        val expected = listOf(TizenSigner.Entry(TizenSigner.DISTRIBUTOR_SIGNATURE, distributorXml)) + withAuthor

        ZipFile(out).use { z ->
            val actual = z.entries().asSequence().filter { !it.isDirectory }.map { it.name to z.getInputStream(it).readBytes().toList() }.toList()
            assertEquals(expected.map { it.name to it.data.toList() }, actual)
            val folders = z.entries().asSequence().filter { it.isDirectory }.map { it.name }.toList()
            assertEquals(listOf("js/", "js/lib/", "img/"), folders)
        }
    }

    // ---------------------------------------------------------------- targets

    @Test
    fun aCustomAppIsFoundOnTheTvByItsIds() {
        val info = TizenPackage.inspect(wgt())
        val target = InstallTarget.Custom(info)
        val list = listOf(
            mapOf("app_id" to "NuvioTV001.NuvioTV", "app_version" to "1.0.0"),
            mapOf("app_tizen_id" to "AbCdEf1234.Demo", "app_version" to "1.0.0"),
        )
        assertEquals("AbCdEf1234.Demo", target.find(list)?.get("app_tizen_id"))
        assertEquals("AbCdEf1234.Demo", TvInfo("D", "6.0", list = list).installed(target)?.get("app_tizen_id"))
        assertNull(TvInfo("D", "6.0", list = list.take(1)).installed(target))
        assertEquals("Demo", target.label)
        assertTrue(!target.isNuvioTv)
    }

    @Test
    fun certificateMessagesForACustomAppNameIt() {
        val target = InstallTarget.Custom(TizenPackage.inspect(wgt()))
        val installed = mapOf("app_id" to "AbCdEf1234.Demo", "app_version" to "1.0.0")
        val blocked = CertificatePreflight.check(emptyList(), installed, target).blocked!!
        assertTrue(blocked.message!!.startsWith("Demo 1.0.0 is already installed"), blocked.message)
        assertTrue(blocked.message!!.contains("uninstall Demo from the TV first"), blocked.message)
        assertTrue(!blocked.message!!.contains("desktop"), blocked.message)
        val rejected = CertificatePreflight.rejectedUpdate(installed, InstallerException("x"), target)
        assertTrue(rejected.message!!.contains("uninstall Demo from the TV"), rejected.message)
    }

    @Test
    fun theFileRowComparesTheTizenVersion() {
        val info = TizenPackage.inspect(zip(mapOf("tizen-manifest.xml" to manifest)))
        assertEquals(ReadyResult(ReadyCheck.PACKAGE, ReadyState.PASS, "Example Player 2.1.0"), InstallReadiness.customPackageRow(info, "6.5"))
        assertEquals(ReadyState.PASS, InstallReadiness.customPackageRow(info, null).state)
        val old = InstallReadiness.customPackageRow(info, "5.5")
        assertEquals(ReadyState.FAIL, old.state)
        assertEquals("Example Player needs Tizen 6.0 or newer. This TV has Tizen 5.5.", old.detail)
        assertEquals("File works on this TV", ReadyCheck.PACKAGE.title(InstallTarget.Custom(info)))
        assertEquals("Nuvio TV file for this TV", ReadyCheck.PACKAGE.title(NuvioApp.TV))
    }
}
