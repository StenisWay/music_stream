package dev.musicstream.feature.playlist.domain.usecase

import dev.musicstream.core.common.AppError
import dev.musicstream.core.common.runCatchingApp
import dev.musicstream.feature.playlist.domain.model.Playlist
import dev.musicstream.feature.playlist.domain.model.PlaylistSummary
import dev.musicstream.feature.playlist.domain.repository.PlaylistRepository
import javax.inject.Inject

class GetPlaylistsUseCase @Inject constructor(private val repo: PlaylistRepository) {
    suspend operator fun invoke(): Result<List<PlaylistSummary>> = runCatchingApp { repo.playlists() }
}

class GetPlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    suspend operator fun invoke(id: Long): Result<Playlist> = runCatchingApp { repo.playlist(id) }
}

class CreatePlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    suspend operator fun invoke(name: String, trackIds: List<Long> = emptyList()): Result<Playlist> = runCatchingApp {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME) throw AppError.Invalid("playlist name must be 1-$MAX_NAME characters")
        repo.create(trimmed, trackIds)
    }

    companion object { const val MAX_NAME = 100 }
}

class AddToPlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    suspend operator fun invoke(playlistId: Long, trackIds: List<Long>): Result<Playlist> =
        runCatchingApp { repo.appendTracks(playlistId, trackIds) }
}

/** Removes the entry at [position] (playlists may contain a track twice). */
class RemoveFromPlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    suspend operator fun invoke(playlist: Playlist, position: Int): Result<Playlist> = runCatchingApp {
        if (position !in playlist.tracks.indices) throw AppError.Invalid("position out of range")
        repo.replaceTracks(playlist.id, playlist.tracks.map { it.id }.toMutableList().apply { removeAt(position) })
    }
}

class MovePlaylistTrackUseCase @Inject constructor(private val repo: PlaylistRepository) {
    suspend operator fun invoke(playlist: Playlist, from: Int, to: Int): Result<Playlist> = runCatchingApp {
        val ids = playlist.tracks.map { it.id }.toMutableList()
        if (from !in ids.indices || to !in ids.indices) throw AppError.Invalid("position out of range")
        ids.add(to, ids.removeAt(from))
        repo.replaceTracks(playlist.id, ids)
    }
}

class RenamePlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    suspend operator fun invoke(id: Long, name: String): Result<Unit> = runCatchingApp {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw AppError.Invalid("playlist name is required")
        repo.rename(id, trimmed)
    }
}

class DeletePlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    suspend operator fun invoke(id: Long): Result<Unit> = runCatchingApp { repo.delete(id) }
}
