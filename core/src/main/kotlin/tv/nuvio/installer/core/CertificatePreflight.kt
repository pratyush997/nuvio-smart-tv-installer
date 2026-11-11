// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The result of checking the saved certificates for a TV before anything is uploaded.
 * [usable] are the ones worth trying, best first; empty means a new certificate is needed.
 * [notes] are for the activity log. [blocked] is set when installing cannot work.
 */
data class Preflight(val usable: List<SigningIdentity>, val notes: List<String>, val blocked: MissingIdentityException?)

/**
 * Decides, before signing and uploading, whether the saved certificates can install Nuvio on this
 * TV. A Samsung TV only accepts an update signed with the certificate of the installed app, so:
 * - expired certificates are skipped;
 * - with Nuvio installed and no valid certificate left, installing is blocked with what to do;
 * - with Nuvio installed and a certificate that never installed Nuvio on this TV, a warning is logged.
 */
object CertificatePreflight {
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC)

    fun check(candidates: List<SigningIdentity>, installed: Map<String, String>?, now: Instant = Instant.now()): Preflight {
        val notes = ArrayList<String>()
        val usable = ArrayList<SigningIdentity>()
        val expired = ArrayList<Instant>()
        for (identity in candidates) {
            val expiresAt = identity.expiresAt()
            if (expiresAt != null && CertificateExpiry.state(expiresAt, now) == ExpiryState.EXPIRED) {
                expired += expiresAt
                notes += "Skipping a saved certificate for this TV that expired on ${dateFormat.format(expiresAt)}."
            } else {
                usable += identity
            }
        }
        if (installed == null) return Preflight(usable, notes, null)

        val nuvio = nuvioName(installed)
        if (usable.isEmpty()) {
            val blocked = if (expired.isNotEmpty()) {
                CertificateExpiredException(
                    "$nuvio is on this TV, signed with a certificate that expired on ${dateFormat.format(expired.max())}. " +
                        "The TV refuses updates signed with it.\n\n" +
                        "Uninstall Nuvio from the TV, then install again: a new certificate is created during the install. " +
                        "Nuvio's settings on the TV are lost when it is uninstalled."
                )
            } else missingIdentity(installed)
            return Preflight(usable, notes, blocked)
        }
        if (usable.none { it.lastSuccessfulInstallAt != null }) {
            notes += "The saved certificate has not installed Nuvio on this TV before. If $nuvio was installed with a " +
                "different certificate (for example by the desktop installer on another computer), the TV will refuse this update."
        }
        return Preflight(usable, notes, null)
    }

    fun missingIdentity(installed: Map<String, String>) = MissingIdentityException(
        "${nuvioName(installed)} is already installed on this TV, signed with a certificate " +
            "that is not on this phone. A new certificate cannot update it.\n\n" +
            "Either import the certificate file from the desktop Nuvio TV Installer, " +
            "or uninstall Nuvio from the TV first (then install again)."
    )

    /** The TV refused the update although Nuvio is installed: it was signed with another certificate. */
    fun rejectedUpdate(installed: Map<String, String>, cause: Exception) = MissingIdentityException(
        "The TV refused the update: ${nuvioName(installed)} on this TV was installed with a " +
            "different certificate than the one on this phone.\n\n" +
            "Import the certificate that installed it (from the desktop Nuvio TV Installer or a backup), " +
            "or uninstall Nuvio from the TV and install again.",
        cause,
    )

    private fun nuvioName(installed: Map<String, String>): String {
        val version = installed["app_version"].orEmpty().trim()
        return if (version.isEmpty()) "Nuvio TV" else "Nuvio TV $version"
    }
}
