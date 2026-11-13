// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TransferTest {
    @Test
    fun wordsForSizesSpeedsAndTimes() {
        assertEquals("512 B", Transfer.bytes(512))
        assertEquals("1 KB", Transfer.bytes(1_800))
        assertEquals("44.1 MB", Transfer.bytes(44_136_329))
        assertEquals("410 KB/s", Transfer.speed(410_000.0))
        assertEquals("2.5 MB/s", Transfer.speed(2_500_000.0))
        assertEquals("0.4 s", Transfer.took(400))
        assertEquals("12 s", Transfer.took(12_300))
        assertEquals("1 min 48 s", Transfer.took(108_000))
        assertEquals("a few seconds", Transfer.left(7))
        assertEquals("about 40 s", Transfer.left(37))
        assertEquals("about 2 min", Transfer.left(100))
    }

    @Test
    fun meterAddsSpeedAndTimeLeftAfterWarmup() {
        var now = 0L
        val meter = TransferMeter(clock = { now })
        assertEquals("0 B of 44.1 MB", meter.describe(0, 44_100_000))
        now = 1_000
        assertEquals("400 KB of 44.1 MB", meter.describe(400_000, 44_100_000), "no guess before the warm-up")
        now = 10_000
        // 4 MB in 10 s = 400 KB/s; 40.1 MB left = about 100 s.
        assertEquals("4.0 MB of 44.1 MB · 400 KB/s · about 2 min left", meter.describe(4_000_000, 44_100_000))
    }

    @Test
    fun meterStartsOverWhenATransferRestarts() {
        var now = 0L
        val meter = TransferMeter(clock = { now })
        meter.describe(0, 1_000_000)
        now = 10_000
        meter.describe(900_000, 1_000_000)
        now = 11_000
        // A retry starts from zero: the clock restarts, so no speed yet.
        assertEquals("10 KB of 1.0 MB", meter.describe(10_000, 1_000_000))
    }

    @Test
    fun noTimeLeftWithoutATotal() {
        var now = 0L
        val meter = TransferMeter(clock = { now })
        meter.describe(0, -1)
        now = 5_000
        assertEquals("2.0 MB · 400 KB/s", meter.describe(2_000_000, -1))
    }
}
