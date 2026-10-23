package dev.musicstream.feature.playlist.domain.repository

import dev.musicstream.feature.playlist.domain.model.Playlist
import dev.musicstream.feature.playlist.domain.model.PlaylistChoice
import dev.musicstream.feature.playlist.domain.model.PlaylistSummary
import kotlinx.coroutines.flow.Flow

/** Server-side playlists. Each call is a cold flow that emits once; failures are [dev.musicstream.core.common.AppError]. */
interface PlaylistRepository {
    fun playlists(): Flow<List<PlaylistSummary>>
    fun choicesFor(trackId: Long): Flow<List<PlaylistChoice>>
    fun playlist(id: Long): Flow<Playlist>
    fun create(name: String, trackIds: List<Long>): Flow<Playlist>
    fun rename(id: Long, name: String): Flow<Unit>
    fun replaceTracks(id: Long, trackIds: List<Long>): Flow<Playlist>
    fun appendTracks(id: Long, trackIds: List<Long>): Flow<Playlist>
    fun removeTrack(id: Long, trackId: Long): Flow<Playlist>
    fun delete(id: Long): Flow<Unit>
}
