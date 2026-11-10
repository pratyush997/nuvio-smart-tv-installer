package tv.nuvio.installer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.pkcs.PKCS12PfxPdu
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.PKCS12SafeBag
import org.bouncycastle.pkcs.PKCS12SafeBagFactory
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.pkcs.bc.BcPKCS12MacCalculatorBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder
import org.bouncycastle.asn1.DERBMPString
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * A Samsung signing identity for one TV, in the same JSON format the desktop Nuvio TV Installer
 * stores under `samsung-certificates/` (base64 PKCS#12 bundles plus their password), so files
 * can be copied between the two.
 */
@Serializable
data class SigningIdentity(
    val authorCert: String,
    val distributorCert: String,
    val distributorXML: String = "",
    val password: String,
    val duid: String = "",
    val createdAt: String = "",
    val lastSuccessfulInstallAt: String? = null,
    val lastSuccessfulPackageId: String? = null,
    val profileName: String? = null,
) {
    val isUsable: Boolean
        get() = authorCert.isNotBlank() && distributorCert.isNotBlank() && password.isNotBlank() && profileName.isNullOrBlank()

    /** Same fingerprint the desktop installer uses: sha256 of the author bundle. */
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(authorCert.toByteArray()).joinToString("") { "%02x".format(it) }

    fun author(): KeyAndChain = Pkcs12.read(Base64.getDecoder().decode(authorCert), password)
    fun distributor(): KeyAndChain = Pkcs12.read(Base64.getDecoder().decode(distributorCert), password)

    /** True when the author bundle's leaf certificate verifies against a CA certificate in the same bundle. */
    fun hasValidAuthorChain(): Boolean = runCatching {
        val certs = author().chain
        certs.any { issuer -> certs.any { cert -> cert !== issuer && runCatching { cert.verify(issuer.publicKey); true }.getOrDefault(false) } }
    }.getOrDefault(false)

    companion object {
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        fun parse(text: String): SigningIdentity = json.decodeFromString(serializer(), text)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)
}

/** Stores identities as individual JSON files, mirroring the desktop installer's layout. */
class IdentityStore(val directory: File) {
    init { directory.mkdirs() }

    private fun fileForDuid(duid: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(duid.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "duid-$hash.json")
    }

    fun all(): List<Pair<File, SigningIdentity>> =
        directory.listFiles { f -> f.name.endsWith(".json", ignoreCase = true) }.orEmpty()
            .mapNotNull { file -> runCatching { file to SigningIdentity.parse(file.readText()) }.getOrNull() }

    /** Usable identities for [duid], most preferred first, de-duplicated by author certificate. */
    fun candidatesFor(duid: String): List<SigningIdentity> {
        val canonical = fileForDuid(duid)
        val seen = HashSet<String>()
        return all()
            .filter { (_, identity) -> identity.isUsable && identity.duid == duid }
            .sortedWith(
                compareBy<Pair<File, SigningIdentity>> { if (it.first == canonical) 0 else 1 }
                    .thenByDescending { it.second.lastSuccessfulInstallAt ?: "" }
                    .thenByDescending { it.second.createdAt }
            )
            .map { it.second }
            .filter { seen.add(it.fingerprint) }
    }

    /** Saves [identity] as the canonical identity for its DUID (atomically). */
    fun save(identity: SigningIdentity): File {
        require(identity.duid.isNotBlank()) { "Identity has no DUID" }
        val target = fileForDuid(identity.duid)
        val temp = File(directory, "${target.name}.${System.nanoTime()}.tmp")
        temp.writeText(identity.toJson())
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        return target
    }

    /** Imports a desktop-installer identity file. Returns the imported identity. */
    fun import(text: String): SigningIdentity {
        val identity = try {
            SigningIdentity.parse(text)
        } catch (e: Exception) {
            throw InstallerException("This file is not a Nuvio installer certificate file (${e.message}).", e)
        }
        if (!identity.isUsable) throw InstallerException("This certificate file does not contain a usable Samsung signing identity.")
        if (identity.duid.isBlank()) throw InstallerException("This certificate file does not say which TV (DUID) it belongs to.")
        try {
            identity.author(); identity.distributor()
        } catch (e: Exception) {
            throw InstallerException("The certificates in this file could not be opened: ${e.message}", e)
        }
        // Keep an existing, different identity for the same TV as a fallback file instead of overwriting it.
        val canonical = fileForDuid(identity.duid)
        if (canonical.exists()) {
            val existing = runCatching { SigningIdentity.parse(canonical.readText()) }.getOrNull()
            if (existing != null && existing.fingerprint != identity.fingerprint) {
                canonical.copyTo(File(directory, "duid-backup-${System.currentTimeMillis()}.json"))
            }
        }
        save(identity)
        return identity
    }
}

/** PKCS#12 helpers built on the BouncyCastle lightweight API so they behave the same on Android and the JVM. */
object Pkcs12 {
    private val provider = BouncyCastleProvider()
    private val certConverter = JcaX509CertificateConverter()

    fun read(der: ByteArray, password: String): KeyAndChain {
        val pfx = PKCS12PfxPdu(der)
        val pwd = password.toCharArray()
        val decryptor = JcePKCSPBEInputDecryptorProviderBuilder().setProvider(provider).build(pwd)
        var key: PrivateKey? = null
        val certs = ArrayList<X509Certificate>()
        for (info in pfx.contentInfos) {
            val factory = if (info.contentType == PKCSObjectIdentifiers.encryptedData) {
                PKCS12SafeBagFactory(info, decryptor)
            } else {
                PKCS12SafeBagFactory(info)
            }
            for (bag in factory.safeBags) {
                when (bag.type) {
                    PKCSObjectIdentifiers.certBag -> certs.add(certConverter.getCertificate(bag.bagValue as X509CertificateHolder))
                    PKCSObjectIdentifiers.pkcs8ShroudedKeyBag -> {
                        val info8 = (bag.bagValue as PKCS8EncryptedPrivateKeyInfo).decryptPrivateKeyInfo(decryptor)
                        key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(info8.encoded))
                    }
                    PKCSObjectIdentifiers.keyBag -> {
                        val info8 = bag.bagValue as org.bouncycastle.asn1.pkcs.PrivateKeyInfo
                        key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(info8.encoded))
                    }
                }
            }
        }
        return KeyAndChain(key ?: throw InstallerException("The certificate bundle does not contain a private key."), certs)
    }

    /** Builds a PKCS#12 bundle (3DES key encryption, SHA-1 MAC — the widely compatible profile). */
    fun write(key: PrivateKey, chain: List<X509Certificate>, password: String): ByteArray {
        val pwd = password.toCharArray()
        val extUtils = JcaX509ExtensionUtils()
        val localKeyId: SubjectKeyIdentifier = extUtils.createSubjectKeyIdentifier(chain.first().publicKey)
        val certBags = chain.mapIndexed { index, cert ->
            val builder = JcaPKCS12SafeBagBuilder(cert)
            if (index == 0) {
                builder.addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_friendlyName, DERBMPString("UserCertificate"))
                builder.addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_localKeyId, localKeyId)
            }
            builder.build()
        }
        val encryptor = JcePKCSPBEOutputEncryptorBuilder(PKCSObjectIdentifiers.pbeWithSHAAnd3_KeyTripleDES_CBC)
            .setProvider(provider).build(pwd)
        val keyBag = JcaPKCS12SafeBagBuilder(key, encryptor)
            .addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_friendlyName, DERBMPString("UserCertificate"))
            .addBagAttribute(PKCSObjectIdentifiers.pkcs_9_at_localKeyId, localKeyId)
            .build()
        val builder = PKCS12PfxPduBuilder()
        certBags.forEach { builder.addData(it) }
        val pfx = builder.addData(keyBag).build(BcPKCS12MacCalculatorBuilder(), pwd)
        return pfx.encoded
    }

}
