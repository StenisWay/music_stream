package dev.musicstream.feature.discover.domain.usecase

import dev.musicstream.core.common.AppError
import dev.musicstream.core.common.asResult
import dev.musicstream.feature.discover.domain.model.DownloadJob
import dev.musicstream.feature.discover.domain.model.OnlineSong
import dev.musicstream.feature.discover.domain.model.OnlineSource
import dev.musicstream.feature.discover.domain.model.SongInfo
import dev.musicstream.feature.discover.domain.repository.DiscoverRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/** Searches an online source through the home server; a blank query finds nothing. */
class SearchOnlineUseCase @Inject constructor(private val repo: DiscoverRepository) {
    operator fun invoke(query: String, source: OnlineSource): Flow<Result<List<OnlineSong>>> {
        val q = query.trim()
        if (q.isEmpty()) return flowOf(Result.success(emptyList()))
        return repo.search(q, source).asResult()
    }
}

/** Asks the server to download a song into the library, tagged with [info]. */
class AddToLibraryUseCase @Inject constructor(private val repo: DiscoverRepository) {
    operator fun invoke(song: OnlineSong, info: SongInfo): Flow<Result<DownloadJob>> {
        val clean = SongInfo(info.artist.trim(), info.title.trim(), info.album.trim())
        if (clean.title.isEmpty()) return flowOf(Result.failure(AppError.Invalid("title")))
        return repo.download(song.url, clean).asResult()
    }
}

/**
 * Server download jobs, newest first. Emits once, then again every
 * [intervalMs] while any job is still queued or downloading; stops after the
 * first list with nothing active. A failed refresh is emitted and ends polling.
 */
class ObserveDownloadsUseCase @Inject constructor(private val repo: DiscoverRepository) {
    operator fun invoke(intervalMs: Long = 2_000): Flow<Result<List<DownloadJob>>> = flow {
        while (true) {
            val result = repo.downloads().asResult().first()
            emit(result)
            if (result.getOrNull()?.none { it.active } != false) break
            delay(intervalMs)
        }
    }
}
