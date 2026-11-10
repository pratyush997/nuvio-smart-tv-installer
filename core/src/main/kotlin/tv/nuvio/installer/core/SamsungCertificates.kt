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
        private const val EXTENSION_INFO_URL = "https://download.tizen.org/sdk/tizenstudio/official/extension_info.xml"
        private const val FALLBACK_EXTENSION_URL = "https://download.tizen.org/sdk/extensions/tizen-certificate-extension_2.0.70.zip"
        private const val AUTHORS_URL = "https://svdca.samsungqbe.com/apis/v3/authors"
        private const val DISTRIBUTORS_URL = "https://svdca.samsungqbe.com/apis/v3/distributors"
        const val AUTHOR_CA = "vd_tizen_dev_author_ca.cer"
        const val PARTNER_CA = "vd_tizen_dev_partner2.crt"
        const val PUBLIC_CA = "vd_tizen_dev_public2.crt"
        private val REQUIRED = listOf(AUTHOR_CA, PARTNER_CA, PUBLIC_CA)
        private const val MARKER = ".extension"

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
            latestExtensionUrl()
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

        events.info("Downloading Samsung certificate authorities.")
        val archive = File(caDirectory, "extension-${System.currentTimeMillis()}.zip")
        try {
            Http.download(repository, archive, "Samsung certificate authorities", events)
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

    private fun latestExtensionUrl(): String {
        val xml = Http.getText(EXTENSION_INFO_URL)
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

    /** Requests a new author + distributor certificate pair for the TV with [duid]. */
    fun createIdentity(access: SamsungAccess, duid: String, events: InstallerEvents): SigningIdentity {
        if (access.accessToken.isBlank() || access.userId.isBlank()) {
            throw InstallerException("Samsung did not return valid authorization data. Try signing in again.")
        }
        ensureAuthorities(events)
        events.info("Creating the Samsung certificate for this TV. This can take 30-60 seconds.")

        val authorKeys = rsaKeyPair()
        val distributorKeys = rsaKeyPair()

        val authorCsr = JcaPKCS10CertificationRequestBuilder(
            X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "Nuvio").build(), authorKeys.public
        ).build(JcaContentSignerBuilder("SHA512withRSA").build(authorKeys.private))

        val altNames = GeneralNames(
            arrayOf(
                GeneralName(GeneralName.uniformResourceIdentifier, "URN:tizen:packageid="),
                GeneralName(GeneralName.uniformResourceIdentifier, "URN:tizen:deviceid=$duid"),
            )
        )
        val extensions = ExtensionsGenerator().apply { addExtension(Extension.subjectAlternativeName, false, altNames) }.generate()
        val distributorCsr = JcaPKCS10CertificationRequestBuilder(
            X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "TizenSDK").addRDN(BCStyle.EmailAddress, access.email).build(),
            distributorKeys.public
        ).addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions)
            .build(JcaContentSignerBuilder("SHA512withRSA").build(distributorKeys.private))

        val common = listOf("access_token" to access.accessToken, "user_id" to access.userId, "platform" to "VD")
        val authorPem = Http.postMultipart(
            AUTHORS_URL, common,
            listOf(Http.FilePart("csr", "author.csr", "application/octet-stream", pem("CERTIFICATE REQUEST", authorCsr.encoded))),
            "Samsung author certificate request"
        )
        val distributorPem = Http.postMultipart(
            DISTRIBUTORS_URL, common + listOf("privilege_level" to "Partner", "developer_type" to "Individual"),
            listOf(Http.FilePart("csr", "distributor.csr", "application/octet-stream", pem("CERTIFICATE REQUEST", distributorCsr.encoded))),
            "Samsung distributor certificate request"
        )

        val authorCert = runCatching { parseCertificate(authorPem.toByteArray()) }.getOrElse {
            throw InstallerException("Samsung did not return a valid author certificate: ${authorPem.take(300)}")
        }
        val distributorCert = runCatching { parseCertificate(distributorPem.toByteArray()) }.getOrElse {
            throw InstallerException("Samsung did not return a valid distributor certificate: ${distributorPem.take(300)}")
        }
        val authorCa = ca(AUTHOR_CA)
        val partnerCa = ca(PARTNER_CA)
        if (runCatching { authorCert.verify(authorCa.publicKey); true }.getOrDefault(false).not()) {
            throw InstallerException(
                "Samsung issued an author certificate that does not match the downloaded certificate authorities. " +
                    "The certificate was not saved; retry the install."
            )
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

    private fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun pem(type: String, der: ByteArray): ByteArray {
        val body = Base64.getMimeEncoder(64, "\r\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\r\n$body\r\n-----END $type-----\r\n".toByteArray(Charsets.US_ASCII)
    }
}
