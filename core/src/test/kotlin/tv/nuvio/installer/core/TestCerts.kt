// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Pratyush (pratyush997) and contributors

package tv.nuvio.installer.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/** Builds throwaway certificates and signing identities for tests. */
object TestCerts {
    private val serial = AtomicLong(System.nanoTime())
    private val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }

    fun keys(): KeyPair = generator.generateKeyPair()

    fun cert(
        subject: String,
        issuer: String,
        keys: KeyPair,
        signer: PrivateKey,
        notAfter: Instant = Instant.now().plus(Duration.ofDays(365)),
        ca: Boolean = false,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"), BigInteger.valueOf(serial.incrementAndGet()),
            Date.from(Instant.now().minus(Duration.ofDays(1))), Date.from(notAfter),
            X500Name("CN=$subject"), keys.public,
        )
        if (ca) builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        return JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(signer)))
    }

    /** A PKCS#12 bundle (base64) holding a leaf signed by its own CA. */
    fun bundle(name: String, leafNotAfter: Instant, caNotAfter: Instant, password: String): String {
        val caKeys = keys()
        val leafKeys = keys()
        val ca = cert("$name CA", "$name CA", caKeys, caKeys.private, caNotAfter, ca = true)
        val leaf = cert(name, "$name CA", leafKeys, caKeys.private, leafNotAfter)
        return Base64.getEncoder().encodeToString(Pkcs12.write(leafKeys.private, listOf(leaf, ca), password))
    }

    fun identity(
        duid: String = "DUID-1",
        authorNotAfter: Instant = Instant.now().plus(Duration.ofDays(3650)),
        distributorNotAfter: Instant = Instant.now().plus(Duration.ofDays(3650)),
        caNotAfter: Instant = Instant.now().plus(Duration.ofDays(7300)),
        lastSuccessfulInstallAt: String? = null,
    ): SigningIdentity {
        val password = "secret"
        return SigningIdentity(
            authorCert = bundle("Author", authorNotAfter, caNotAfter, password),
            distributorCert = bundle("Distributor", distributorNotAfter, caNotAfter, password),
            password = password,
            duid = duid,
            createdAt = Instant.now().toString(),
            lastSuccessfulInstallAt = lastSuccessfulInstallAt,
        )
    }
}
