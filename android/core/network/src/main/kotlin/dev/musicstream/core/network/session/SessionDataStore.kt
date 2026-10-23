package dev.musicstream.core.network.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Qualifier

/**
 * The single "session" preferences file. DataStore allows only one instance
 * per file, so the token store (here) and the paired-server store (session
 * feature) share this one through Hilt.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class SessionPreferences

internal val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "session")
