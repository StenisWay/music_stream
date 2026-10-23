package dev.musicstream.feature.discover.domain.repository

import dev.musicstream.feature.discover.domain.model.DownloadJob
import dev.musicstream.feature.discover.domain.model.OnlineSong
import dev.musicstream.feature.discover.domain.model.OnlineSource
import dev.musicstream.feature.discover.domain.model.SongInfo

interface DiscoverRepository {
    suspend fun search(query: String, source: OnlineSource): List<OnlineSong>
    suspend fun download(url: String, info: SongInfo): DownloadJob
    suspend fun downloads(): List<DownloadJob>
}
