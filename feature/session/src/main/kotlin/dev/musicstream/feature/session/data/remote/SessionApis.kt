package dev.musicstream.feature.session.data.remote

import dev.musicstream.core.network.session.RefreshRequest
import dev.musicstream.core.network.session.TokenDto
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** Unauthenticated auth endpoints used by the login flow (refresh lives in core:network). */
interface AuthApi {
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): TokenDto

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: RefreshRequest)
}

/** Authenticated home-server status. */
interface ServerInfoApi {
    @GET("api/v1/server/info")
    suspend fun serverInfo(): ServerInfoDto
}

// Wire format of the Go server: docs/API.md (handlers in backend/internal/feature/*/presentation/).

@Serializable
data class LoginRequest(val username: String, val password: String, val deviceName: String)

@Serializable
data class ServerInfoDto(
    val version: String = "",
    val trackCount: Int = 0,
    val scanning: Boolean = false,
    val transcodeAvailable: Boolean = false,
)
