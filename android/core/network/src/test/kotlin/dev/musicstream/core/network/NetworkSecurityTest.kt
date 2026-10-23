package dev.musicstream.core.network

import dev.musicstream.core.network.session.TokenDto
import dev.musicstream.core.network.session.TokenRefreshApi
import dev.musicstream.core.network.session.TokenStorage
import dev.musicstream.core.network.tls.ServerHostnameVerifier
import dev.musicstream.core.network.tls.ServerTrustManager
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/** Exercises TLS pinning, virtual-host routing and token refresh against a real HTTPS server. */
class NetworkSecurityTest {
    private val server = MockWebServer()
    // Self-signed, issued for a hostname the client never uses (like musicserver's cert).
    private val cert = HeldCertificate.Builder().commonName("musicstream").addSubjectAlternativeName("musicstream").build()
    private val pin = "sha256/" + Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(cert.certificate.publicKey.encoded),
    )

    @Before fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        server.start()
    }

    @After fun stop() = server.close()

    private fun pinnedClient(currentPin: String?): OkHttpClient.Builder {
        val trust = ServerTrustManager { currentPin }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier(ServerHostnameVerifier { currentPin })
    }

    @Test
    fun pinnedSelfSignedCertificateIsTrustedByIp() {
        server.enqueue(MockResponse.Builder().body("ok").build())
        // Connect via 127.0.0.1 although the cert names "musicstream": the pin is the trust anchor.
        val url = server.url("/healthz").newBuilder().host("127.0.0.1").build()
        pinnedClient(pin).build().newCall(Request.Builder().url(url).build()).execute().use {
            assertEquals("ok", it.body.string())
        }
    }

    @Test
    fun wrongPinIsRejected() {
        server.enqueue(MockResponse.Builder().body("ok").build())
        val url = server.url("/healthz").newBuilder().host("127.0.0.1").build()
        val other = "sha256/" + Base64.getEncoder().encodeToString(ByteArray(32))
        assertThrows(SSLException::class.java) {
            pinnedClient(other).build().newCall(Request.Builder().url(url).build()).execute().close()
        }
    }

    // ---- interceptors ----

    private class FakeTokens(var access: String?, var refresh: String?) : TokenStorage {
        override fun accessToken() = access
        override fun refreshToken() = refresh
        override fun save(accessToken: String, refreshToken: String) { access = accessToken; refresh = refreshToken }
        override fun clear() { access = null; refresh = null }
    }

    private inner class FakeResolver : ServerEndpointResolver {
        override suspend fun resolve(force: Boolean) = server.url("/").newBuilder().host("127.0.0.1").build().toString()
    }

    private fun apiClients(tokens: TokenStorage): Pair<OkHttpClient, TokenRefreshApi> {
        val url = ServerUrlInterceptor { FakeResolver() }
        val base = pinnedClient(pin).build()
        val routed = base.newBuilder().addInterceptor(url).build()
        val json = Json { ignoreUnknownKeys = true }
        val authApi = Retrofit.Builder().baseUrl(ServerUrls.BASE_URL).client(routed)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(TokenRefreshApi::class.java)
        // Same interceptor order as NetworkModule.serverClient.
        val client = base.newBuilder()
            .addInterceptor(AuthInterceptor(tokens))
            .addInterceptor(url)
            .authenticator(TokenAuthenticator(tokens) { authApi })
            .build()
        return client to authApi
    }

    @Test
    fun rewritesVirtualHostAndRefreshesExpiredToken() {
        val tokens = FakeTokens("old-access", "rt.secret")
        val (client, _) = apiClients(tokens)
        val refreshed = Json.encodeToString(TokenDto.serializer(), TokenDto("Bearer", "new-access", 3600, "rt2.secret2", 0))
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body(refreshed).build())
        server.enqueue(MockResponse.Builder().body("tracks").build())

        client.newCall(Request.Builder().url(ServerUrls.BASE_URL + "api/v1/tracks").build()).execute().use {
            assertEquals("tracks", it.body.string())
        }
        val first = server.takeRequest()
        assertEquals("/api/v1/tracks", first.url.encodedPath)
        assertEquals("Bearer old-access", first.headers["Authorization"])
        assertEquals("/api/v1/auth/refresh", server.takeRequest().url.encodedPath)
        assertEquals("Bearer new-access", server.takeRequest().headers["Authorization"])
        assertEquals("rt2.secret2", tokens.refresh)
    }

    @Test
    fun revokedRefreshTokenLogsOut() {
        val tokens = FakeTokens("old-access", "rt.secret")
        val (client, _) = apiClients(tokens)
        server.enqueue(MockResponse.Builder().code(401).build())
        server.enqueue(MockResponse.Builder().code(401).build()) // refresh rejected

        client.newCall(Request.Builder().url(ServerUrls.BASE_URL + "api/v1/tracks").build()).execute().use {
            assertEquals(401, it.code)
        }
        assertNull(tokens.refresh)
    }
}
