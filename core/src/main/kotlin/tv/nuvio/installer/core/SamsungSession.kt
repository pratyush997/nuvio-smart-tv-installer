// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/** Where a [SamsungSession] keeps the sign-in between app starts. The app encrypts what it saves. */
interface SignInStore {
    fun load(): String?
    fun save(text: String)
    fun clear()
}

/**
 * Keeps the last Samsung sign-in, so certificates for several TVs can be created with one
 * sign-in. It lasts as long as Samsung says the token is valid (`access_token_expires_in`), or
 * [defaultLifetime] when Samsung does not say. With a [store], it survives app restarts.
 */
class SamsungSession(
    private val defaultLifetime: Duration = Duration.ofHours(1),
    private val clock: () -> Instant = Instant::now,
    private val store: SignInStore? = null,
) {
    companion object {
        /** Stop using a token this long before Samsung's own expiry, so a request never races it. */
        val MARGIN: Duration = Duration.ofMinutes(5)
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private class Saved(val accessToken: String, val userId: String, val email: String, val expiresAt: String)

    private var access: SamsungAccess? = null
    private var expiresAt: Instant? = null

    init {
        runCatching {
            val saved = json.decodeFromString(Saved.serializer(), store?.load() ?: return@runCatching)
            access = SamsungAccess(saved.accessToken, saved.userId, saved.email)
            expiresAt = Instant.parse(saved.expiresAt)
        }.onFailure { store?.clear() }
    }

    /** The remembered sign-in, or null when there is none or it has expired. */
    @Synchronized
    fun current(): SamsungAccess? {
        val until = expiresAt ?: return null
        if (!clock().isBefore(until)) {
            forget()
            return null
        }
        return access
    }

    /** When the remembered sign-in stops being used, or null when there is none. */
    @Synchronized
    fun expiresAt(): Instant? = if (current() != null) expiresAt else null

    @Synchronized
    fun remember(value: SamsungAccess) {
        val now = clock()
        val lifetime = value.expiresInSeconds?.takeIf { it > 0 }
            ?.let { Duration.ofSeconds(it).minus(MARGIN).coerceAtLeast(Duration.ofMinutes(1)) }
            ?: defaultLifetime
        access = value
        expiresAt = now.plus(lifetime)
        runCatching {
            store?.save(json.encodeToString(Saved.serializer(), Saved(value.accessToken, value.userId, value.email, expiresAt.toString())))
        }
    }

    @Synchronized
    fun forget() {
        access = null
        expiresAt = null
        runCatching { store?.clear() }
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
