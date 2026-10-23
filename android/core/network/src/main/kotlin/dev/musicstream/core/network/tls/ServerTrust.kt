package dev.musicstream.core.network.tls

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Provides the SPKI pin of the paired server ("sha256/<base64>"), if any. */
fun interface PinProvider {
    fun currentPin(): String?
}

internal fun spkiPin(cert: X509Certificate): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
    return "sha256/" + Base64.getEncoder().encodeToString(digest)
}

/**
 * Trusts a certificate when EITHER its public key matches the pin obtained
 * during pairing (our self-signed home server) OR the platform CA store
 * validates it (podcast CDNs, Radio Browser...).
 *
 * Pinning the key is stronger than CA validation for the home server: no CA
 * can issue a certificate an attacker could use, and no domain is needed.
 */
class ServerTrustManager(private val pins: PinProvider) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as KeyStore?)
        trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty()) throw CertificateException("empty certificate chain")
        val pin = pins.currentPin()
        if (pin != null && spkiPin(chain[0]) == pin) return
        system.checkServerTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/**
 * The home server is reached by IP, which a self-signed certificate cannot
 * name; when the presented key matches the pin the hostname is irrelevant.
 * Everything else gets standard hostname verification.
 */
class ServerHostnameVerifier(private val pins: PinProvider) : HostnameVerifier {
    private val default = HttpsURLConnection.getDefaultHostnameVerifier()

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val pin = pins.currentPin()
        if (pin != null) {
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            if (leaf != null && spkiPin(leaf) == pin) return true
        }
        return default.verify(hostname, session)
    }
}
