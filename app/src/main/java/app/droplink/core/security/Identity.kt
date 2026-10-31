package app.droplink.core.security

import app.droplink.core.protocol.Checksums
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.TimeUnit

/** This install's TLS identity: an EC P-256 key and a self-signed certificate. */
class Identity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    /** Lower-case hex SHA-256 of the DER certificate; this is what peers pin. */
    val fingerprint: String = Fingerprints.of(certificate)
}

object Fingerprints {
    fun of(cert: Certificate): String = Checksums.sha256Hex(cert.encoded)

    /** "ab12 cd34 …" — first [groups] groups, for display. */
    fun short(fingerprint: String, groups: Int = 4): String = fingerprint.take(groups * 4).chunked(4).joinToString(" ")
}

/**
 * Short authentication string shown on both phones for LAN / Wi-Fi Direct: derived from BOTH
 * certificate fingerprints, order-independent, so a man-in-the-middle (who must present his own
 * certificate to each side) produces different codes on the two screens.
 */
object PairingCode {
    fun of(fingerprintA: String, fingerprintB: String): String {
        val a = fingerprintA.lowercase()
        val b = fingerprintB.lowercase()
        val (first, second) = if (a <= b) a to b else b to a
        val digest = Checksums.sha256().digest(("droplink-pair-v1|$first|$second").toByteArray(Charsets.UTF_8))
        val n = ((digest[0].toLong() and 0xFF) shl 24) or ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or (digest[3].toLong() and 0xFF)
        return String.format(java.util.Locale.ROOT, "%04d", n % 10_000)
    }
}

object IdentityFactory {
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** Generates a fresh key pair + self-signed certificate (valid ~20 years). */
    fun generate(commonName: String = "DropLink"): Identity {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=" + commonName.replace(Regex("[,=+<>#;\"\\\\]"), "_").take(60).ifBlank { "DropLink" })
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
            Date(now - TimeUnit.DAYS.toMillis(2)), // tolerate clock skew between phones
            Date(now + TimeUnit.DAYS.toMillis(365L * 20)),
            subject,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return Identity(keyPair.private, cert)
    }
}

/** Persists the identity in a PKCS#12 file in app-private storage (one per install). */
class IdentityStore(private val file: File) {
    private val password = "droplink".toCharArray() // file lives in app-private storage; password is not a secret
    private val alias = "droplink"

    @Volatile
    private var cached: Identity? = null

    @Synchronized
    fun get(): Identity {
        cached?.let { return it }
        val loaded = runCatching { load() }.getOrNull()
        val identity = loaded ?: IdentityFactory.generate().also { save(it) }
        cached = identity
        return identity
    }

    private fun load(): Identity? {
        if (!file.exists()) return null
        val ks = KeyStore.getInstance("PKCS12")
        file.inputStream().use { ks.load(it, password) }
        val key = ks.getKey(alias, password) as? PrivateKey ?: return null
        val cert = ks.getCertificate(alias) as? X509Certificate ?: return null
        return Identity(key, cert)
    }

    private fun save(identity: Identity) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, identity.privateKey, password, arrayOf<Certificate>(identity.certificate))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { ks.store(it, password) }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
