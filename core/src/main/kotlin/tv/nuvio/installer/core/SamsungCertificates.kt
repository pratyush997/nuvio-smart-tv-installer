// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Creates Samsung TV signing identities the same way Tizen Studio's Samsung Certificate Extension
 * does: generate key pairs, send CSRs to Samsung's VD CA with the user's Samsung account token,
 * and bundle the issued certificates with Samsung's intermediate CAs.
 */
class SamsungCertificates(private val caDirectory: File) {
    companion object {
        /**
         * Builds the identity for a new distributor certificate: the author bundle and password of
         * [base] unchanged, the distributor bundle new. Checks that Samsung listed every TV asked for.
         */
        fun assembleDistributor(
            base: SigningIdentity,
            keys: KeyPair,
            distributorPem: String,
            duid: String,
            partnerCa: X509Certificate,
            expectedDuids: List<String>? = null,
        ): SigningIdentity {
            val cert = runCatching { parseCertificate(distributorPem.toByteArray()) }.getOrElse {
                throw InstallerException("Samsung did not return a valid distributor certificate: ${distributorPem.take(300)}")
            }
            val listed = TizenStudioCertificate.duids(cert)
            val missing = (expectedDuids ?: listOf(duid)).filter { it !in listed }
            if (missing.isNotEmpty()) {
                throw InstallerException("Samsung's distributor certificate does not list ${missing.joinToString()}. The certificate was not saved; retry.")
            }
            return base.copy(
                distributorCert = Base64.getEncoder().encodeToString(Pkcs12.write(keys.private, listOf(cert, partnerCa), base.password)),
                distributorXML = Base64.getEncoder().encodeToString(distributorPem.toByteArray()),
                duid = duid,
                createdAt = Instant.now().toString(),
                lastSuccessfulInstallAt = null,
                lastSuccessfulPackageId = null,
            )
        }

        private const val EXTENSION_INFO_URL = "https://download.tizen.org/sdk/tizenstudio/official/extension_info.xml"
        private const val FALLBACK_EXTENSION_URL = "https://download.tizen.org/sdk/extensions/tizen-certificate-extension_2.0.70.zip"
        private const val AUTHORS_URL = "https://svdca.samsungqbe.com/apis/v3/authors"
        private const val DISTRIBUTORS_URL = "https://svdca.samsungqbe.com/apis/v3/distributors"
        const val AUTHOR_CA = "vd_tizen_dev_author_ca.cer"
        const val PARTNER_CA = "vd_tizen_dev_partner2.crt"
        const val PUBLIC_CA = "vd_tizen_dev_public2.crt"
        private val REQUIRED = listOf(AUTHOR_CA, PARTNER_CA, PUBLIC_CA)
        private const val MARKER = ".extension"

        /** Samsung's reply to an author request, as a certificate issued by [authorCa]; throws when it is not one. */
        internal fun checkedAuthor(reply: String, authorCa: X509Certificate): X509Certificate {
            val cert = runCatching { parseCertificate(reply.toByteArray()) }.getOrElse {
                throw InstallerException("Samsung did not return a valid author certificate: ${reply.take(300)}")
            }
            if (runCatching { cert.verify(authorCa.publicKey); true }.getOrDefault(false).not()) {
                throw InstallerException(
                    "Samsung issued an author certificate that does not match the downloaded certificate authorities. " +
                        "Nothing was saved. Try again."
                )
            }
            return cert
        }

        fun parseCertificate(bytes: ByteArray): X509Certificate {
            val text = String(bytes, Charsets.ISO_8859_1)
            val pem = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").find(text)?.value
            val input = pem?.toByteArray(Charsets.US_ASCII) ?: bytes
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(input)) as X509Certificate
        }
    }

    private fun cached() = REQUIRED.all { File(caDirectory, it).isFile }

    /** Makes sure Samsung's current intermediate CA certificates are available locally. */
    fun ensureAuthorities(events: InstallerEvents) {
        val repository = try {
            latestExtensionUrl(events)
        } catch (e: Exception) {
            if (cached()) {
                events.info("Could not check for newer Samsung certificate authorities; using the cached ones. ${e.message}")
                return
            }
            events.info("Could not read Samsung's extension index (${e.message}); using the known extension package.")
            FALLBACK_EXTENSION_URL
        }
        val marker = File(caDirectory, MARKER)
        if (cached() && marker.isFile && marker.readText().trim() == repository) return

        events.info("Downloading Samsung's certificate files. Samsung only offers them inside a large package (about 45 MB), so this can take a few minutes. It only happens once.")
        val archive = File(caDirectory, "extension-${System.currentTimeMillis()}.zip")
        try {
            Http.download(repository, archive, "Samsung certificate files", events)
            val addon = firstEntry(archive.readBytes(), ".zip") ?: throw InstallerException("The Samsung certificate extension did not contain an add-on package.")
            val jar = firstEntry(addon, ".jar") ?: throw InstallerException("The Samsung certificate extension did not contain a certificate manager.")
            val certs = allEntries(jar) { it.endsWith(".crt", true) || it.endsWith(".cer", true) }
            if (certs.isEmpty()) throw InstallerException("The Samsung certificate extension did not contain certificate authorities.")
            caDirectory.mkdirs()
            marker.delete()
            caDirectory.listFiles { f -> f.name.endsWith(".crt", true) || f.name.endsWith(".cer", true) }?.forEach { it.delete() }
            for ((name, data) in certs) File(caDirectory, name.substringAfterLast('/')).writeBytes(data)
            if (!cached()) throw InstallerException("The Samsung certificate extension is missing ${REQUIRED.filterNot { File(caDirectory, it).isFile }}.")
            marker.writeText(repository)
        } finally {
            archive.delete()
        }
    }

    private fun latestExtensionUrl(events: InstallerEvents): String {
        val xml = Http.getText(EXTENSION_INFO_URL, events = events)
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val extensions = doc.getElementsByTagName("extension")
        for (i in 0 until extensions.length) {
            val extension = extensions.item(i) as Element
            val name = extension.getElementsByTagName("name").item(0)?.textContent?.trim()
            if (name == "Samsung Certificate Extension") {
                val repo = extension.getElementsByTagName("repository").item(0)?.textContent?.trim()
                if (!repo.isNullOrEmpty()) return repo
            }
        }
        throw InstallerException("Samsung certificate extension info did not contain a repository.")
    }

    private fun firstEntry(zip: ByteArray, suffix: String): ByteArray? =
        allEntries(zip) { it.endsWith(suffix, ignoreCase = true) }.firstOrNull()?.second

    private fun allEntries(zip: ByteArray, accept: (String) -> Boolean): List<Pair<String, ByteArray>> {
        val result = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (!entry.isDirectory && accept(entry.name)) result += entry.name to input.readBytes()
            }
        }
        return result
    }

    private fun ca(name: String) = parseCertificate(File(caDirectory, name).readBytes())

    /** Samsung's CA certificates (downloaded first if needed), to complete imported certificate files. */
    fun authorities(events: InstallerEvents): List<X509Certificate> {
        ensureAuthorities(events)
        return caDirectory.listFiles { f -> f.name.endsWith(".crt", true) || f.name.endsWith(".cer", true) }.orEmpty()
            .mapNotNull { runCatching { parseCertificate(it.readBytes()) }.getOrNull() }
    }

    /** Requests a new author + distributor certificate pair for the TV with [duid]. */
    fun createIdentity(access: SamsungAccess, duid: String, events: InstallerEvents): SigningIdentity {
        if (access.accessToken.isBlank() || access.userId.isBlank()) {
            throw InstallerException("Samsung did not return valid authorization data. Try signing in again.")
        }
        ensureAuthorities(events)
        events.info("Creating the Samsung certificate for this TV. This can take 30-60 seconds.")

        val authorKeys = rsaKeyPair()
        val distributorKeys = rsaKeyPair()

        val authorPem = requestAuthor(access, authorKeys, events)
        val distributorPem = requestDistributor(access, listOf(duid), distributorKeys, events)

        val authorCa = ca(AUTHOR_CA)
        val partnerCa = ca(PARTNER_CA)
        val authorCert = checkedAuthor(authorPem, authorCa)
        val distributorCert = runCatching { parseCertificate(distributorPem.toByteArray()) }.getOrElse {
            throw InstallerException("Samsung did not return a valid distributor certificate: ${distributorPem.take(300)}")
        }

        val passwordBytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(passwordBytes)
        val identity = SigningIdentity(
            authorCert = Base64.getEncoder().encodeToString(Pkcs12.write(authorKeys.private, listOf(authorCert, authorCa), password)),
            distributorCert = Base64.getEncoder().encodeToString(Pkcs12.write(distributorKeys.private, listOf(distributorCert, partnerCa), password)),
            distributorXML = Base64.getEncoder().encodeToString(distributorPem.toByteArray()),
            password = password,
            duid = duid,
            createdAt = Instant.now().toString(),
        )
        if (!identity.hasValidAuthorChain()) {
            throw InstallerException("The new Samsung certificate bundle failed its self-check; it was not saved. Retry the install.")
        }
        return identity
    }

    /**
     * Asks Samsung for a new distributor certificate listing [duids], for the author of [base].
     * The author certificate and key stay byte-identical, so apps it installed keep updating.
     * Returns an identity for the first DUID; [SharedProfile.identitiesToSave] makes the rest.
     */
    fun createDistributor(access: SamsungAccess, duids: List<String>, base: SigningIdentity, events: InstallerEvents): SigningIdentity {
        if (access.accessToken.isBlank() || access.userId.isBlank()) {
            throw InstallerException("Samsung did not return valid authorization data. Try signing in again.")
        }
        require(duids.isNotEmpty() && duids.size <= SharedProfile.MAX_TVS) { "1 to ${SharedProfile.MAX_TVS} TVs" }
        ensureAuthorities(events)
        events.info(
            if (duids.size == 1) "Creating the Samsung certificate for this TV with your existing author key."
            else "Creating one Samsung certificate for your ${duids.size} TVs, with your existing author key."
        )
        val distributorKeys = rsaKeyPair()
        val distributorPem = requestDistributor(access, duids, distributorKeys, events)
        return assembleDistributor(base, distributorKeys, distributorPem, duids.first(), ca(PARTNER_CA), expectedDuids = duids)
    }

    /**
     * Checks that Samsung accepts [access], without a TV: asks for an author certificate (which
     * needs no TV) and checks it the way [createIdentity] does. The certificate is thrown away.
     */
    fun checkAccess(access: SamsungAccess, events: InstallerEvents) {
        if (access.accessToken.isBlank() || access.userId.isBlank()) {
            throw InstallerException("Samsung did not return valid authorization data. Try signing in again.")
        }
        ensureAuthorities(events)
        events.info("Asking Samsung for a test certificate. It is not saved.")
        checkedAuthor(requestAuthor(access, rsaKeyPair(), events), ca(AUTHOR_CA))
    }

    private fun requestAuthor(access: SamsungAccess, keys: KeyPair, events: InstallerEvents): String {
        val csr = JcaPKCS10CertificationRequestBuilder(
            X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "Nuvio").build(), keys.public
        ).build(JcaContentSignerBuilder("SHA512withRSA").build(keys.private))
        val common = listOf("access_token" to access.accessToken, "user_id" to access.userId, "platform" to "VD")
        return Http.postMultipart(
            AUTHORS_URL, common,
            listOf(Http.FilePart("csr", "author.csr", "application/octet-stream", pem("CERTIFICATE REQUEST", csr.encoded))),
            "Samsung author certificate request", events
        )
    }

    private fun requestDistributor(access: SamsungAccess, duids: List<String>, keys: KeyPair, events: InstallerEvents): String {
        val names = listOf(GeneralName(GeneralName.uniformResourceIdentifier, "URN:tizen:packageid=")) +
            duids.map { GeneralName(GeneralName.uniformResourceIdentifier, "URN:tizen:deviceid=$it") }
        val extensions = ExtensionsGenerator().apply {
            addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        }.generate()
        val csr = JcaPKCS10CertificationRequestBuilder(
            X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "TizenSDK").addRDN(BCStyle.EmailAddress, access.email).build(),
            keys.public
        ).addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions)
            .build(JcaContentSignerBuilder("SHA512withRSA").build(keys.private))
        val common = listOf("access_token" to access.accessToken, "user_id" to access.userId, "platform" to "VD")
        return Http.postMultipart(
            DISTRIBUTORS_URL, common + listOf("privilege_level" to "Partner", "developer_type" to "Individual"),
            listOf(Http.FilePart("csr", "distributor.csr", "application/octet-stream", pem("CERTIFICATE REQUEST", csr.encoded))),
            "Samsung distributor certificate request", events
        )
    }

    private fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun pem(type: String, der: ByteArray): ByteArray {
        val body = Base64.getMimeEncoder(64, "\r\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\r\n$body\r\n-----END $type-----\r\n".toByteArray(Charsets.US_ASCII)
    }
}
