// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

/** Which part of a sign-in check failed: the sign-in itself, or Samsung refusing it afterwards. */
enum class SignInCheckStep { SIGN_IN, SAMSUNG }

class SignInCheckException(val step: SignInCheckStep, message: String, cause: Throwable?) : InstallerException(message, cause)

/**
 * Checks that "Sign in with Samsung" works, without a TV: a fresh [signIn], then [accept]
 * (Samsung is asked to use it, see [SamsungCertificates.checkAccess]). A sign-in that passes is
 * kept in [session], so the next certificate needs no sign-in while it lasts.
 */
object SignInCheck {
    fun run(session: SamsungSession, signIn: () -> SamsungAccess, accept: (SamsungAccess) -> Unit): SamsungAccess {
        val access = try {
            signIn()
        } catch (e: Exception) {
            throw SignInCheckException(SignInCheckStep.SIGN_IN, "The Samsung sign-in did not finish. ${e.message.orEmpty()}".trim(), e)
        }
        try {
            accept(access)
        } catch (e: Exception) {
            throw SignInCheckException(
                SignInCheckStep.SAMSUNG,
                "You signed in, but Samsung did not accept the sign-in. ${e.message.orEmpty()}".trim(),
                e,
            )
        }
        session.remember(access)
        return access
    }
}
