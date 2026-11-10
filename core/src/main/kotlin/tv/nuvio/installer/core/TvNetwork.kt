// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 pratyush997 and contributors

package tv.nuvio.installer.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * What a Samsung TV says about itself on its info port (8001). Fields the TV did not report are
 * null; [name] is empty when the TV answered but the answer could not be read.
 */
data class SamsungTvStatus(val name: String, val model: String, val developerMode: Boolean?, val developerIp: String?)

enum class CheckState { OK, FAIL, UNKNOWN }

data class CheckRow(val state: CheckState, val text: String)

/** Result of the phone/TV address checks: one row per check, and what to do about the first failure. */
data class IpReport(val rows: List<CheckRow>, val fix: String?)

object TvNetwork {
    const val INFO_PORT = 8001
    const val POWER_CYCLE = "unplug the TV from power, wait 5 seconds, then plug it back in"

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun isValidIpv4(text: String): Boolean =
        ipv4.matchEntire(text.trim())?.groupValues?.drop(1)?.all { it.toInt() <= 255 } == true

    private fun toInt(ip: String): Int = ip.trim().split('.').fold(0) { acc, part -> (acc shl 8) or part.toInt() }

    fun sameSubnet(a: String, b: String, prefixLength: Int = 24): Boolean {
        if (!isValidIpv4(a) || !isValidIpv4(b)) return false
        val bits = prefixLength.coerceIn(0, 32)
        val mask = if (bits == 0) 0 else -1 shl (32 - bits)
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    /**
     * Asks the TV's info port who it is and how Developer Mode is set. Returns null when nothing
     * answered at [ip]. Uses a plain socket: this is cleartext HTTP on the local network.
     */
    fun fetchStatus(ip: String, timeoutMs: Int = 2500): SamsungTvStatus? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip.trim(), INFO_PORT), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.getOutputStream().apply {
                write("GET /api/v2/ HTTP/1.1\r\nHost: ${ip.trim()}:$INFO_PORT\r\nAccept: application/json\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val received = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            val input = socket.getInputStream()
            var status: SamsungTvStatus? = null
            try {
                while (received.size() < 256 * 1024) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    received.write(chunk, 0, n)
                    status = parseStatus(received.toString("UTF-8"))
                    if (status != null) break
                }
            } catch (_: SocketTimeoutException) {
            }
            status ?: if (received.toString("UTF-8").startsWith("HTTP/")) SamsungTvStatus("", "", null, null) else null
        }
    } catch (_: IOException) {
        null
    }

    /** Reads the JSON object out of an HTTP response (or a bare JSON body). */
    internal fun parseStatus(response: String): SamsungTvStatus? {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { json.parseToJsonElement(response.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
        val device = root["device"] as? JsonObject
        fun text(key: String) = ((device?.get(key) ?: root[key]) as? JsonPrimitive)?.content?.trim().orEmpty()
        val mode = when (text("developerMode").lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
        val developerIp = text("developerIP").takeIf { isValidIpv4(it) && it != "0.0.0.0" }
        return SamsungTvStatus(text("name").ifEmpty { "Samsung TV" }, text("modelName"), mode, developerIp)
    }

    /**
     * Explains the state of the phone and TV addresses.
     * [phoneIp] is null when the phone has no local network address. [connected] is whether the
     * installer connection (SDB) worked; [connectError] is its error when it did not.
     * [lastGoodPhoneIp] is the phone's address the last time the connection worked.
     */
    fun report(
        phoneIp: String?,
        prefixLength: Int,
        tvIp: String,
        status: SamsungTvStatus?,
        connected: Boolean,
        connectError: String? = null,
        lastGoodPhoneIp: String? = null,
    ): IpReport {
        val rows = ArrayList<CheckRow>()
        var fix: String? = null
        fun add(state: CheckState, text: String, hint: String? = null) {
            rows += CheckRow(state, text)
            if (state == CheckState.FAIL && fix == null) fix = hint
        }
        val hostIp = phoneIp ?: "this phone's IP"
        val devModeSteps = "On the TV open Apps, press 1 2 3 4 5 on the remote"

        if (phoneIp == null) {
            add(CheckState.FAIL, "Phone has no Wi-Fi address", "Connect this phone to the same Wi-Fi as the TV.")
        } else {
            add(CheckState.OK, "Phone IP $phoneIp")
        }

        val tv = tvIp.trim()
        if (!isValidIpv4(tv)) {
            add(CheckState.FAIL, "TV IP is not a valid address", "Type the TV's IP as four numbers, like 192.168.1.20.")
            return IpReport(rows, fix)
        }
        if (tv == phoneIp) {
            add(CheckState.FAIL, "$tv is this phone's IP, not the TV's", "Type the TV's own IP. On the TV: Settings › General (or Connection) › Network › Network Status › IP Settings.")
            return IpReport(rows, fix)
        }

        if (connected) {
            val name = status?.name.orEmpty()
            add(CheckState.OK, if (name.isNotEmpty()) "TV found at $tv: $name" else "TV found at $tv")
            add(CheckState.OK, "Developer Mode is on")
            add(CheckState.OK, "Host PC IP matches this phone")
            add(CheckState.OK, "Installer connection works")
            return IpReport(rows, null)
        }

        if (status == null) {
            if (phoneIp != null && !sameSubnet(phoneIp, tv, prefixLength)) {
                add(
                    CheckState.FAIL, "TV IP $tv is on a different network than the phone",
                    "Check the TV IP for a typo. Phone and TV must be on the same Wi-Fi (a guest network will not work).",
                )
            } else {
                add(
                    CheckState.FAIL, "No Samsung TV answered at $tv",
                    "Check that the TV is on and the IP is right. On the TV: Settings › General (or Connection) › Network › Network Status › IP Settings.",
                )
            }
            return IpReport(rows, fix)
        }

        val label = listOf(status.name, status.model).filter { it.isNotEmpty() }.joinToString(" · ")
        add(CheckState.OK, if (label.isNotEmpty()) "TV found at $tv: $label" else "TV found at $tv")

        when (status.developerMode) {
            true -> add(CheckState.OK, "Developer Mode is on")
            false -> add(
                CheckState.FAIL, "Developer Mode is off on the TV",
                "$devModeSteps, switch Developer Mode on and type $hostIp as Host PC IP. Then $POWER_CYCLE.",
            )
            null -> add(CheckState.UNKNOWN, "Developer Mode: the TV did not say")
        }

        val developerIp = status.developerIp
        when {
            status.developerMode == false -> Unit
            developerIp == null -> add(CheckState.UNKNOWN, "Host PC IP: the TV did not say")
            developerIp == phoneIp -> add(CheckState.OK, "Host PC IP matches this phone")
            else -> add(
                CheckState.FAIL, "Host PC IP on the TV is $developerIp, but this phone is $hostIp",
                "$devModeSteps and change Host PC IP to $hostIp. Then $POWER_CYCLE.",
            )
        }

        val settingsLookRight = status.developerMode == true && developerIp != null && developerIp == phoneIp
        val phoneMoved = developerIp == null && lastGoodPhoneIp != null && phoneIp != null && lastGoodPhoneIp != phoneIp
        add(
            CheckState.FAIL, "Installer connection failed",
            when {
                settingsLookRight -> "The settings are right, but the TV has not applied them yet: $POWER_CYCLE."
                phoneMoved -> "This phone's IP changed from $lastGoodPhoneIp to $phoneIp. $devModeSteps and change Host PC IP to $phoneIp. Then $POWER_CYCLE."
                else -> listOfNotNull(
                    connectError?.takeIf { it.isNotBlank() },
                    "$devModeSteps, check Developer Mode is on and Host PC IP is $hostIp. Then $POWER_CYCLE.",
                ).joinToString("\n\n")
            },
        )
        return IpReport(rows, fix)
    }
}
