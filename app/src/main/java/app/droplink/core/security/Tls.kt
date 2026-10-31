package app.droplink.core.security

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

class PeerNotTrustedException(message: String) : CertificateException(message)

/**
 * Trusts exactly one certificate when [expectedFingerprint] is set (QR mode: the fingerprint came
 * from the QR code). With no expected fingerprint (LAN / Wi-Fi Direct) any self-signed certificate
 * is accepted at the TLS layer and the user verifies the 4-digit [PairingCode] instead.
 */
class PinningTrustManager(private val expectedFingerprint: String?) : X509ExtendedTrustManager() {

    private fun check(chain: Array<out X509Certificate>?) {
        if (chain == null || chain.isEmpty()) throw PeerNotTrustedException("The other phone sent no certificate")
        val expected = expectedFingerprint ?: return
        val actual = Fingerprints.of(chain[0])
        if (!actual.equals(expected, ignoreCase = true)) {
            throw PeerNotTrustedException("The other phone's security key doesn't match the QR code")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Always presents this install's single EC certificate, as client and as server. */
class SingleKeyManager(private val identity: Identity) : X509ExtendedKeyManager() {
    private fun ok(keyType: String?): Boolean = keyType == null || keyType.uppercase().startsWith("EC")
    private fun okAny(keyTypes: Array<out String>?): Boolean = keyTypes == null || keyTypes.any { ok(it) }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (ok(keyType)) arrayOf(ALIAS) else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (okAny(keyType)) ALIAS else null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (ok(keyType)) arrayOf(ALIAS) else null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (ok(keyType)) ALIAS else null

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        if (okAny(keyType)) ALIAS else null

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        if (ok(keyType)) ALIAS else null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey

    private companion object {
        const val ALIAS = "droplink"
    }
}

object Tls {
    private val PREFERRED_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

    fun context(identity: Identity, expectedPeerFingerprint: String?): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(SingleKeyManager(identity)), arrayOf(PinningTrustManager(expectedPeerFingerprint)), SecureRandom())
        }

    fun configure(socket: SSLSocket) {
        val enabled = PREFERRED_PROTOCOLS.filter { it in socket.supportedProtocols }
        if (enabled.isEmpty()) throw javax.net.ssl.SSLException("TLS 1.2+ is not available on this phone")
        socket.enabledProtocols = enabled.toTypedArray()
    }

    fun configure(server: SSLServerSocket) {
        val enabled = PREFERRED_PROTOCOLS.filter { it in server.supportedProtocols }
        if (enabled.isEmpty()) throw javax.net.ssl.SSLException("TLS 1.2+ is not available on this phone")
        server.enabledProtocols = enabled.toTypedArray()
        server.needClientAuth = true
    }

    fun peerFingerprint(socket: SSLSocket): String = Fingerprints.of(socket.session.peerCertificates[0])
}
