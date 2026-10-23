package dev.musicstream.feature.session.domain.repository

import dev.musicstream.feature.session.domain.model.ServerConnection
import dev.musicstream.feature.session.domain.model.ServerInfo
import kotlinx.coroutines.flow.Flow

/** Contracts implemented by this feature's data layer. Calls are cold flows that emit once; failures are [dev.musicstream.core.common.AppError]. */

interface ConnectionRepository {
    val connection: Flow<ServerConnection?>
    fun save(connection: ServerConnection): Flow<Unit>
    /** Finds a reachable address (home LAN or VPN) and emits its base URL. */
    fun findServer(force: Boolean = false): Flow<String>
    fun clear(): Flow<Unit>
}

interface AuthRepository {
    val isLoggedIn: Flow<Boolean>
    fun login(username: String, password: String): Flow<Unit>
    fun logout(): Flow<Unit>
}

interface ServerInfoRepository {
    fun serverInfo(): Flow<ServerInfo>
}
