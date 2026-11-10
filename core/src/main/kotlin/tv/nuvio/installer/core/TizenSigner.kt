// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A private key with its certificate chain (leaf first), as stored in a PKCS#12 bundle. */
class KeyAndChain(val privateKey: PrivateKey, val chain: List<X509Certificate>)

/**
 * Tizen widget signer (W3C widget digital signatures, as produced by Tizen Studio).
 * Port of tizen.js packageSigner.js; the output is byte-identical to the desktop Nuvio installer.
 */
object TizenSigner {
    private const val AUTHOR_PROP_DIGEST =
        "aXbSAVgmAz0GsBUeZ1UmNDRrxkWhDUVGb45dZcNRq429wX3X+x6kaXT3NdNDTSNVTU+ypkysPMGvQY10fG1EWQ=="
    private const val DISTRIBUTOR_PROP_DIGEST =
        "/r5npk2VVA46QFJnejgONBEh4BWtjrtu9x/IFeLksjWyGmB/cMWKSJWQl7aU3YRQRZ3AesG8gF7qGyvKX9Snig=="
    private const val XMLDSIG_NS = "http://www.w3.org/2000/09/xmldsig#"

    const val AUTHOR_SIGNATURE = "author-signature.xml"
    const val DISTRIBUTOR_SIGNATURE = "signature1.xml"

    class Entry(val name: String, val data: ByteArray)

    /** Signs [input] with the author and distributor identities and writes the result to [output]. */
    fun signWgt(input: File, output: File, author: KeyAndChain, distributor: KeyAndChain) {
        val files = ZipFile(input).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name != AUTHOR_SIGNATURE && it.name != DISTRIBUTOR_SIGNATURE }
                .map { entry -> Entry(entry.name, zip.getInputStream(entry).use { it.readBytes() }) }
                .toList()
        }
        val authorXml = signatureXml("AuthorSignature", files, author)
        val withAuthor = listOf(Entry(AUTHOR_SIGNATURE, authorXml)) + files
        val distributorXml = signatureXml("DistributorSignature", withAuthor, distributor)
        val signed = listOf(Entry(DISTRIBUTOR_SIGNATURE, distributorXml)) + withAuthor

        output.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            val folders = HashSet<String>()
            for (entry in signed) {
                // Mirror JSZip's createFolders behaviour: parent folders precede their files.
                val parts = entry.name.split('/')
                for (i in 1 until parts.size) {
                    val folder = parts.subList(0, i).joinToString("/") + "/"
                    if (folders.add(folder)) {
                        zip.putNextEntry(ZipEntry(folder))
                        zip.closeEntry()
                    }
                }
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.data)
                zip.closeEntry()
            }
        }
    }

    fun signatureXml(id: String, files: List<Entry>, key: KeyAndChain): ByteArray {
        val references = StringBuilder()
        for (file in files) references.append(reference(sha512Base64(file.data), encodeUriComponent(file.name), false))
        references.append(reference(if (id == "AuthorSignature") AUTHOR_PROP_DIGEST else DISTRIBUTOR_PROP_DIGEST, "#prop", true))

        val signedInfo = "<SignedInfo>\n" +
            "<CanonicalizationMethod Algorithm=\"http://www.w3.org/2001/10/xml-exc-c14n#\"></CanonicalizationMethod>\n" +
            "<SignatureMethod Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#rsa-sha512\"></SignatureMethod>\n" +
            references +
            "</SignedInfo>\n"

        // Exclusive canonicalization of <SignedInfo>: the only change is the inherited default
        // namespace declaration on the element itself. The content contains no characters that
        // need escaping (digests are base64, URIs are percent-encoded).
        val canonical = signedInfo.trimEnd('\n').replaceFirst("<SignedInfo>", "<SignedInfo xmlns=\"$XMLDSIG_NS\">")
        val signer = Signature.getInstance("SHA512withRSA")
        signer.initSign(key.privateKey)
        signer.update(canonical.toByteArray(Charsets.UTF_8))
        val signatureValue = Base64.getEncoder().encodeToString(signer.sign())

        val keyInfo = StringBuilder("<KeyInfo>\n<X509Data>")
        for (cert in key.chain) {
            val wrapped = wrap76(Base64.getEncoder().encodeToString(cert.encoded))
            keyInfo.append("\n<X509Certificate>").append(if (wrapped.startsWith("\n")) "" else "\n").append(wrapped).append("\n</X509Certificate>")
        }
        keyInfo.append("\n</X509Data>\n</KeyInfo>\n")

        val role = if (id == "AuthorSignature") "author" else "distributor"
        val xml = "<Signature xmlns=\"$XMLDSIG_NS\" Id=\"$id\">\n" +
            signedInfo +
            "<SignatureValue>\n${wrap76(signatureValue)}\n</SignatureValue>\n" +
            keyInfo +
            "<Object Id=\"prop\">" +
            "<SignatureProperties xmlns:dsp=\"http://www.w3.org/2009/xmldsig-properties\">" +
            "<SignatureProperty Id=\"profile\" Target=\"#$id\">" +
            "<dsp:Profile URI=\"http://www.w3.org/ns/widgets-digsig#profile\"></dsp:Profile>" +
            "</SignatureProperty>" +
            "<SignatureProperty Id=\"role\" Target=\"#$id\">" +
            "<dsp:Role URI=\"http://www.w3.org/ns/widgets-digsig#role-$role\"></dsp:Role>" +
            "</SignatureProperty>" +
            "<SignatureProperty Id=\"identifier\" Target=\"#$id\">" +
            "<dsp:Identifier></dsp:Identifier></SignatureProperty></SignatureProperties></Object>\n" +
            "</Signature>\n"
        return xml.toByteArray(Charsets.UTF_8)
    }

    private fun reference(digest: String, uri: String, prop: Boolean): String {
        val transforms = if (prop) {
            "<Transforms>\n<Transform Algorithm=\"http://www.w3.org/2006/12/xml-c14n11\"></Transform>\n</Transforms>\n"
        } else ""
        return "<Reference URI=\"$uri\">\n" +
            transforms +
            "<DigestMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#sha512\"></DigestMethod>\n" +
            "<DigestValue>${wrap76(digest)}</DigestValue>\n" +
            "</Reference>\n"
    }

    private fun sha512Base64(data: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(data))

    /** Equivalent of JavaScript `s.replace(/(.{76})/g, '$1\n')`. */
    internal fun wrap76(value: String): String {
        val sb = StringBuilder(value.length + value.length / 76 + 1)
        var i = 0
        while (i + 76 <= value.length) {
            sb.append(value, i, i + 76).append('\n')
            i += 76
        }
        sb.append(value, i, value.length)
        return sb.toString()
    }

    /** Equivalent of JavaScript `encodeURIComponent`. */
    internal fun encodeUriComponent(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }
}
