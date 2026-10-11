package dev.musicstream.feature.session.domain.usecase

import dev.musicstream.core.common.AppError
import dev.musicstream.core.common.runCatchingApp
import dev.musicstream.feature.session.domain.model.ConnectionCode
import dev.musicstream.feature.session.domain.model.ServerInfo
import dev.musicstream.feature.session.domain.model.SessionState
import dev.musicstream.feature.session.domain.repository.AuthRepository
import dev.musicstream.feature.session.domain.repository.ConnectionRepository
import dev.musicstream.feature.session.domain.repository.ServerInfoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/** Parses a connection code (or manual address), verifies reachability and stores it. */
class PairServerUseCase @Inject constructor(private val connections: ConnectionRepository) {
    suspend operator fun invoke(code: String): Result<Unit> = runCatchingApp {
        val connection = try {
            ConnectionCode.parse(code)
        } catch (e: IllegalArgumentException) {
            throw AppError.Invalid(e.message ?: "invalid code")
        }
        connections.save(connection)
        connections.resolve(force = true)
    }

    suspend fun manual(hostAndPort: String, certPin: String?): Result<Unit> = runCatchingApp {
        val connection = try {
            ConnectionCode.manual(hostAndPort, certPin)
        } catch (e: IllegalArgumentException) {
            throw AppError.Invalid(e.message ?: "invalid address")
        }
        connections.save(connection)
        connections.resolve(force = true)
    }
}

class LoginUseCase @Inject constructor(private val auth: AuthRepository) {
    suspend operator fun invoke(username: String, password: String): Result<Unit> = runCatchingApp {
        if (username.isBlank() || password.isEmpty()) throw AppError.Invalid("username and password are required")
        auth.login(username.trim(), password)
    }
}

class LogoutUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val connections: ConnectionRepository,
) {
    suspend operator fun invoke(forgetServer: Boolean = false) {
        auth.logout()
        if (forgetServer) connections.clear()
    }
}

/** Derives the top-level flow (pair → login → app) from persisted session state. */
class ObserveSessionStateUseCase @Inject constructor(
    private val connections: ConnectionRepository,
    private val auth: AuthRepository,
) {
    operator fun invoke(): Flow<SessionState> = combine(connections.connection, auth.isLoggedIn) { connection, loggedIn ->
        when {
            connection == null -> SessionState.NEEDS_PAIRING
            !loggedIn -> SessionState.NEEDS_LOGIN
            else -> SessionState.READY
        }
    }
}

class GetServerInfoUseCase @Inject constructor(private val repo: ServerInfoRepository) {
    suspend operator fun invoke(): Result<ServerInfo> = runCatchingApp { repo.serverInfo() }
}
