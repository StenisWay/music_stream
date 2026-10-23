package dev.musicstream.feature.playlist.data.repository

import dev.musicstream.core.network.apiFlow
import dev.musicstream.feature.library.data.remote.toDomain
import dev.musicstream.feature.playlist.data.remote.PlaylistApi
import dev.musicstream.feature.playlist.data.remote.PlaylistDto
import dev.musicstream.feature.playlist.data.remote.PlaylistRequest
import dev.musicstream.feature.playlist.data.remote.RenameRequest
import dev.musicstream.feature.playlist.data.remote.TrackIdsRequest
import dev.musicstream.feature.playlist.domain.model.Playlist
import dev.musicstream.feature.playlist.domain.model.PlaylistChoice
import dev.musicstream.feature.playlist.domain.model.PlaylistSummary
import dev.musicstream.feature.playlist.domain.repository.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(private val api: PlaylistApi) : PlaylistRepository {
    override fun playlists() = apiFlow {
        api.playlists().items.map { PlaylistSummary(it.id, it.name, it.trackCount) }
    }

    override fun choicesFor(trackId: Long) = apiFlow {
        api.playlists(containsTrack = trackId).items.map {
            PlaylistChoice(PlaylistSummary(it.id, it.name, it.trackCount), it.containsTrack == true)
        }
    }

    override fun playlist(id: Long) = apiFlow { api.playlist(id).toDomain() }

    override fun create(name: String, trackIds: List<Long>) =
        apiFlow { api.createPlaylist(PlaylistRequest(name, trackIds)).toDomain() }

    override fun rename(id: Long, name: String) = apiFlow { api.renamePlaylist(id, RenameRequest(name)); Unit }

    override fun replaceTracks(id: Long, trackIds: List<Long>) =
        apiFlow { api.replacePlaylistTracks(id, TrackIdsRequest(trackIds)).toDomain() }

    override fun appendTracks(id: Long, trackIds: List<Long>) =
        apiFlow { api.appendPlaylistTracks(id, TrackIdsRequest(trackIds)).toDomain() }

    override fun removeTrack(id: Long, trackId: Long) =
        apiFlow { api.removePlaylistTrack(id, trackId).toDomain() }

    override fun delete(id: Long) = apiFlow { api.deletePlaylist(id); Unit }

    private fun PlaylistDto.toDomain() = Playlist(id, name, tracks.map { it.toDomain() })
}
