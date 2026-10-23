package dev.musicstream.feature.session.domain.usecase

import dev.musicstream.core.common.AppError
import dev.musicstream.core.common.asResult
import dev.musicstream.feature.session.domain.model.ConnectionCode
import dev.musicstream.feature.session.domain.model.ServerConnection
import dev.musicstream.feature.session.domain.model.ServerInfo
import dev.musicstream.feature.session.domain.model.SessionState
import dev.musicstream.feature.session.domain.repository.AuthRepository
import dev.musicstream.feature.session.domain.repository.ConnectionRepository
import dev.musicstream.feature.session.domain.repository.ServerInfoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Parses a connection code (or manual address), stores it and checks the server answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairServerUseCase @Inject constructor(private val connections: ConnectionRepository) {
    operator fun invoke(code: String): Flow<Result<Unit>> = pair("invalid code") { ConnectionCode.parse(code) }

    fun manual(hostAndPort: String, certPin: String?): Flow<Result<Unit>> =
        pair("invalid address") { ConnectionCode.manual(hostAndPort, certPin) }

    private fun pair(invalid: String, parse: () -> ServerConnection): Flow<Result<Unit>> = flow {
        val connection = try {
            parse()
        } catch (e: IllegalArgumentException) {
            throw AppError.Invalid(e.message ?: invalid)
        }
        emitAll(
            connections.save(connection)
                .flatMapConcat { connections.findServer(force = true) }
                .map { },
        )
    }.asResult()
}

class LoginUseCase @Inject constructor(private val auth: AuthRepository) {
    operator fun invoke(username: String, password: String): Flow<Result<Unit>> = flow {
        if (username.isBlank() || password.isEmpty()) throw AppError.Invalid("username and password are required")
        emitAll(auth.login(username.trim(), password))
    }.asResult()
}

@OptIn(ExperimentalCoroutinesApi::class)
class LogoutUseCase @Inject constructor(
    private val auth: AuthRepository,
    private val connections: ConnectionRepository,
) {
    operator fun invoke(forgetServer: Boolean = false): Flow<Result<Unit>> =
        auth.logout()
            .flatMapConcat { if (forgetServer) connections.clear() else flowOf(Unit) }
            .asResult()
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
    operator fun invoke(): Flow<Result<ServerInfo>> = repo.serverInfo().asResult()
}
