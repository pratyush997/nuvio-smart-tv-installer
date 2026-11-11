// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.time.Instant

/**
 * One certificate profile for all of the user's TVs, as Tizen Studio and Apps2Samsung do it:
 * a single author key, and a distributor certificate that lists every TV (up to [MAX_TVS]).
 * Adding a TV asks Samsung for a new distributor certificate listing the TVs so far plus the new
 * one; the author certificate stays byte-identical, so Nuvio on the other TVs keeps updating.
 */
object SharedProfile {
    /** Samsung's limit of TVs (DUIDs) in one distributor certificate. */
    const val MAX_TVS = 10

    /**
     * What to ask Samsung for when [duid] has no certificate yet. With [base] set, reuse its author
     * and request a distributor for [duids]; with [base] null, create a new profile for [duid] only.
     */
    data class Plan(val base: SigningIdentity?, val duids: List<String>)

    /** The TVs a saved identity's distributor certificate lists, plus the TV it was saved for. */
    fun duidsOf(identity: SigningIdentity): List<String> {
        val listed = runCatching {
            val bundle = identity.distributor()
            val leaf = bundle.chain.firstOrNull { it.basicConstraints == -1 } ?: bundle.chain.first()
            TizenStudioCertificate.duids(leaf)
        }.getOrDefault(emptyList())
        return (listOf(identity.duid) + listed).filter { it.isNotBlank() }.distinct()
    }

    private fun authorExpiry(identity: SigningIdentity): Instant? = runCatching {
        identity.author().chain.filter { it.basicConstraints == -1 }.minOfOrNull { it.notAfter.toInstant() }
    }.getOrNull()

    /**
     * Picks the author to share. It must have a valid chain and stay valid for more than
     * [CertificateExpiry.WARNING]; otherwise a new profile is better than a certificate that
     * stops working within a month. Among several, the one used most recently wins.
     */
    fun plan(saved: List<SigningIdentity>, duid: String, now: Instant = Instant.now()): Plan {
        val authors = saved
            .filter { it.isUsable && it.hasValidAuthorChain() }
            .filter { CertificateExpiry.state(authorExpiry(it), now) == ExpiryState.VALID }
            .groupBy { it.fingerprint }
        if (authors.isEmpty()) return Plan(null, listOf(duid))
        val group = authors.values.maxWith(
            compareBy<List<SigningIdentity>> { list -> list.maxOf { it.lastSuccessfulInstallAt.orEmpty() } }
                .thenBy { list -> list.maxOf { it.createdAt } }
        )
        val base = group.maxWith(compareBy<SigningIdentity> { it.lastSuccessfulInstallAt.orEmpty() }.thenBy { it.createdAt })
        // Most recently used TVs first, so the oldest drop off when there are more than 10.
        val others = group.sortedByDescending { it.lastSuccessfulInstallAt.orEmpty() }.flatMap(::duidsOf)
        return Plan(base, (listOf(duid) + others).distinct().take(MAX_TVS))
    }

    /**
     * The files to save after Samsung issued [distributor] for the shared author: one per TV it
     * lists. [requested] is the TV the certificate was made for and is always saved. [current] is
     * the certificate each other TV uses now: a TV whose current certificate has a different
     * author is left alone, so its file is never overwritten. TVs keep their install history.
     */
    fun identitiesToSave(
        distributor: SigningIdentity,
        requested: String,
        duids: List<String>,
        current: (String) -> SigningIdentity?,
    ): List<SigningIdentity> =
        (listOf(requested) + duids).distinct().mapNotNull { tv ->
            val existing = current(tv)?.takeIf { it.fingerprint == distributor.fingerprint }
            if (tv != requested && current(tv) != null && existing == null) return@mapNotNull null
            distributor.copy(
                duid = tv,
                createdAt = existing?.createdAt ?: distributor.createdAt,
                lastSuccessfulInstallAt = existing?.lastSuccessfulInstallAt,
                lastSuccessfulPackageId = existing?.lastSuccessfulPackageId,
            )
        }
}
