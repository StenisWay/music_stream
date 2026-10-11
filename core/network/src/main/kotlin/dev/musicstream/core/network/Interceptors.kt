package dev.musicstream.core.network

import dev.musicstream.core.common.AppError
import dev.musicstream.core.network.session.RefreshRequest
import dev.musicstream.core.network.session.TokenRefreshApi
import dev.musicstream.core.network.session.TokenStorage
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Routes requests for [ServerUrls.VIRTUAL_HOST] to the reachable address.
 * When the connection fails (e.g. phone left home Wi-Fi) it re-resolves once
 * and retries, which makes switching between home Wi-Fi and the VPN transparent.
 */
@Singleton
class ServerUrlInterceptor @Inject constructor(
    private val resolver: Provider<ServerEndpointResolver>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!ServerUrls.isServer(original.url)) return chain.proceed(original)

        val base = resolve(force = false)
        try {
            return chain.proceed(original.withBase(base))
        } catch (e: IOException) {
            if (chain.call().isCanceled()) throw e
            val retryBase = runCatching { resolve(force = true) }.getOrNull()
            if (retryBase == null || retryBase == base) throw e
            return chain.proceed(original.withBase(retryBase))
        }
    }

    private fun resolve(force: Boolean): String = try {
        runBlocking { resolver.get().resolve(force) }
    } catch (e: AppError) {
        throw ServerUnavailableException(e)
    }

    private fun Request.withBase(base: String) = newBuilder().url(ServerUrls.rewrite(url, base)).build()
}

/** IOException so OkHttp/ExoPlayer treat it as a network failure. */
class ServerUnavailableException(cause: Throwable) : IOException("home server unreachable", cause)

/** Adds the bearer token to home-server requests only. */
@Singleton
class AuthInterceptor @Inject constructor(private val tokens: TokenStorage) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!ServerUrls.isServer(req.url) || req.url.encodedPath.startsWith("/api/v1/auth/")) return chain.proceed(req)
        val token = tokens.accessToken() ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * Refreshes the access token on 401 and retries once. Concurrent 401s are
 * serialised so only one refresh (which rotates the refresh token) happens.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokens: TokenStorage,
    private val refreshApi: Provider<TokenRefreshApi>,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        // The interceptor rewrote the host; recognise server calls by path.
        if (!request.url.encodedPath.startsWith("/api/") || request.url.encodedPath.startsWith("/api/v1/auth/")) return null
        if (responseCount(response) >= 2) return null
        val failedToken = request.header("Authorization")?.removePrefix("Bearer ")

        synchronized(lock) {
            val current = tokens.accessToken()
            if (current != null && current != failedToken) {
                return request.newBuilder().header("Authorization", "Bearer $current").build()
            }
            val refresh = tokens.refreshToken() ?: return null
            return try {
                val pair = runBlocking { refreshApi.get().refresh(RefreshRequest(refresh)) }
                tokens.save(pair.accessToken, pair.refreshToken)
                request.newBuilder().header("Authorization", "Bearer ${pair.accessToken}").build()
            } catch (e: HttpException) {
                if (e.code() == 401) tokens.clear() // refresh token revoked/expired: log out
                null
            } catch (e: IOException) {
                null
            }
        }
    }

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var n = 0
        while (r != null) { n++; r = r.priorResponse }
        return n
    }
}
