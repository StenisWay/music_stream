package dev.musicstream.feature.discover.data.repository

import dev.musicstream.core.network.apiFlow
import dev.musicstream.feature.discover.data.remote.CandidateDto
import dev.musicstream.feature.discover.data.remote.DiscoverApi
import dev.musicstream.feature.discover.data.remote.DownloadRequestDto
import dev.musicstream.feature.discover.data.remote.JobDto
import dev.musicstream.feature.discover.domain.model.DownloadJob
import dev.musicstream.feature.discover.domain.model.DownloadStatus
import dev.musicstream.feature.discover.domain.model.OnlineSong
import dev.musicstream.feature.discover.domain.model.OnlineSource
import dev.musicstream.feature.discover.domain.model.SongInfo
import dev.musicstream.feature.discover.domain.repository.DiscoverRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoverRepositoryImpl @Inject constructor(private val api: DiscoverApi) : DiscoverRepository {
    override fun search(query: String, source: OnlineSource) = apiFlow {
        api.search(query, source.apiValue).items.mapNotNull { it.toDomain() }
    }

    override fun download(url: String, info: SongInfo) = apiFlow {
        api.download(DownloadRequestDto(url, info.artist, info.title, info.album)).toDomain()
    }

    override fun downloads() = apiFlow { api.downloads().items.map { it.toDomain() } }
}

private fun CandidateDto.toDomain(): OnlineSong? {
    val src = OnlineSource.entries.firstOrNull { it.apiValue == source } ?: return null
    return OnlineSong(
        url = url, source = src, videoTitle = title, uploader = uploader, durationMs = durationSec * 1000L,
        thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() }, artist = artist, title = track.ifBlank { title },
    )
}

private fun JobDto.toDomain() = DownloadJob(
    id = id, url = url, info = SongInfo(artist, title, album),
    status = when (status) {
        "downloading" -> DownloadStatus.DOWNLOADING
        "done" -> DownloadStatus.DONE
        "failed" -> DownloadStatus.FAILED
        else -> DownloadStatus.QUEUED
    },
    error = error?.takeIf { it.isNotBlank() },
)
