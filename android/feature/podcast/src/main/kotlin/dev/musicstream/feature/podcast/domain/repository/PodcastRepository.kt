package dev.musicstream.feature.podcast.domain.repository

import dev.musicstream.core.common.Page
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.EpisodeProgress
import dev.musicstream.feature.podcast.domain.model.FeedPage
import dev.musicstream.feature.podcast.domain.model.HistoryEntry
import dev.musicstream.feature.podcast.domain.model.Podcast
import dev.musicstream.feature.podcast.domain.model.PodcastSearchResult
import kotlinx.coroutines.flow.Flow

/** Podcast subscriptions and history (local) and directory search. Calls are cold flows that emit once (the observe* properties keep emitting); failures are [dev.musicstream.core.common.AppError]. */
interface PodcastRepository {
    val subscriptions: Flow<List<Podcast>>

    /**
     * Stored episodes of a subscribed feed, newest first. Reaching the end
     * fetches older RSS pages when the feed is paged, so the list keeps loading.
     */
    fun episodePage(feedUrl: String, offset: Int, limit: Int): Flow<Page<Episode>>

    /** Fetches one feed page for display without subscribing ([url] defaults to the feed itself). */
    fun fetchFeedPage(feedUrl: String, url: String = feedUrl): Flow<FeedPage>

    fun search(term: String): Flow<List<PodcastSearchResult>>
    fun subscribe(feedUrl: String): Flow<Podcast>
    fun unsubscribe(feedUrl: String): Flow<Unit>
    fun refresh(feedUrl: String): Flow<Unit>
    /** Fails only when every feed failed. */
    fun refreshAll(): Flow<Unit>

    // Listening history

    val history: Flow<List<HistoryEntry>>
    fun progress(feedUrl: String): Flow<Map<String, EpisodeProgress>>

    /** Remembers metadata of episodes about to be queued, so they can be recorded once they start. */
    fun prepareForPlayback(episodes: List<Episode>, podcastTitle: String)
    fun markStarted(episodeId: String): Flow<Unit>
    fun saveProgress(episodeId: String, positionMs: Long, durationMs: Long): Flow<Unit>
    fun setCompleted(episode: Episode, podcastTitle: String, completed: Boolean): Flow<Unit>
    fun removeFromHistory(episodeId: String): Flow<Unit>
}
