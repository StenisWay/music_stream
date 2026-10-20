package dev.musicstream.feature.discover.domain.usecase

import dev.musicstream.core.common.AppError
import dev.musicstream.core.common.runCatchingApp
import dev.musicstream.feature.discover.domain.model.DownloadJob
import dev.musicstream.feature.discover.domain.model.OnlineSong
import dev.musicstream.feature.discover.domain.model.OnlineSource
import dev.musicstream.feature.discover.domain.model.SongInfo
import dev.musicstream.feature.discover.domain.repository.DiscoverRepository
import javax.inject.Inject

/** Searches an online source through the home server; a blank query finds nothing. */
class SearchOnlineUseCase @Inject constructor(private val repo: DiscoverRepository) {
    suspend operator fun invoke(query: String, source: OnlineSource): Result<List<OnlineSong>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(emptyList())
        return runCatchingApp { repo.search(q, source) }
    }
}

/** Asks the server to download a song into the library, tagged with [info]. */
class AddToLibraryUseCase @Inject constructor(private val repo: DiscoverRepository) {
    suspend operator fun invoke(song: OnlineSong, info: SongInfo): Result<DownloadJob> {
        val clean = SongInfo(info.artist.trim(), info.title.trim(), info.album.trim())
        if (clean.title.isEmpty()) return Result.failure(AppError.Invalid("title"))
        return runCatchingApp { repo.download(song.url, clean) }
    }
}

class GetDownloadsUseCase @Inject constructor(private val repo: DiscoverRepository) {
    suspend operator fun invoke(): Result<List<DownloadJob>> = runCatchingApp { repo.downloads() }
}
