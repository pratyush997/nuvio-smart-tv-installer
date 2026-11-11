// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class SamsungSessionTest {
    private var now = Instant.parse("2026-11-11T10:00:00Z")
    private val session = SamsungSession(Duration.ofHours(1)) { now }
    private val events = object : InstallerEvents {
        val lines = ArrayList<String>()
        override fun log(level: LogLevel, text: String) { lines += text }
    }
    private fun access(token: String) = SamsungAccess(token, "user", "user@example.com")

    @Test
    fun remembersForAnHourOnly() {
        val first = access("a")
        session.remember(first)
        now = now.plus(Duration.ofMinutes(59))
        assertSame(first, session.current())
        now = now.plus(Duration.ofMinutes(1))
        assertNull(session.current())
    }

    @Test
    fun reportsWhenTheSignInExpires() {
        assertNull(session.expiresAt())
        val start = now
        session.remember(access("a"))
        assertEquals(start.plus(Duration.ofHours(1)), session.expiresAt())
        now = now.plus(Duration.ofHours(1))
        assertNull(session.expiresAt())
        session.remember(access("b"))
        session.forget()
        assertNull(session.expiresAt())
    }

    @Test
    fun secondTvReusesTheSignIn() {
        var signIns = 0
        val used = ArrayList<String>()
        repeat(2) { tv ->
            session.withAccess(events, signIn = { signIns++; access("token") }) { used += "${it.accessToken}-tv$tv" }
        }
        assertEquals(1, signIns)
        assertEquals(listOf("token-tv0", "token-tv1"), used)
    }

    @Test
    fun rejectedSignInFallsBackToAFreshOne() {
        session.remember(access("stale"))
        var signIns = 0
        val result = session.withAccess(events, signIn = { signIns++; access("fresh") }) {
            if (it.accessToken == "stale") throw InstallerException("Samsung author certificate request failed: HTTP 401")
            it.accessToken
        }
        assertEquals("fresh", result)
        assertEquals(1, signIns)
        assertEquals("fresh", session.current()?.accessToken)
    }

    @Test
    fun failureWithAFreshSignInIsReported() {
        val error = assertFailsWith<InstallerException> {
            session.withAccess(events, signIn = { access("fresh") }) { throw InstallerException("no network") }
        }
        assertEquals("no network", error.message)
    }

    @Test
    fun cancelledSignInIsNotRemembered() {
        assertFailsWith<InstallerException> {
            session.withAccess<Unit>(events, signIn = { throw InstallerException("Samsung login was cancelled.") }) { }
        }
        assertNull(session.current())
    }
}
