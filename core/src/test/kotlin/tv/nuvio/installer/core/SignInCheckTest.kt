// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SignInCheckTest {
    private val access = SamsungAccess("token", "user", "a@b.c")

    @Test
    fun keepsTheSignInWhenSamsungAcceptsIt() {
        val session = SamsungSession()
        var accepted: SamsungAccess? = null
        val result = SignInCheck.run(session, signIn = { access }, accept = { accepted = it })
        assertSame(access, result)
        assertSame(access, accepted)
        assertSame(access, session.current())
    }

    @Test
    fun saysTheSignInFailedWhenItDidNotFinish() {
        val session = SamsungSession()
        var asked = false
        val e = assertFailsWith<SignInCheckException> {
            SignInCheck.run(session, signIn = { throw InstallerException("Samsung login timed out.") }, accept = { asked = true })
        }
        assertEquals(SignInCheckStep.SIGN_IN, e.step)
        assertTrue(e.message!!.contains("did not finish") && e.message!!.contains("timed out"))
        assertTrue(!asked, "Samsung is not asked without a sign-in")
        assertNull(session.current())
    }

    @Test
    fun saysSamsungRefusedWhenTheSignInIsNotAccepted() {
        val session = SamsungSession()
        val e = assertFailsWith<SignInCheckException> {
            SignInCheck.run(session, signIn = { access }, accept = { throw InstallerException("HTTP 401") })
        }
        assertEquals(SignInCheckStep.SAMSUNG, e.step)
        assertTrue(e.message!!.contains("did not accept") && e.message!!.contains("HTTP 401"))
        assertNull(session.current(), "a refused sign-in is not kept")
    }

    @Test
    fun authorReplyMustBeACertificateFromTheAuthorCa() {
        val caKeys = TestCerts.keys()
        val ca = TestCerts.cert("Author CA", "Author CA", caKeys, caKeys.private, ca = true)
        val author = TestCerts.cert("Nuvio", "Author CA", TestCerts.keys(), caKeys.private)
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(author.encoded) + "\n-----END CERTIFICATE-----\n"
        assertEquals(author, SamsungCertificates.checkedAuthor(pem, ca))

        val garbage = assertFailsWith<InstallerException> { SamsungCertificates.checkedAuthor("""{"error":"invalid token"}""", ca) }
        assertTrue(garbage.message!!.contains("invalid token"))

        val otherKeys = TestCerts.keys()
        val otherCa = TestCerts.cert("Other CA", "Other CA", otherKeys, otherKeys.private, ca = true)
        val mismatch = assertFailsWith<InstallerException> { SamsungCertificates.checkedAuthor(pem, otherCa) }
        assertTrue(mismatch.message!!.contains("does not match"))
    }
}
