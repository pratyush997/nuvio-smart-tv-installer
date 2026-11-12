// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AutoUpdateTest {
    private val wgt = ReleaseAsset("Nuvio.wgt", 10, "https://example.invalid/Nuvio.wgt")
    private fun release(tag: String, assets: List<ReleaseAsset> = listOf(wgt)) = Release(1, tag, assets = assets)

    private fun plan(
        latest: Release? = release("v1.3.0"),
        enabled: Boolean = true,
        tvIp: String = "192.168.50.9",
        onWifi: Boolean = true,
        hasCertificate: Boolean = true,
        attempts: Int = 0,
    ) = AutoUpdate.plan(latest, enabled, tvIp, onWifi, hasCertificate, attempts)

    @Test
    fun installsANewerVersionWhenEverythingIsReady() {
        assertEquals(AutoUpdatePlan.Install(release("v1.3.0")), plan())
    }

    @Test
    fun nothingToDoWithoutAnUpdate() {
        // Whether a release is newer for the TV is ReleaseChannel.updateFor's job (see ReleaseChannelTest).
        assertEquals(AutoUpdatePlan.None, plan(latest = null))
    }

    @Test
    fun turnedOffOnlyNotifies() {
        assertEquals(AutoUpdatePlan.Notify(release("v1.3.0"), null), plan(enabled = false))
    }

    @Test
    fun saysWhyItDidNotInstall() {
        fun reason(p: AutoUpdatePlan) = assertIs<AutoUpdatePlan.Notify>(p).reason
        assertEquals("the phone was not on Wi-Fi", reason(plan(onWifi = false)))
        assertEquals("no TV is selected", reason(plan(tvIp = "")))
        assertEquals("this TV's certificate is not saved on the phone", reason(plan(hasCertificate = false)))
        assertEquals("the release has no Samsung package", reason(plan(latest = release("v1.3.0", emptyList()))))
        assertEquals("the TV could not be reached", reason(plan(attempts = AutoUpdate.MAX_ATTEMPTS)))
    }

    @Test
    fun keepsTryingBelowTheLimit() {
        assertIs<AutoUpdatePlan.Install>(plan(attempts = AutoUpdate.MAX_ATTEMPTS - 1))
    }

    @Test
    fun reusesAVerifiedDownload() {
        val dir = Files.createTempDirectory("release-package").toFile()
        val file = java.io.File(dir, "Nuvio.wgt").apply { writeBytes(ByteArray(10) { it.toByte() }) }
        val digest = "sha256:" + ReleasePackage.sha256(file)
        val lines = ArrayList<String>()
        val events = object : InstallerEvents { override fun log(level: LogLevel, text: String) { lines += text } }
        // The URL is unreachable: the existing file must be used without downloading.
        val result = ReleasePackage.fetch(wgt.copy(digest = digest), dir, events)
        assertEquals(file, result)
        assertEquals(listOf("Using downloaded Nuvio.wgt."), lines)
    }

    @Test
    fun knownDigest() {
        val file = Files.createTempFile("digest", ".bin").toFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ReleasePackage.sha256(file))
    }
}
