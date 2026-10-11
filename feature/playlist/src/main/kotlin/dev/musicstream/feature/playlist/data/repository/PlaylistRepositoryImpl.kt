package dev.musicstream.feature.playlist.data.repository

import dev.musicstream.core.network.apiCall
import dev.musicstream.feature.library.data.remote.toDomain
import dev.musicstream.feature.playlist.data.remote.PlaylistApi
import dev.musicstream.feature.playlist.data.remote.PlaylistDto
import dev.musicstream.feature.playlist.data.remote.PlaylistRequest
import dev.musicstream.feature.playlist.data.remote.RenameRequest
import dev.musicstream.feature.playlist.data.remote.TrackIdsRequest
import dev.musicstream.feature.playlist.domain.model.Playlist
import dev.musicstream.feature.playlist.domain.model.PlaylistSummary
import dev.musicstream.feature.playlist.domain.repository.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(private val api: PlaylistApi) : PlaylistRepository {
    override suspend fun playlists() = apiCall {
        api.playlists().items.map { PlaylistSummary(it.id, it.name, it.trackCount) }
    }

    override suspend fun playlist(id: Long) = apiCall { api.playlist(id).toDomain() }

    override suspend fun create(name: String, trackIds: List<Long>) =
        apiCall { api.createPlaylist(PlaylistRequest(name, trackIds)).toDomain() }

    override suspend fun rename(id: Long, name: String) {
        apiCall { api.renamePlaylist(id, RenameRequest(name)) }
    }

    override suspend fun replaceTracks(id: Long, trackIds: List<Long>) =
        apiCall { api.replacePlaylistTracks(id, TrackIdsRequest(trackIds)).toDomain() }

    override suspend fun appendTracks(id: Long, trackIds: List<Long>) =
        apiCall { api.appendPlaylistTracks(id, TrackIdsRequest(trackIds)).toDomain() }

    override suspend fun delete(id: Long) = apiCall { api.deletePlaylist(id) }

    private fun PlaylistDto.toDomain() = Playlist(id, name, tracks.map { it.toDomain() })
}
