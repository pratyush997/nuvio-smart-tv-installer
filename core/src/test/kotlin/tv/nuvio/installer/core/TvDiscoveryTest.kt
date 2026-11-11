// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvDiscoveryTest {
    private val livingRoom = SamsungTvStatus("Living Room", "QA55Q60", true, "192.168.1.11")

    @Test
    fun hostsCoverTheSubnetExceptNetworkBroadcastAndPhone() {
        val hosts = TvDiscovery.hostsToScan("192.168.1.11", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.0" in hosts || "192.168.1.255" in hosts || "192.168.1.11" in hosts)
    }

    @Test
    fun largeNetworksAreLimitedToThePhonesSlash24() {
        val hosts = TvDiscovery.hostsToScan("10.20.30.40", 16)
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.20.30.") })
    }

    @Test
    fun smallNetworksScanOnlyTheirHosts() {
        assertEquals(listOf("192.168.1.10"), TvDiscovery.hostsToScan("192.168.1.9", 30))
        assertEquals(125, TvDiscovery.hostsToScan("192.168.1.130", 25).size)
        assertTrue(TvDiscovery.hostsToScan("192.168.1.130", 25).all { it.substringAfterLast('.').toInt() in 129..254 })
        // /31 and /32 are treated as /30 so there is always something to scan.
        assertEquals(1, TvDiscovery.hostsToScan("192.168.1.9", 32).size)
    }

    @Test
    fun invalidPhoneAddressScansNothing() {
        assertTrue(TvDiscovery.hostsToScan("not-an-ip", 24).isEmpty())
        assertTrue(TvDiscovery.scan("not-an-ip", 24) { error("must not probe") }.isEmpty())
    }

    @Test
    fun scanReturnsAnsweringTvsInAddressOrder() {
        val probed = ConcurrentHashMap.newKeySet<String>()
        val found = TvDiscovery.scan("192.168.1.11", 24, parallelism = 8) { ip ->
            probed += ip
            when (ip) {
                "192.168.1.200" -> livingRoom.copy(name = "Bedroom")
                "192.168.1.20" -> livingRoom
                // Answered HTTP on 8001 but sent no TV info: not a TV.
                "192.168.1.30" -> SamsungTvStatus("", "", null, null)
                "192.168.1.40" -> throw IllegalStateException("probe failure is ignored")
                else -> null
            }
        }
        assertEquals(listOf("192.168.1.20", "192.168.1.200"), found.map { it.ip })
        assertEquals(listOf("Living Room", "Bedroom"), found.map { it.status.name })
        assertEquals(253, probed.size)
    }

    @Test
    fun readinessNeedsDeveloperModeAndMatchingHostIp() {
        assertTrue(FoundTv("192.168.1.20", livingRoom).isReadyFor("192.168.1.11"))
        assertFalse(FoundTv("192.168.1.20", livingRoom).isReadyFor("192.168.1.12"))
        assertFalse(FoundTv("192.168.1.20", livingRoom).isReadyFor(null))
        assertFalse(FoundTv("192.168.1.20", livingRoom.copy(developerMode = false)).isReadyFor("192.168.1.11"))
        assertFalse(FoundTv("192.168.1.20", livingRoom.copy(developerMode = null)).isReadyFor("192.168.1.11"))
    }

    @Test
    fun parseStatusReadsTheTvIdentity() {
        // Shape of a 2020+ TV: id inside "device", also repeated at the top level.
        val full = TvNetwork.parseStatus(
            """{"device":{"id":"uuid:aaaa-1111","duid":"uuid:aaaa-1111","wifiMac":"8c:ea:48:00:00:01","name":"Living Room","modelName":"QA55Q60"},"id":"uuid:aaaa-1111"}"""
        )
        assertEquals("uuid:aaaa-1111", full?.id)
        assertEquals("uuid:bbbb", TvNetwork.parseStatus("""{"device":{"name":"TV"},"id":"uuid:bbbb"}""")?.id)
        assertEquals("uuid:cccc", TvNetwork.parseStatus("""{"device":{"duid":"uuid:cccc","name":"TV"}}""")?.id)
        assertEquals("8c:ea:48:00:00:02", TvNetwork.parseStatus("""{"device":{"wifiMac":"8c:ea:48:00:00:02","name":"TV"}}""")?.id)
        assertNull(TvNetwork.parseStatus("""{"device":{"name":"TV"}}""")?.id)
    }

    @Test
    fun rememberedTvIsFoundAgainAfterAnIpChange() {
        val bedroom = FoundTv("192.168.1.30", livingRoom.copy(name = "Bedroom", id = "uuid:bed"))
        val living = FoundTv("192.168.1.42", livingRoom.copy(id = "uuid:living"))
        val found = listOf(bedroom, living)

        assertEquals(living, TvDiscovery.findRemembered(RememberedTv("uuid:living", "Living Room", "QA55Q60"), found))
        // The id wins over the name: a renamed TV is still found.
        assertEquals(living, TvDiscovery.findRemembered(RememberedTv("uuid:living", "Old name", "QA55Q60"), found))
        // Known id that is not on the network: not matched to another TV with the same name.
        assertNull(TvDiscovery.findRemembered(RememberedTv("uuid:gone", "Living Room", "QA55Q60"), found))
        assertNull(TvDiscovery.findRemembered(RememberedTv(null, "", ""), found))
    }

    @Test
    fun tvsWithoutIdAreMatchedByNameAndModelOnlyWhenUnique() {
        val a = FoundTv("192.168.1.30", livingRoom)
        val b = FoundTv("192.168.1.31", livingRoom.copy(model = "UA43"))
        assertEquals(a, TvDiscovery.findRemembered(RememberedTv(null, "Living Room", "QA55Q60"), listOf(a, b)))
        // Remembered with an id, now reporting none (older firmware): name and model still match.
        assertEquals(a, TvDiscovery.findRemembered(RememberedTv("uuid:x", "Living Room", "QA55Q60"), listOf(a, b)))
        // Two identical TVs: ambiguous, so no guess.
        val twin = a.copy(ip = "192.168.1.32")
        assertNull(TvDiscovery.findRemembered(RememberedTv(null, "Living Room", "QA55Q60"), listOf(a, twin)))
    }

    @Test
    fun addressConversionRoundTrips() {
        for (ip in listOf("0.0.0.0", "192.168.1.9", "255.255.255.255", "10.0.2.15")) {
            assertEquals(ip, TvNetwork.toText(TvNetwork.toInt(ip)))
        }
    }

    @Test
    fun fetchStatusReadsALiveInfoPort() {
        val body = """{"device":{"developerIP":"192.168.1.11","developerMode":"1","modelName":"QA55Q60","name":"Living Room"}}"""
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val serving = thread(isDaemon = true) {
                server.accept().use { client ->
                    val request = client.getInputStream().bufferedReader().readLine()
                    assertEquals("GET /api/v2/ HTTP/1.1", request)
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n$body".toByteArray())
                }
            }
            val status = TvNetwork.fetchStatus("127.0.0.1", timeoutMs = 2000, connectTimeoutMs = 500, port = server.localPort)
            serving.join(2000)
            assertEquals(livingRoom, status)
        }
    }

    @Test
    fun fetchStatusReturnsNullWhenNothingListens() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertNull(TvNetwork.fetchStatus("127.0.0.1", timeoutMs = 500, port = port))
    }
}
