// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The backup is password-protected and no password, or the wrong one, was given. */
class BackupPasswordException(message: String, val wrongPassword: Boolean) : InstallerException(message)

/**
 * Certificate backups: a .zip of the identity files, optionally encrypted with a password.
 *
 * Encrypted layout: "NUVIOBK1" | iterations (int, big-endian) | salt (16) | IV (12) | AES-256-GCM
 * ciphertext of the .zip. The key comes from the password with PBKDF2-HMAC-SHA256. The header is
 * authenticated, so a changed file fails to open instead of restoring damaged certificates.
 */
object IdentityBackup {
    const val FILE_NAME = "nuvio-tizen-certificates.zip"
    const val ENCRYPTED_FILE_NAME = "nuvio-tizen-certificates.nuviobackup"
    private val MAGIC = "NUVIOBK1".toByteArray(Charsets.US_ASCII)
    const val ITERATIONS = 150_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val HEADER_BYTES = 8 + 4 + SALT_BYTES + IV_BYTES
    private val random = SecureRandom()

    fun isEncrypted(bytes: ByteArray): Boolean = bytes.size >= MAGIC.size && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)

    private fun isZip(bytes: ByteArray) = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /** Writes every saved identity to [out], encrypted when [password] is not blank. Returns the number of files. */
    fun write(store: IdentityStore, out: OutputStream, password: String? = null): Int {
        val files = store.all()
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { stream ->
            for ((file, _) in files) {
                stream.putNextEntry(ZipEntry(file.name))
                stream.write(file.readBytes())
                stream.closeEntry()
            }
        }
        out.write(if (password.isNullOrBlank()) zip.toByteArray() else encrypt(zip.toByteArray(), password))
        return files.size
    }

    /**
     * Imports a backup made by [write] (plain or encrypted), or a single desktop-installer .json.
     * Returns the number of identities imported. Throws [BackupPasswordException] when the backup
     * needs a password that was not given, or the password is wrong.
     */
    fun import(store: IdentityStore, bytes: ByteArray, password: String? = null): Int {
        val plain = if (isEncrypted(bytes)) {
            if (password.isNullOrEmpty()) throw BackupPasswordException("This backup is protected with a password.", wrongPassword = false)
            decrypt(bytes, password)
        } else bytes
        if (!isZip(plain)) {
            store.import(String(plain, Charsets.UTF_8))
            return 1
        }
        var imported = 0
        ZipInputStream(plain.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                    runCatching { store.import(String(zip.readBytes(), Charsets.UTF_8)) }.onSuccess { imported++ }
                }
            }
        }
        if (imported == 0) throw InstallerException("The backup did not contain any certificates.")
        return imported
    }

    fun encrypt(plain: ByteArray, password: String, iterations: Int = ITERATIONS): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    fun decrypt(bytes: ByteArray, password: String): ByteArray {
        if (!isEncrypted(bytes) || bytes.size <= HEADER_BYTES) throw InstallerException("This is not a protected certificate backup.")
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(MAGIC.size)
        val iterations = buffer.int
        if (iterations !in 1..10_000_000) throw InstallerException("This backup file is damaged.")
        val salt = ByteArray(SALT_BYTES).also { buffer.get(it) }
        val iv = ByteArray(IV_BYTES).also { buffer.get(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(bytes, 0, HEADER_BYTES)
        return try {
            cipher.doFinal(bytes, HEADER_BYTES, bytes.size - HEADER_BYTES)
        } catch (e: GeneralSecurityException) {
            // GCM cannot tell a wrong password from a damaged file.
            throw BackupPasswordException("Wrong password, or the backup file is damaged.", wrongPassword = true)
        }
    }

    private fun key(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
