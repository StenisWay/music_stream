package dev.musicstream.core.network.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/** Synchronous token access for OkHttp interceptors (abstracted for tests). */
interface TokenStorage {
    fun accessToken(): String?
    fun refreshToken(): String?
    fun save(accessToken: String, refreshToken: String)
    fun clear()
}

/**
 * Holds the access/refresh token pair. Values are encrypted at rest with
 * [TokenCipher] and cached in memory for the OkHttp interceptors.
 */
@Singleton
class TokenStore @Inject constructor(
    @SessionPreferences private val store: DataStore<Preferences>,
    private val cipher: TokenCipher,
) : TokenStorage {
    private val lock = Any()
    @Volatile private var access: String? = null
    @Volatile private var refresh: String? = null
    @Volatile private var loaded = false

    val isLoggedIn: Flow<Boolean> = store.data.map { it[REFRESH] != null }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val prefs = runBlocking { store.data.first() }
            access = prefs[ACCESS]?.let(cipher::decrypt)
            refresh = prefs[REFRESH]?.let(cipher::decrypt)
            loaded = true
        }
    }

    override fun accessToken(): String? { ensureLoaded(); return access }
    override fun refreshToken(): String? { ensureLoaded(); return refresh }

    override fun save(accessToken: String, refreshToken: String) {
        synchronized(lock) {
            access = accessToken
            refresh = refreshToken
            loaded = true
        }
        runBlocking {
            store.edit {
                it[ACCESS] = cipher.encrypt(accessToken)
                it[REFRESH] = cipher.encrypt(refreshToken)
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            access = null
            refresh = null
            loaded = true
        }
        runBlocking { store.edit { it.remove(ACCESS); it.remove(REFRESH) } }
    }

    private companion object {
        val ACCESS = stringPreferencesKey("access_token")
        val REFRESH = stringPreferencesKey("refresh_token")
    }
}
