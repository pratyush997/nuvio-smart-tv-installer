// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityBackupTest {
    private fun store() = IdentityStore(Files.createTempDirectory("identities").toFile())

    private fun storeWith(vararg duids: String) = store().also { store -> duids.forEach { store.save(TestCerts.identity(duid = it)) } }

    private fun backup(store: IdentityStore, password: String?): ByteArray =
        ByteArrayOutputStream().also { IdentityBackup.write(store, it, password) }.toByteArray()

    private fun fingerprints(store: IdentityStore) = store.all().map { it.second.duid to it.second.fingerprint }.toSet()

    @Test
    fun plainBackupIsAZipThatRestoresEveryCertificate() {
        val source = storeWith("TV-A", "TV-B")
        val bytes = backup(source, password = null)
        assertFalse(IdentityBackup.isEncrypted(bytes))
        assertEquals('P'.code.toByte(), bytes[0])
        val target = store()
        assertEquals(2, IdentityBackup.import(target, bytes))
        assertEquals(fingerprints(source), fingerprints(target))
    }

    @Test
    fun protectedBackupRestoresOnlyWithThePassword() {
        val source = storeWith("TV-A", "TV-B")
        val bytes = backup(source, password = "correct horse")
        assertTrue(IdentityBackup.isEncrypted(bytes))
        // The private keys are not readable in the file.
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("TV-A") || text.contains("authorCert") || text.contains("duid-"))

        val missing = assertFailsWith<BackupPasswordException> { IdentityBackup.import(store(), bytes) }
        assertFalse(missing.wrongPassword)
        val wrong = assertFailsWith<BackupPasswordException> { IdentityBackup.import(store(), bytes, "wrong") }
        assertTrue(wrong.wrongPassword)

        val target = store()
        assertEquals(2, IdentityBackup.import(target, bytes, "correct horse"))
        assertEquals(fingerprints(source), fingerprints(target))
    }

    @Test
    fun blankPasswordWritesAPlainBackup() {
        assertFalse(IdentityBackup.isEncrypted(backup(storeWith("TV-A"), password = "  ")))
    }

    @Test
    fun encryptionRoundTripsAndUsesFreshSaltEachTime() {
        val data = ByteArray(5000) { (it % 251).toByte() }
        val a = IdentityBackup.encrypt(data, "pw", iterations = 1000)
        val b = IdentityBackup.encrypt(data, "pw", iterations = 1000)
        assertFalse(a.contentEquals(b))
        assertContentEquals(data, IdentityBackup.decrypt(a, "pw"))
        assertContentEquals(data, IdentityBackup.decrypt(b, "pw"))
    }

    @Test
    fun damagedFilesAreRejected() {
        val bytes = IdentityBackup.encrypt("certificates".toByteArray(), "pw", iterations = 1000)
        // A flipped bit in the ciphertext, and in the authenticated header (the salt).
        for (index in listOf(bytes.size - 1, 14)) {
            val damaged = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<BackupPasswordException> { IdentityBackup.decrypt(damaged, "pw") }
        }
        assertFailsWith<InstallerException> { IdentityBackup.decrypt(bytes.copyOf(20), "pw") }
    }

    @Test
    fun singleDesktopJsonStillImports() {
        val target = store()
        val json = TestCerts.identity(duid = "TV-DESKTOP").toJson().toByteArray()
        assertEquals(1, IdentityBackup.import(target, json))
        assertEquals(listOf("TV-DESKTOP"), target.all().map { it.second.duid })
    }

    @Test
    fun zipWithoutCertificatesFails() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("readme.txt")); it.write("hi".toByteArray()); it.closeEntry() }
        }.toByteArray()
        assertFailsWith<InstallerException> { IdentityBackup.import(store(), zip) }
    }
}
