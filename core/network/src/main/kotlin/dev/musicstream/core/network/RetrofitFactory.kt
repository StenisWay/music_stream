package dev.musicstream.core.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Builds Retrofit services with the app-wide JSON settings; feature modules create their own APIs with it. */
object RetrofitFactory {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun <T> create(baseUrl: String, client: OkHttpClient, service: Class<T>): T = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(service)

    inline fun <reified T> create(baseUrl: String, client: OkHttpClient): T = create(baseUrl, client, T::class.java)
}
