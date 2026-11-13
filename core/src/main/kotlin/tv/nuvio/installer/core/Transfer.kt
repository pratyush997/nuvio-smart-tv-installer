// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.util.Locale
import kotlin.math.roundToLong

/** Sizes, speeds and durations in the words the app shows: "44.1 MB", "410 KB/s", "1 min 48 s". */
object Transfer {
    fun bytes(n: Long): String = when {
        n < 1_000 -> "$n B"
        n < 1_000_000 -> "${n / 1_000} KB"
        else -> String.format(Locale.US, "%.1f MB", n / 1_000_000.0)
    }

    fun speed(bytesPerSecond: Double): String =
        if (bytesPerSecond < 1_000_000) "${(bytesPerSecond / 1_000).roundToLong()} KB/s"
        else String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000)

    /** An exact time taken: "0.4 s", "12 s", "1 min 48 s". */
    fun took(millis: Long): String = when {
        millis < 10_000 -> String.format(Locale.US, "%.1f s", millis / 1_000.0)
        millis < 60_000 -> "${millis / 1_000} s"
        else -> "${millis / 60_000} min ${millis % 60_000 / 1_000} s"
    }

    /** A rough time still to go: "a few seconds", "about 40 s", "about 2 min". */
    fun left(seconds: Long): String = when {
        seconds < 10 -> "a few seconds"
        seconds < 60 -> "about ${(seconds + 4) / 5 * 5} s"
        else -> "about ${(seconds + 30) / 60} min"
    }
}

/**
 * Turns a stream of (done, total) progress reports into "12.3 of 44.1 MB · 410 KB/s · about 2 min left".
 * Speed is the average since the transfer started, which is steadier than the latest burst.
 * Speed and time left appear once [warmupMs] has passed, so the first guess is not wild.
 */
class TransferMeter(private val clock: () -> Long = System::currentTimeMillis, private val warmupMs: Long = 2_000) {
    private var startedAt = 0L
    private var lastDone = -1L

    fun describe(done: Long, total: Long): String {
        val now = clock()
        if (lastDone < 0 || done < lastDone) startedAt = now
        lastDone = done
        val size = if (total > 0) "${Transfer.bytes(done)} of ${Transfer.bytes(total)}" else Transfer.bytes(done)
        val elapsed = now - startedAt
        if (elapsed < warmupMs || done <= 0) return size
        val rate = done * 1_000.0 / elapsed
        val parts = mutableListOf(size, Transfer.speed(rate))
        if (total > done) parts += "${Transfer.left(((total - done) / rate).roundToLong())} left"
        return parts.joinToString(" · ")
    }

    fun reset() {
        lastDone = -1L
    }
}
