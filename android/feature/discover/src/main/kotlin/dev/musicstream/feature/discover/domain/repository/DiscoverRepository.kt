package dev.musicstream.feature.discover.domain.repository

import dev.musicstream.feature.discover.domain.model.DownloadJob
import dev.musicstream.feature.discover.domain.model.OnlineSong
import dev.musicstream.feature.discover.domain.model.OnlineSource
import dev.musicstream.feature.discover.domain.model.SongInfo
import kotlinx.coroutines.flow.Flow

interface DiscoverRepository {
    fun search(query: String, source: OnlineSource): Flow<List<OnlineSong>>
    fun download(url: String, info: SongInfo): Flow<DownloadJob>
    fun downloads(): Flow<List<DownloadJob>>
}
