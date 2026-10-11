package dev.musicstream.feature.radio.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Radio Browser is run by volunteers on several mirrors; if one is down the
 * request is retried on the next.
 */
class RadioBrowserFailover : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var last: IOException? = null
        for (host in MIRRORS) {
            val url = request.url.newBuilder().host(host).build()
            try {
                val response = chain.proceed(request.newBuilder().url(url).build())
                if (response.code < 500) return response
                response.close()
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("all Radio Browser mirrors failed")
    }

    companion object {
        private val MIRRORS = listOf("de1.api.radio-browser.info", "nl1.api.radio-browser.info", "at1.api.radio-browser.info")
        val PRIMARY = "https://${MIRRORS.first()}/"
    }
}
