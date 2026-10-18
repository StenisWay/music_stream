package dev.musicstream.feature.podcast.domain.repository

import dev.musicstream.core.common.Page
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.EpisodeProgress
import dev.musicstream.feature.podcast.domain.model.FeedPage
import dev.musicstream.feature.podcast.domain.model.HistoryEntry
import dev.musicstream.feature.podcast.domain.model.Podcast
import dev.musicstream.feature.podcast.domain.model.PodcastSearchResult
import kotlinx.coroutines.flow.Flow

/** Podcast subscriptions and history (local) and directory search. All failures are [dev.musicstream.core.common.AppError]. */
interface PodcastRepository {
    val subscriptions: Flow<List<Podcast>>

    /**
     * Stored episodes of a subscribed feed, newest first. Reaching the end
     * fetches older RSS pages when the feed is paged, so the list keeps loading.
     */
    suspend fun episodePage(feedUrl: String, offset: Int, limit: Int): Page<Episode>

    /** Fetches one feed page for display without subscribing ([url] defaults to the feed itself). */
    suspend fun fetchFeedPage(feedUrl: String, url: String = feedUrl): FeedPage

    suspend fun search(term: String): List<PodcastSearchResult>
    suspend fun subscribe(feedUrl: String): Podcast
    suspend fun unsubscribe(feedUrl: String)
    suspend fun refresh(feedUrl: String)
    suspend fun refreshAll()

    // Listening history

    val history: Flow<List<HistoryEntry>>
    fun progress(feedUrl: String): Flow<Map<String, EpisodeProgress>>

    /** Remembers metadata of episodes about to be queued, so they can be recorded once they start. */
    fun prepareForPlayback(episodes: List<Episode>, podcastTitle: String)
    suspend fun markStarted(episodeId: String)
    suspend fun saveProgress(episodeId: String, positionMs: Long, durationMs: Long)
    suspend fun setCompleted(episode: Episode, podcastTitle: String, completed: Boolean)
    suspend fun removeFromHistory(episodeId: String)
}
