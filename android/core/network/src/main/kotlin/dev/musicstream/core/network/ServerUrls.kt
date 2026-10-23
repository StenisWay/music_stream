package dev.musicstream.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Every request to the home server is addressed to this virtual host. The
 * [ServerUrlInterceptor] rewrites it to the currently reachable address, so
 * URLs handed to Retrofit, Coil and ExoPlayer stay stable across network
 * changes (and their caches keep working).
 */
object ServerUrls {
    const val VIRTUAL_HOST = "server.musicstream"
    const val BASE_URL = "https://$VIRTUAL_HOST/"

    fun absolute(path: String?): String? = path?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else BASE_URL + it.trimStart('/')
    }

    fun isServer(url: HttpUrl): Boolean = url.host == VIRTUAL_HOST

    /** Replaces scheme/host/port of [url] with those of [base]. */
    fun rewrite(url: HttpUrl, base: String): HttpUrl {
        val target = base.toHttpUrl()
        return url.newBuilder().scheme(target.scheme).host(target.host).port(target.port).build()
    }
}
