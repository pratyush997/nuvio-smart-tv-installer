// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The "reserve this phone's IP" tip shown when the phone's IP no longer matches Host IP. */
class IpAdviceTest {
    private val tvOldHost = SamsungTvStatus("Living Room", "QA55Q60", true, "192.168.50.11")
    private val silentTv = SamsungTvStatus("TV", "", null, null)

    private fun report(phone: String?, status: SamsungTvStatus?, connected: Boolean = false, lastGood: String? = null, gateway: String? = "192.168.50.1") =
        TvNetwork.report(phone, 24, "192.168.50.9", status, connected, null, lastGood, gateway)

    @Test
    fun hostPcIpMismatchSuggestsReservingThePhonesIp() {
        val fix = report("192.168.50.14", tvOldHost).fix!!
        assertTrue(fix.tip!!.contains("reserve 192.168.50.14 for this phone"))
        assertTrue(fix.tip!!.contains("http://192.168.50.1"))
        assertEquals("http://192.168.50.1", fix.tipLink)
        assertEquals("Tip: ${fix.tip}", fix.text.lines().last())
    }

    @Test
    fun phoneIpChangeSuggestsReservingThePhonesIp() {
        val fix = report("192.168.50.14", silentTv, lastGood = "192.168.50.11").fix!!
        assertTrue(fix.reason!!.contains("changed from 192.168.50.11 to 192.168.50.14"))
        assertTrue(fix.tip!!.contains("reserve 192.168.50.14"))
    }

    @Test
    fun withoutAGatewayTheTipHasNoLink() {
        val fix = report("192.168.50.14", tvOldHost, gateway = null).fix!!
        assertTrue(fix.tip!!.contains("DHCP reservation"))
        assertTrue(!fix.tip!!.contains("http://"))
        assertNull(fix.tipLink)
        assertNull(report("192.168.50.14", tvOldHost, gateway = "not-an-ip").fix!!.tipLink)
    }

    @Test
    fun noTipWhenTheIpIsNotTheProblem() {
        assertNull(report("192.168.50.11", tvOldHost, connected = true).fix)
        // Developer Mode off: first-time setup, not an address change.
        assertNull(report("192.168.50.11", tvOldHost.copy(developerMode = false)).fix!!.tip)
        // Settings right but not applied yet.
        assertNull(report("192.168.50.11", tvOldHost).fix!!.tip)
        // No TV at that address.
        assertNull(report("192.168.50.11", null).fix!!.tip)
    }

    @Test
    fun emulatorsGetNoReservationTip() {
        val fix = report("10.0.2.15", tvOldHost).fix!!
        assertNull(fix.tip)
        assertNull(fix.tipLink)
    }

    @Test
    fun fixWithoutTipKeepsItsPlainText() {
        assertEquals("Why\n1. One\n2. Two", Fix("Why", listOf("One", "Two")).text)
    }
}
