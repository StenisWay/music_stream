package dev.musicstream.feature.podcast.domain.repository

import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.Podcast
import dev.musicstream.feature.podcast.domain.model.PodcastSearchResult
import kotlinx.coroutines.flow.Flow

/** Podcast subscriptions (local) and directory search. All failures are [dev.musicstream.core.common.AppError]. */
interface PodcastRepository {
    val subscriptions: Flow<List<Podcast>>
    fun episodes(feedUrl: String): Flow<List<Episode>>
    fun episode(id: String): Flow<Episode?>
    suspend fun search(term: String): List<PodcastSearchResult>
    suspend fun subscribe(feedUrl: String): Podcast
    suspend fun unsubscribe(feedUrl: String)
    suspend fun refresh(feedUrl: String)
    suspend fun refreshAll()
    suspend fun saveProgress(episodeId: String, positionMs: Long, durationMs: Long)
}
