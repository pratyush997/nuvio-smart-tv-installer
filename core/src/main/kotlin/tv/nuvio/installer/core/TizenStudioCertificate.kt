// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import org.bouncycastle.operator.bc.BcDefaultDigestProvider
import org.bouncycastle.pkcs.PKCS12PfxPdu
import org.bouncycastle.pkcs.bc.BcPKCS12MacCalculatorBuilderProvider
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

/** The author or distributor .p12 needs a password, or the one given is wrong. */
class TizenStudioPasswordException(val file: TizenStudioCertificate.Part, val wrongPassword: Boolean) :
    InstallerException("The password does not open ${file.fileName}.")

/**
 * Imports a Samsung certificate made with Tizen Studio's Certificate Manager: the author.p12 and
 * distributor.p12 from its certificate profile folder. Nothing is requested from Samsung, so no
 * sign-in happens in this app. This does NOT remove the need for a Samsung account: Tizen Studio
 * signs in to Samsung to create the certificate, on a PC. It only helps people who already have
 * one. Never describe this import as "no Samsung account needed" in app text or release notes.
 * The distributor certificate lists the TVs (DUIDs) it is valid for; one identity is saved per TV.
 */
object TizenStudioCertificate {
    enum class Part(val fileName: String) { AUTHOR("author.p12"), DISTRIBUTOR("distributor.p12") }

    private const val DEVICE_ID = "URN:tizen:deviceid="
    private const val URI_NAME = 6
    private val random = SecureRandom()

    /** Which of two .p12 files is the author one, by file name. Null when the names do not say. */
    fun authorFirst(firstName: String, secondName: String): Boolean? {
        fun kind(name: String) = when {
            name.contains("author", ignoreCase = true) -> Part.AUTHOR
            name.contains("distributor", ignoreCase = true) -> Part.DISTRIBUTOR
            else -> null
        }
        val first = kind(firstName)
        val second = kind(secondName)
        return when {
            first == Part.AUTHOR || second == Part.DISTRIBUTOR -> true
            first == Part.DISTRIBUTOR || second == Part.AUTHOR -> false
            else -> null
        }
    }

    /** The TVs a distributor certificate is valid for. */
    fun duids(distributorLeaf: X509Certificate): List<String> =
        distributorLeaf.subjectAlternativeNames.orEmpty()
            .filter { it.getOrNull(0) == URI_NAME }
            .mapNotNull { (it.getOrNull(1) as? String)?.takeIf { uri -> uri.startsWith(DEVICE_ID, ignoreCase = true) } }
            .map { it.substring(DEVICE_ID.length).trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * Reads both files and builds one identity per TV listed in the distributor certificate.
     * [distributorPassword] defaults to the author one, as in Tizen Studio. [authorities] supplies
     * Samsung's CA certificates; it is only called when a file lacks the CA above its certificate.
     */
    fun read(
        author: ByteArray,
        distributor: ByteArray,
        authorPassword: String?,
        distributorPassword: String? = null,
        authorities: () -> List<X509Certificate> = { emptyList() },
    ): List<SigningIdentity> {
        val authorBundle = open(author, authorPassword, Part.AUTHOR)
        val distributorBundle = open(distributor, distributorPassword ?: authorPassword, Part.DISTRIBUTOR)

        val distributorLeaf = leaf(distributorBundle)
        val duids = duids(distributorLeaf)
        if (duids.isEmpty()) {
            throw InstallerException(
                "This distributor certificate does not list any TV, so a Samsung TV will not accept it. " +
                    "It may be Tizen's default certificate. In Tizen Studio's Certificate Manager, create a " +
                    "Samsung certificate and add your TV's DUID, then import its author.p12 and distributor.p12."
            )
        }
        if (duids(leaf(authorBundle)).isNotEmpty()) {
            throw InstallerException("The two files look swapped: pick author.p12 as the author certificate and distributor.p12 as the distributor one.")
        }

        val cas by lazy(authorities)
        val authorChain = withIssuer(authorBundle, Part.AUTHOR) { cas }
        val distributorChain = withIssuer(distributorBundle, Part.DISTRIBUTOR) { cas }

        // One password for both bundles, as in the desktop installer's files.
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also(random::nextBytes))
        val base = SigningIdentity(
            authorCert = Base64.getEncoder().encodeToString(Pkcs12.write(authorBundle.privateKey, authorChain, password)),
            distributorCert = Base64.getEncoder().encodeToString(Pkcs12.write(distributorBundle.privateKey, distributorChain, password)),
            password = password,
            createdAt = Instant.now().toString(),
        )
        if (!base.hasValidAuthorChain()) {
            throw InstallerException("The author certificate could not be checked against Samsung's certificate authority.")
        }
        return duids.map { base.copy(duid = it) }
    }

