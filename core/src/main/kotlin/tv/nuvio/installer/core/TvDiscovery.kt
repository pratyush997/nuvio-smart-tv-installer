// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A Samsung TV that answered on its info port during a scan. */
data class FoundTv(val ip: String, val status: SamsungTvStatus) {
    /** Developer Mode is on and Host PC IP is [phoneIp], so the installer can connect. */
    fun isReadyFor(phoneIp: String?): Boolean =
        status.developerMode == true && phoneIp != null && status.developerIp == phoneIp
}

/** The TV the user last connected to, as remembered between app starts. */
data class RememberedTv(val id: String?, val name: String, val model: String)

/** Finds Samsung TVs on the phone's network by asking every address for the TV info page. */
object TvDiscovery {
    /** Connect timeout per address. Local devices answer in a few milliseconds; absent ones never do. */
    const val CONNECT_TIMEOUT_MS = 350
    const val READ_TIMEOUT_MS = 2000
    const val PARALLELISM = 48

    /**
     * Addresses to scan: every host on the phone's network except the phone itself. Networks
     * larger than /24 are limited to the phone's /24, so a scan always takes a few seconds.
     */
    fun hostsToScan(phoneIp: String, prefixLength: Int): List<String> {
        if (!TvNetwork.isValidIpv4(phoneIp)) return emptyList()
        val bits = prefixLength.coerceIn(24, 30)
        val phone = TvNetwork.toInt(phoneIp)
        val network = phone and (-1 shl (32 - bits))
        val size = 1 shl (32 - bits)
        // Skip the network address (first) and the broadcast address (last).
        return (1 until size - 1).map { network + it }.filter { it != phone }.map(TvNetwork::toText)
    }

    /**
     * The [remembered] TV among [found], so a TV that got a new IP from the router is found again.
     * Matches by id; for TVs that report no id, by name and model, and only when exactly one matches.
     */
    fun findRemembered(remembered: RememberedTv, found: List<FoundTv>): FoundTv? {
        val id = remembered.id?.takeIf { it.isNotBlank() }
        if (id != null) found.firstOrNull { it.status.id == id }?.let { return it }
        if (remembered.name.isBlank()) return null
        return found.filter { tv ->
            (id == null || tv.status.id == null) && tv.status.name == remembered.name && tv.status.model == remembered.model
        }.singleOrNull()
    }

    /**
     * Scans the phone's network and returns the TVs that answered, in address order.
     * [probe] is the per-address check; tests replace it.
     */
    fun scan(
        phoneIp: String,
        prefixLength: Int,
        parallelism: Int = PARALLELISM,
        probe: (String) -> SamsungTvStatus? = { TvNetwork.fetchStatus(it, READ_TIMEOUT_MS, CONNECT_TIMEOUT_MS) },
    ): List<FoundTv> {
        val hosts = hostsToScan(phoneIp, prefixLength)
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, hosts.size)) { task ->
            Thread(task, "tv-scan").apply { isDaemon = true }
        }
        try {
            val tasks = hosts.map { ip -> Callable { runCatching { probe(ip) }.getOrNull()?.let { FoundTv(ip, it) } } }
            return pool.invokeAll(tasks)
                .mapNotNull { runCatching { it.get() }.getOrNull() }
                // A device that answered HTTP on port 8001 without TV info is not a Samsung TV.
                .filter { it.status.name.isNotEmpty() }
                .sortedBy { TvNetwork.toInt(it.ip).toLong() and 0xffffffffL }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
