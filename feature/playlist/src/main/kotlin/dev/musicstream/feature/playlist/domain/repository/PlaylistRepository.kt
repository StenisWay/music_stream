package dev.musicstream.feature.playlist.domain.repository

import dev.musicstream.feature.playlist.domain.model.Playlist
import dev.musicstream.feature.playlist.domain.model.PlaylistChoice
import dev.musicstream.feature.playlist.domain.model.PlaylistSummary

/** Server-side playlists. All failures are [dev.musicstream.core.common.AppError]. */
interface PlaylistRepository {
    suspend fun playlists(): List<PlaylistSummary>
    suspend fun choicesFor(trackId: Long): List<PlaylistChoice>
    suspend fun playlist(id: Long): Playlist
    suspend fun create(name: String, trackIds: List<Long>): Playlist
    suspend fun rename(id: Long, name: String)
    suspend fun replaceTracks(id: Long, trackIds: List<Long>): Playlist
    suspend fun appendTracks(id: Long, trackIds: List<Long>): Playlist
    suspend fun removeTrack(id: Long, trackId: Long): Playlist
    suspend fun delete(id: Long)
}
