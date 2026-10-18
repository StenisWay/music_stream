package dev.musicstream.feature.podcast.domain.usecase

import dev.musicstream.core.common.Page
import dev.musicstream.core.common.runCatchingApp
import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.playback.api.QueueItem
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.Podcast
import dev.musicstream.feature.podcast.domain.model.EpisodeProgress
import dev.musicstream.feature.podcast.domain.model.FeedPage
import dev.musicstream.feature.podcast.domain.model.HistoryEntry
import dev.musicstream.feature.podcast.domain.model.PodcastSearchResult
import dev.musicstream.feature.podcast.domain.repository.PodcastRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SearchPodcastsUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(term: String): Result<List<PodcastSearchResult>> {
        val t = term.trim()
        if (t.length < 2) return Result.success(emptyList())
        return runCatchingApp { repo.search(t) }
    }
}

class ObserveSubscriptionsUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(): Flow<List<Podcast>> = repo.subscriptions
}

class GetEpisodePageUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(feedUrl: String, offset: Int, limit: Int): Page<Episode> = repo.episodePage(feedUrl, offset, limit)
}

class ObserveEpisodeProgressUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(feedUrl: String): Flow<Map<String, EpisodeProgress>> = repo.progress(feedUrl)
}

class ObserveHistoryUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(): Flow<List<HistoryEntry>> = repo.history
}

/** Reads a feed page without subscribing; [pageUrl] is a previous page's `nextPageUrl`. */
class FetchFeedPageUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(feedUrl: String, pageUrl: String = feedUrl): Result<FeedPage> =
        runCatchingApp { repo.fetchFeedPage(feedUrl, pageUrl) }
}

class SubscribePodcastUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(feedUrl: String): Result<Podcast> = runCatchingApp { repo.subscribe(feedUrl.trim()) }
}

class UnsubscribePodcastUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(feedUrl: String): Result<Unit> = runCatchingApp { repo.unsubscribe(feedUrl) }
}

class RefreshPodcastsUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(feedUrl: String? = null): Result<Unit> = runCatchingApp {
        if (feedUrl == null) repo.refreshAll() else repo.refresh(feedUrl)
    }
}

/**
 * Plays [episodes] from [index] onwards (this one, then the older ones) and
 * makes sure each is recorded in the history once it actually starts.
 */
class PlayEpisodesUseCase @Inject constructor(
    private val repo: PodcastRepository,
    private val player: PlayerController,
) {
    operator fun invoke(episodes: List<Episode>, index: Int, podcastTitle: String) {
        val queue = episodes.drop(index)
        if (queue.isEmpty()) return
        repo.prepareForPlayback(queue, podcastTitle)
        player.play(queue.map { it.toQueueItem(podcastTitle) }, 0)
    }
}

class MarkEpisodeStartedUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(episodeId: String) = repo.markStarted(episodeId)
}

/** Manually marks an episode as finished (or not, which also rewinds it). */
class SetEpisodeCompletedUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(episode: Episode, podcastTitle: String, completed: Boolean): Result<Unit> =
        runCatchingApp { repo.setCompleted(episode, podcastTitle, completed) }
}

class RemoveFromHistoryUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(episodeId: String): Result<Unit> = runCatchingApp { repo.removeFromHistory(episodeId) }
}

/**
 * Persists listening progress. Positions within the last [COMPLETION_TAIL_MS]
 * count as finished so the next play starts a new episode, not the credits.
 */
class SaveEpisodeProgressUseCase @Inject constructor(private val repo: PodcastRepository) {
    suspend operator fun invoke(episodeId: String, positionMs: Long, durationMs: Long) {
        if (positionMs < 0) return
        repo.saveProgress(episodeId, positionMs, durationMs)
    }

    companion object {
        const val COMPLETION_TAIL_MS = 30_000L
        fun isCompleted(positionMs: Long, durationMs: Long) =
            durationMs > 0 && positionMs >= durationMs - COMPLETION_TAIL_MS
    }
}

/** Maps an episode to a source-independent queue entry. */
fun Episode.toQueueItem(podcastTitle: String) = QueueItem(
    mediaId = QueueItem.mediaId(MediaKind.PODCAST, id),
    kind = MediaKind.PODCAST,
    uri = audioUrl,
    title = title,
    subtitle = podcastTitle,
    artworkUri = artworkUrl,
    durationMs = durationMs,
    // Resume where the user stopped, unless the episode was finished.
    startPositionMs = if (completed) 0 else positionMs,
)
