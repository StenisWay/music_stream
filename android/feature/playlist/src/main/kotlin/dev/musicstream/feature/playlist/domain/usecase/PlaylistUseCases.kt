package dev.musicstream.feature.playlist.domain.usecase

import dev.musicstream.core.common.AppError
import dev.musicstream.core.common.asResult
import dev.musicstream.feature.playlist.domain.model.Playlist
import dev.musicstream.feature.playlist.domain.model.PlaylistChoice
import dev.musicstream.feature.playlist.domain.model.PlaylistSummary
import dev.musicstream.feature.playlist.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/** Runs [check] when collected, then continues with [next]; a thrown [AppError] becomes a failed result. */
private fun <T> validated(check: () -> Unit, next: () -> Flow<T>): Flow<Result<T>> =
    flow { check(); emitAll(next()) }.asResult()

class GetPlaylistsUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(): Flow<Result<List<PlaylistSummary>>> = repo.playlists().asResult()
}

/** All playlists, each marked with whether it already holds [trackId]. */
class GetPlaylistChoicesUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(trackId: Long): Flow<Result<List<PlaylistChoice>>> = repo.choicesFor(trackId).asResult()
}

class GetPlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(id: Long): Flow<Result<Playlist>> = repo.playlist(id).asResult()
}

class CreatePlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(name: String, trackIds: List<Long> = emptyList()): Flow<Result<Playlist>> {
        val trimmed = name.trim()
        return validated(
            check = { if (trimmed.isEmpty() || trimmed.length > MAX_NAME) throw AppError.Invalid("playlist name must be 1-$MAX_NAME characters") },
            next = { repo.create(trimmed, trackIds) },
        )
    }

    companion object { const val MAX_NAME = 100 }
}

class AddToPlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(playlistId: Long, trackIds: List<Long>): Flow<Result<Playlist>> =
        repo.appendTracks(playlistId, trackIds).asResult()
}

/** Removes every occurrence of [trackId] from the playlist. */
class RemoveTrackFromPlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(playlistId: Long, trackId: Long): Flow<Result<Playlist>> =
        repo.removeTrack(playlistId, trackId).asResult()
}

/** Removes the entry at [position] (playlists may contain a track twice). */
class RemoveFromPlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(playlist: Playlist, position: Int): Flow<Result<Playlist>> = validated(
        check = { if (position !in playlist.tracks.indices) throw AppError.Invalid("position out of range") },
        next = { repo.replaceTracks(playlist.id, playlist.tracks.map { it.id }.toMutableList().apply { removeAt(position) }) },
    )
}

class MovePlaylistTrackUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(playlist: Playlist, from: Int, to: Int): Flow<Result<Playlist>> {
        val ids = playlist.tracks.map { it.id }
        return validated(
            check = { if (from !in ids.indices || to !in ids.indices) throw AppError.Invalid("position out of range") },
            next = { repo.replaceTracks(playlist.id, ids.toMutableList().apply { add(to, removeAt(from)) }) },
        )
    }
}

class RenamePlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(id: Long, name: String): Flow<Result<Unit>> {
        val trimmed = name.trim()
        return validated(
            check = { if (trimmed.isEmpty()) throw AppError.Invalid("playlist name is required") },
            next = { repo.rename(id, trimmed) },
        )
    }
}

class DeletePlaylistUseCase @Inject constructor(private val repo: PlaylistRepository) {
    operator fun invoke(id: Long): Flow<Result<Unit>> = repo.delete(id).asResult()
}
