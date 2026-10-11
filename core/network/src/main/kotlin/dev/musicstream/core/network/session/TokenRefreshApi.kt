package dev.musicstream.core.network.session

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/** Token endpoints shared by the login flow and [dev.musicstream.core.network.TokenAuthenticator]. */
interface TokenRefreshApi {
    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokenDto
}

// Wire format of the Go server: docs/API.md (handlers in backend/internal/feature/*/presentation/).

@Serializable
data class TokenDto(
    val tokenType: String,
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
)

@Serializable
data class RefreshRequest(val refreshToken: String)
