// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvAwakeTest {
    private val loopback = InetAddress.getLoopbackAddress()

    /** A port nothing listens on: bound, noted, then closed. */
    private fun closedPort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    @Test
    fun awakeWhenAnyPortAnswers() {
        ServerSocket(0, 1, loopback).use { open ->
            // The info port is closed but the install port answers: still on.
            assertTrue(TvNetwork.isAwake(loopback.hostAddress, 1000, listOf(closedPort(), open.localPort)))
        }
    }

    @Test
    fun offWhenNothingAnswers() {
        assertFalse(TvNetwork.isAwake(loopback.hostAddress, 1000, listOf(closedPort(), closedPort())))
    }

    @Test
    fun offForANonsenseAddress() {
        assertFalse(TvNetwork.isAwake("not an ip", 500, listOf(1)))
    }
}
