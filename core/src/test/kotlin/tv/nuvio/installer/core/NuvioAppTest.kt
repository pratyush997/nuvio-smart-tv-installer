// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NuvioAppTest {
    private fun asset(name: String) = ReleaseAsset(name, browser_download_url = "https://example.invalid/$name")

    /** The files of a real Legacy release (v2.0.8). */
    private val legacy = Release(
        1, "v2.0.8", name = "Nuvio Legacy 2.0.8",
        assets = listOf(
            "libnuvio-2.0.8-tpk-arm.so", "Nuvio-2.0.8-android.apk", "Nuvio-2.0.8-NuvioTpk.tpk", "Nuvio-2.0.8-NuvioTpk40.tpk",
            "Nuvio-2.0.8-NuvioTpk60.tpk", "Nuvio-2.0.8-NuvioTpk65.tpk", "NuvioTV-2.0.8-tizen.wgt", "SHA256SUMS",
            "space.nuvio.native.legacy_2.0.8_arm.ipk",
        ).map(::asset),
    )

    private fun picked(release: Release, tizen: String?): String? =
        (NuvioApp.LEGACY.packageFor(release, tizen) as? PackagePick.Found)?.asset?.name

    @Test
    fun picksTheLegacyTpkForEachTizenGeneration() {
        assertEquals("Nuvio-2.0.8-NuvioTpk40.tpk", picked(legacy, "4.0"))
        assertEquals("Nuvio-2.0.8-NuvioTpk40.tpk", picked(legacy, "5.5"))
        assertEquals("Nuvio-2.0.8-NuvioTpk60.tpk", picked(legacy, "6.0"))
        assertEquals("Nuvio-2.0.8-NuvioTpk65.tpk", picked(legacy, "6.5"))
        assertEquals("Nuvio-2.0.8-NuvioTpk65.tpk", picked(legacy, "7.0"))
        assertEquals("Nuvio-2.0.8-NuvioTpk.tpk", picked(legacy, "8.0"))
        assertEquals("Nuvio-2.0.8-NuvioTpk.tpk", picked(legacy, "9.0"))
    }

    @Test
    fun legacyNeedsAKnownTizenFourOrLater() {
        assertIs<PackagePick.Missing>(NuvioApp.LEGACY.packageFor(legacy, null))
        assertIs<PackagePick.Missing>(NuvioApp.LEGACY.packageFor(legacy, ""))
        assertIs<PackagePick.Missing>(NuvioApp.LEGACY.packageFor(legacy, "3.0"))
        assertFalse(NuvioApp.LEGACY.supports("3.0"))
        assertTrue(NuvioApp.LEGACY.supports("4.0"))
        assertTrue(NuvioApp.LEGACY.supports(null))
    }

    @Test
    fun fallsBackToTheLegacyWgtOnlyFromTizenFiveFive() {
        val no65 = legacy.copy(assets = legacy.assets.filterNot { it.name.endsWith("NuvioTpk65.tpk") || it.name.endsWith("NuvioTpk40.tpk") })
        assertEquals("NuvioTV-2.0.8-tizen.wgt", picked(no65, "7.0"))
        assertIs<PackagePick.Missing>(NuvioApp.LEGACY.packageFor(no65, "5.0"))
    }

    @Test
    fun nuvioTvUsesItsWgtOnEveryTv() {
        val release = Release(2, "1.3.11", assets = listOf(asset("NuvioTV-Tizen-1.3.11.wgt"), asset("NuvioTV-webOS-1.3.11.ipk")))
        val pick = NuvioApp.TV.packageFor(release, null)
        assertEquals("NuvioTV-Tizen-1.3.11.wgt", (pick as PackagePick.Found).asset.name)
    }

    @Test
    fun legacyHidesTestBuildsAndReleasesWithoutATpk() {
        assertTrue(NuvioApp.LEGACY.offers(legacy))
        assertFalse(NuvioApp.LEGACY.offers(legacy.copy(prerelease = true)))
        assertFalse(NuvioApp.LEGACY.offers(Release(3, "teste-318.1", assets = listOf(asset("x.ipk")))))
        assertTrue(NuvioApp.TV.offers(Release(4, "1.4.0-beta.1", prerelease = true, assets = listOf(asset("a.wgt")))))
    }

    @Test
    fun findsEachAppInTheTvAppList() {
        val tv = mapOf("app_tizen_id" to "NuvioTV001.NuvioTV", "app_version" to "1.3.11")
        val legacyTpk = mapOf("app_tizen_id" to "NuvioTV002.Nuvio", "app_id" to "Nuvio", "app_version" to "1.5.4")
        val other = mapOf("app_tizen_id" to "Other.App")
        assertEquals(tv, NuvioApp.find(listOf(other, legacyTpk, tv), NuvioApp.TV))
        assertEquals(legacyTpk, NuvioApp.find(listOf(other, legacyTpk, tv), NuvioApp.LEGACY))
        // Legacy alone is never taken for Nuvio TV, even under the loose id "Nuvio".
        assertNull(NuvioApp.find(listOf(legacyTpk), NuvioApp.TV))
        assertEquals(mapOf("package_id" to "NuvioTV002"), NuvioApp.find(listOf(mapOf("package_id" to "NuvioTV002")), NuvioApp.LEGACY))
    }

    @Test
    fun olderTvsAreTizenFourAndFive() {
        assertTrue(NuvioApp.isOlderTv("4.0"))
        assertTrue(NuvioApp.isOlderTv("5.5"))
        assertFalse(NuvioApp.isOlderTv("6.0"))
        assertFalse(NuvioApp.isOlderTv(null))
    }

    // ---------------------------------------------------------------- packages

    private val legacyManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns="http://tizen.org/ns/packages" api-version="6.5" package="NuvioTV002.Nuvio" version="1.5.1">
          <profile name="tv" />
          <ui-application appid="NuvioTV002.Nuvio" exec="NuvioTpk65.dll" type="dotnet" multiple="false">
            <label>Nuvio Legacy</label>
          </ui-application>
        </manifest>
    """.trimIndent()

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("pkg", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun readsATpkManifest() {
        val metadata = TizenPackage.tpkMetadataFromManifest(legacyManifest)
        assertEquals(PackageMetadata("NuvioTV002.Nuvio", "NuvioTV002.Nuvio", "1.5.1", "6.5"), metadata)
    }

    @Test
    fun tellsTheKindFromTheContentsNotTheName() {
        val tpk = zip("tizen-manifest.xml" to legacyManifest, "bin/NuvioTpk65.dll" to "x")
        assertEquals(PackageKind.TPK, TizenPackage.kind(tpk))
        val (kind, metadata) = TizenPackage.read(tpk, NuvioApp.LEGACY, NoEvents)
        assertEquals(PackageKind.TPK, kind)
        assertEquals("NuvioTV002.Nuvio", metadata.packageId)
        assertFailsWith<InstallerException> { TizenPackage.kind(zip("readme.txt" to "hi")) }
    }

    @Test
    fun refusesLegacyWhileNuvioTvIsSelected() {
        val tpk = zip("tizen-manifest.xml" to legacyManifest)
        val e = assertFailsWith<InstallerException> { TizenPackage.read(tpk, NuvioApp.TV, NoEvents) }
        assertTrue(e.message!!.contains("Choose Nuvio Legacy first"))
    }

    @Test
    fun aLegacyWgtWhileNuvioTvIsSelectedSaysSoInsteadOfListingMissingServices() {
        val config = """
            <?xml version="1.0" encoding="UTF-8"?>
            <widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" version="2.0.8">
              <tizen:application id="NuvioTV002.NuvioNative" package="NuvioTV002" required_version="5.5"/>
            </widget>
        """.trimIndent()
        val wgt = zip("config.xml" to config, "index.html" to "<html></html>")
        val e = assertFailsWith<InstallerException> { TizenPackage.read(wgt, NuvioApp.TV, NoEvents) }
        assertTrue(e.message!!.contains("Choose Nuvio Legacy first"), e.message)
        val (kind, metadata) = TizenPackage.read(wgt, NuvioApp.LEGACY, NoEvents)
        assertEquals(PackageKind.WGT, kind)
        assertEquals("NuvioTV002.NuvioNative", metadata.appId)
    }

    private object NoEvents : InstallerEvents {
        override fun log(level: LogLevel, text: String) = Unit
    }
}
