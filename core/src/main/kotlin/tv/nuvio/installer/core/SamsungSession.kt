// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.time.Duration
import java.time.Instant

/**
 * Keeps the last Samsung sign-in in memory, so certificates for several TVs can be created with
 * one sign-in. Nothing is written to disk: the sign-in is forgotten after [maxAge] or when the
 * app process ends.
 */
class SamsungSession(
    private val maxAge: Duration = Duration.ofHours(1),
    private val clock: () -> Instant = Instant::now,
) {
    private var access: SamsungAccess? = null
    private var signedInAt: Instant? = null

    /** The remembered sign-in, or null when there is none or it is older than [maxAge]. */
    @Synchronized
    fun current(): SamsungAccess? {
        val at = signedInAt ?: return null
        if (!clock().isBefore(at.plus(maxAge))) {
            forget()
            return null
        }
        return access
    }

    /** When the remembered sign-in stops being used, or null when there is none. */
    @Synchronized
    fun expiresAt(): Instant? = if (current() != null) signedInAt?.plus(maxAge) else null

    @Synchronized
    fun remember(value: SamsungAccess) {
        access = value
        signedInAt = clock()
    }

    @Synchronized
    fun forget() {
        access = null
        signedInAt = null
    }

    /**
     * Runs [use] with the remembered sign-in when there is one. If that fails (for example because
     * Samsung no longer accepts it), the sign-in is forgotten and [use] runs once more after a fresh
     * [signIn]. Without a remembered sign-in, [signIn] runs first.
     */
    fun <T> withAccess(events: InstallerEvents, signIn: () -> SamsungAccess, use: (SamsungAccess) -> T): T {
        current()?.let { saved ->
            events.info("Using your Samsung sign-in from earlier; no need to sign in again.")
            try {
                return use(saved)
            } catch (e: Exception) {
                forget()
                events.info("Samsung did not accept the earlier sign-in (${e.message}). Sign in again.")
            }
        }
        val fresh = signIn()
        remember(fresh)
        return use(fresh)
    }
}