    /** Reads and saves the certificate. Returns the identities saved, one per TV. */
    fun import(
        store: IdentityStore,
        author: ByteArray,
        distributor: ByteArray,
        authorPassword: String?,
        distributorPassword: String? = null,
        authorities: () -> List<X509Certificate> = { emptyList() },
    ): List<SigningIdentity> =
        read(author, distributor, authorPassword, distributorPassword, authorities).map { store.import(it.toJson()) }

    private fun open(bytes: ByteArray, password: String?, part: Part): KeyAndChain {
        if (password.isNullOrEmpty()) throw TizenStudioPasswordException(part, wrongPassword = false)
        val bundle = try {
            if (!macMatches(bytes, password)) throw TizenStudioPasswordException(part, wrongPassword = true)
            Pkcs12.read(bytes, password)
        } catch (e: TizenStudioPasswordException) {
            throw e
        } catch (e: InstallerException) {
            throw InstallerException("${part.fileName} has no private key. Pick the .p12 file from the Tizen Studio certificate profile folder.", e)
        } catch (e: Exception) {
            if (!looksLikePkcs12(bytes)) throw InstallerException("This is not a .p12 certificate file (${part.fileName} expected).", e)
            throw TizenStudioPasswordException(part, wrongPassword = true)
        }
        if (bundle.chain.isEmpty()) throw InstallerException("${part.fileName} has no certificate in it.")
        return bundle
    }

    /** False only when the file has a MAC and [password] does not match it. */
    private fun macMatches(bytes: ByteArray, password: String): Boolean {
        val pfx = runCatching { PKCS12PfxPdu(bytes) }.getOrNull() ?: return true
        if (!pfx.hasMac()) return true
        return pfx.isMacValid(BcPKCS12MacCalculatorBuilderProvider(BcDefaultDigestProvider.INSTANCE), password.toCharArray())
    }

    // PKCS#12 is a DER SEQUENCE.
    private fun looksLikePkcs12(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 0x30.toByte()

    /** The certificate that belongs to the bundle's private key. */
    private fun leaf(bundle: KeyAndChain): X509Certificate {
        val modulus = (bundle.privateKey as? RSAPrivateKey)?.modulus
        return bundle.chain.firstOrNull { (it.publicKey as? RSAPublicKey)?.modulus == modulus } ?: bundle.chain.first()
    }

    private fun issues(issuer: X509Certificate, cert: X509Certificate) =
        issuer !== cert && runCatching { cert.verify(issuer.publicKey); true }.getOrDefault(false)

    /** The leaf first, then the CA that issued it: from the file, or else from [authorities]. */
    private fun withIssuer(bundle: KeyAndChain, part: Part, authorities: () -> List<X509Certificate>): List<X509Certificate> {
        val leaf = leaf(bundle)
        val others = bundle.chain.filter { it !== leaf }
        if (others.any { issues(it, leaf) }) return listOf(leaf) + others
        val ca = authorities().firstOrNull { issues(it, leaf) }
            ?: throw InstallerException(
                "${part.fileName} was not issued by Samsung's TV certificate authority, so a Samsung TV will not accept it. " +
                    "In Tizen Studio's Certificate Manager, create a Samsung certificate (not a Tizen one)."
            )
        return listOf(leaf) + others + ca
    }
}
