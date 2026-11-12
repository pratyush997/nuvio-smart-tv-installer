// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class InstallReadinessTest {
    private val phone = "192.168.50.8"
    private fun status(mode: Boolean?, hostIp: String?) = SamsungTvStatus("Living Room", "UA65", mode, hostIp)
    private fun states(rows: List<ReadyResult>) = rows.map { it.check to it.state }

    @Test
    fun connectedPassesEveryTvRow() {
        val rows = InstallReadiness.tvRows(status(null, null), phone, connected = true)
        assertEquals(
            listOf(
                ReadyCheck.TV to ReadyState.PASS,
                ReadyCheck.DEVELOPER_MODE to ReadyState.PASS,
                ReadyCheck.HOST_IP to ReadyState.PASS,
                ReadyCheck.CONNECTION to ReadyState.PASS,
            ),
            states(rows),
        )
        assertEquals("Living Room", rows.first().detail)
    }

    @Test
    fun noAnswerStopsAtTheTv() {
        assertEquals(listOf(ReadyCheck.TV to ReadyState.FAIL), states(InstallReadiness.tvRows(null, phone, connected = false)))
    }

    @Test
    fun developerModeOffStopsThere() {
        assertEquals(
            listOf(ReadyCheck.TV to ReadyState.PASS, ReadyCheck.DEVELOPER_MODE to ReadyState.FAIL),
            states(InstallReadiness.tvRows(status(false, null), phone, connected = false)),
        )
    }

    @Test
    fun wrongHostIpStopsThereAndNamesBothAddresses() {
        val rows = InstallReadiness.tvRows(status(true, "192.168.50.11"), phone, connected = false)
        assertEquals(
            listOf(ReadyCheck.TV to ReadyState.PASS, ReadyCheck.DEVELOPER_MODE to ReadyState.PASS, ReadyCheck.HOST_IP to ReadyState.FAIL),
            states(rows),
        )
        assertEquals("The TV has 192.168.50.11, this phone is $phone", rows.last().detail)
    }

    @Test
    fun rightSettingsButNoConnectionFailsAtTheConnection() {
        assertEquals(
            listOf(
                ReadyCheck.TV to ReadyState.PASS,
                ReadyCheck.DEVELOPER_MODE to ReadyState.PASS,
                ReadyCheck.HOST_IP to ReadyState.PASS,
                ReadyCheck.CONNECTION to ReadyState.FAIL,
            ),
            states(InstallReadiness.tvRows(status(true, phone), phone, connected = false)),
        )
    }

    @Test
    fun silentTvIsUnsureUntilTheConnectionDecides() {
        assertEquals(
            listOf(
                ReadyCheck.TV to ReadyState.PASS,
                ReadyCheck.DEVELOPER_MODE to ReadyState.UNSURE,
                ReadyCheck.HOST_IP to ReadyState.UNSURE,
                ReadyCheck.CONNECTION to ReadyState.FAIL,
            ),
            states(InstallReadiness.tvRows(status(null, null), phone, connected = false)),
        )
    }

    @Test
    fun emulatorHostIpIsUnsure() {
        val rows = InstallReadiness.tvRows(status(true, "192.168.50.11"), "10.0.2.15", connected = false)
        assertEquals(ReadyCheck.HOST_IP to ReadyState.UNSURE, states(rows)[2])
    }

    @Test
    fun signInRowFollowsThePreflight() {
        assertEquals(ReadyState.LATER, InstallReadiness.signInRow(Preflight(emptyList(), emptyList(), null), NuvioApp.TV).state)
        assertEquals(
            ReadyState.FAIL,
            InstallReadiness.signInRow(Preflight(emptyList(), emptyList(), MissingIdentityException("x")), NuvioApp.LEGACY).state,
        )
        assertEquals(
            ReadyState.PASS,
            InstallReadiness.signInRow(Preflight(listOf(TestCerts.identity()), emptyList(), null), NuvioApp.TV).state,
        )
    }

    @Test
    fun wifiRowNeedsAPhoneAddress() {
        assertEquals(ReadyState.FAIL, InstallReadiness.wifiRow(null).state)
        assertEquals(ReadyState.FAIL, InstallReadiness.wifiRow("").state)
        assertEquals(ReadyState.PASS, InstallReadiness.wifiRow(phone).state)
    }

    // --- Row 6: the file for this TV ---

    private fun asset(name: String) = ReleaseAsset(name, browser_download_url = "https://example.invalid/$name")
    private val legacy = Release(
        1, "v2.0.8",
        assets = listOf("Nuvio-2.0.8-NuvioTpk.tpk", "Nuvio-2.0.8-NuvioTpk40.tpk", "Nuvio-2.0.8-NuvioTpk60.tpk", "Nuvio-2.0.8-NuvioTpk65.tpk", "NuvioTV-2.0.8-tizen.wgt").map(::asset),
    )
    private val nuvioTv = Release(2, "v1.4.0", assets = listOf(asset("NuvioTV-1.4.0.wgt")))

    @Test
    fun legacyNeedsTizen4AndGetsTheBuildForTheTv() {
        assertEquals(ReadyState.FAIL, InstallReadiness.packageRow(NuvioApp.LEGACY, legacy, isFile = false, tizen = "3.0").state)
        val row = InstallReadiness.packageRow(NuvioApp.LEGACY, legacy, isFile = false, tizen = "6.5")
        assertEquals(ReadyState.PASS, row.state)
        assertEquals("Nuvio-2.0.8-NuvioTpk65.tpk", row.detail)
    }

    @Test
    fun nuvioTvPassesOnAnyTizen() {
        for (tizen in listOf("3.0", "6.5", "9.0", null)) {
            assertEquals(ReadyState.PASS, InstallReadiness.packageRow(NuvioApp.TV, nuvioTv, isFile = false, tizen = tizen).state, "Tizen $tizen")
        }
    }

    @Test
    fun aPickedFilePassesAndAMissingReleaseFails() {
        assertEquals(ReadyState.PASS, InstallReadiness.packageRow(NuvioApp.LEGACY, null, isFile = true, tizen = "3.0").state)
        val failed = InstallReadiness.packageRow(NuvioApp.TV, null, isFile = false, tizen = "7.0", loadError = "offline")
        assertEquals(ReadyState.FAIL, failed.state)
        assertEquals("offline", failed.detail)
    }

    @Test
    fun packageTitleNamesTheApp() {
        assertEquals("Nuvio Legacy file for this TV", ReadyCheck.PACKAGE.title(NuvioApp.LEGACY))
    }

    // --- The highlighted button ---

    private val none = MainAction.AppState(installed = false)
    private val installed = MainAction.AppState(installed = true)
    private val outdated = MainAction.AppState(installed = true, updateAvailable = true)
    private fun pick(
        tv: MainAction.AppState,
        legacyApp: MainAction.AppState,
        hasTv: Boolean = true,
        online: Boolean = true,
        failed: NuvioApp? = null,
        onWifi: Boolean = true,
    ) = MainAction.pick(hasTv, online, mapOf(NuvioApp.TV to tv, NuvioApp.LEGACY to legacyApp), failed, onWifi)

    @Test
    fun highlightFollowsTheOrder() {
        assertEquals(MainAction.ConnectWifi, pick(outdated, installed, hasTv = false, online = false, onWifi = false))
        assertEquals(MainAction.FindTv, pick(outdated, installed, hasTv = false, online = false))
        assertEquals(MainAction.CheckAgain(null), pick(outdated, installed, online = false))
        assertEquals(MainAction.CheckAgain(NuvioApp.LEGACY), pick(outdated, installed, failed = NuvioApp.LEGACY))
        assertEquals(MainAction.Update(NuvioApp.TV), pick(outdated, installed))
        assertEquals(MainAction.Open(NuvioApp.TV), pick(installed, installed))
        assertEquals(MainAction.Install(NuvioApp.TV), pick(none, none))
    }

    @Test
    fun noWifiComesBeforeEverything() {
        assertEquals(MainAction.ConnectWifi, pick(outdated, installed, onWifi = false))
        assertEquals(MainAction.ConnectWifi, pick(none, none, failed = NuvioApp.TV, onWifi = false))
    }

    @Test
    fun onlyLegacyOnTheTvOpensLegacy() {
        assertEquals(MainAction.Open(NuvioApp.LEGACY), pick(none, installed))
    }
}
