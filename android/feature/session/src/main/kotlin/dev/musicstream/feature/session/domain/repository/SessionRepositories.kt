package dev.musicstream.feature.session.domain.repository

import dev.musicstream.feature.session.domain.model.ServerConnection
import dev.musicstream.feature.session.domain.model.ServerInfo
import kotlinx.coroutines.flow.Flow

/** Contracts implemented by this feature's data layer. All failures are [dev.musicstream.core.common.AppError]. */

interface ConnectionRepository {
    val connection: Flow<ServerConnection?>
    suspend fun save(connection: ServerConnection)
    /** Finds a reachable address (home LAN or VPN) and returns its base URL. */
    suspend fun resolve(force: Boolean = false): String
    suspend fun clear()
}

interface AuthRepository {
    val isLoggedIn: Flow<Boolean>
    suspend fun login(username: String, password: String)
    suspend fun logout()
}

interface ServerInfoRepository {
    suspend fun serverInfo(): ServerInfo
}
