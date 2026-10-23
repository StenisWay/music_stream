package dev.musicstream.feature.session.data.repository

import android.os.Build
import dev.musicstream.core.common.AppError
import dev.musicstream.core.network.apiCall
import dev.musicstream.core.network.session.RefreshRequest
import dev.musicstream.core.network.session.TokenStore
import dev.musicstream.feature.session.data.remote.AuthApi
import dev.musicstream.feature.session.data.remote.LoginRequest
import dev.musicstream.feature.session.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val api: AuthApi,
    private val tokens: TokenStore,
) : AuthRepository {
    override val isLoggedIn: Flow<Boolean> = tokens.isLoggedIn

    override suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        val pair = try {
            apiCall { api.login(LoginRequest(username, password, "${Build.MANUFACTURER} ${Build.MODEL}")) }
        } catch (e: AppError.Unauthorized) {
            throw AppError.InvalidCredentials()
        }
        tokens.save(pair.accessToken, pair.refreshToken)
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        val refresh = tokens.refreshToken()
        tokens.clear()
        // Best effort server-side revocation; local logout must not depend on it.
        if (refresh != null) runCatching { api.logout(RefreshRequest(refresh)) }
        Unit
    }
}
