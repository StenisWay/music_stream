package dev.musicstream.feature.session.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.musicstream.core.network.session.SessionPreferences
import dev.musicstream.core.network.tls.PinProvider
import dev.musicstream.feature.session.BuildConfig
import dev.musicstream.feature.session.domain.model.ConnectionCode
import dev.musicstream.feature.session.domain.model.ServerConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class StoredConnection(
    val hosts: List<String>,
    val port: Int,
    val scheme: String,
    val certPin: String? = null,
    val publicKey: String? = null,
)

/**
 * Persists the paired server. Interceptors and the TLS layer need
 * synchronous reads, so the value is mirrored in memory.
 * Without a stored pairing it falls back to the server bundled at build
 * time from server.properties, so that build needs no pairing step.
 */
@Singleton
class ConnectionStore @Inject constructor(
    @SessionPreferences private val store: DataStore<Preferences>,
) : PinProvider {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cached: ServerConnection? = null
    @Volatile private var loaded = false

    val connection: Flow<ServerConnection?> = store.data.map { prefs -> prefs[CONNECTION]?.let(::decode) ?: BUNDLED }

    fun current(): ServerConnection? {
        if (!loaded) {
            cached = runBlocking { connection.first() }
            loaded = true
        }
        return cached
    }

    suspend fun save(c: ServerConnection) {
        val encoded = json.encodeToString(
            StoredConnection.serializer(),
            StoredConnection(c.hosts, c.port, c.scheme, c.certPin, c.publicKey),
        )
        store.edit { it[CONNECTION] = encoded }
        cached = c
        loaded = true
    }

    suspend fun clear() {
        store.edit { it.remove(CONNECTION) }
        cached = BUNDLED
        loaded = true
    }

    override fun currentPin(): String? = current()?.certPin

    private fun decode(raw: String): ServerConnection? = runCatching {
        val s = json.decodeFromString(StoredConnection.serializer(), raw)
        ServerConnection(s.hosts, s.port, s.scheme, s.certPin, s.publicKey)
    }.getOrNull()

    private companion object {
        val CONNECTION = stringPreferencesKey("connection")

        val BUNDLED: ServerConnection? = runCatching {
            val hosts = BuildConfig.SERVER_HOSTS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val pin = BuildConfig.SERVER_PIN.takeIf { it.isNotEmpty() }
            hosts.takeIf { it.isNotEmpty() }?.let {
                ServerConnection(
                    hosts = it,
                    port = BuildConfig.SERVER_PORT.toIntOrNull() ?: ConnectionCode.DEFAULT_PORT,
                    scheme = if (pin != null) "https" else "http",
                    certPin = pin,
                )
            }
        }.getOrNull()
    }
}
