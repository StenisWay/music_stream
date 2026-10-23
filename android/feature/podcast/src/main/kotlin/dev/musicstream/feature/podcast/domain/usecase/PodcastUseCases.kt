package dev.musicstream.feature.podcast.domain.usecase

import dev.musicstream.core.common.Page
import dev.musicstream.core.common.asResult
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class SearchPodcastsUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(term: String): Flow<Result<List<PodcastSearchResult>>> {
        val t = term.trim()
        if (t.length < 2) return flowOf(Result.success(emptyList()))
        return repo.search(t).asResult()
    }
}

class ObserveSubscriptionsUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(): Flow<List<Podcast>> = repo.subscriptions
}

class GetEpisodePageUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(feedUrl: String, offset: Int, limit: Int): Flow<Result<Page<Episode>>> =
        repo.episodePage(feedUrl, offset, limit).asResult()
}

class ObserveEpisodeProgressUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(feedUrl: String): Flow<Map<String, EpisodeProgress>> = repo.progress(feedUrl)
}

class ObserveHistoryUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(): Flow<List<HistoryEntry>> = repo.history
}

/** Reads a feed page without subscribing; [pageUrl] is a previous page's `nextPageUrl`. */
class FetchFeedPageUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(feedUrl: String, pageUrl: String = feedUrl): Flow<Result<FeedPage>> =
        repo.fetchFeedPage(feedUrl, pageUrl).asResult()
}

class SubscribePodcastUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(feedUrl: String): Flow<Result<Podcast>> = repo.subscribe(feedUrl.trim()).asResult()
}

class UnsubscribePodcastUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(feedUrl: String): Flow<Result<Unit>> = repo.unsubscribe(feedUrl).asResult()
}

class RefreshPodcastsUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(feedUrl: String? = null): Flow<Result<Unit>> =
        (if (feedUrl == null) repo.refreshAll() else repo.refresh(feedUrl)).asResult()
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
    operator fun invoke(episodeId: String): Flow<Result<Unit>> = repo.markStarted(episodeId).asResult()
}

/** Manually marks an episode as finished (or not, which also rewinds it). */
class SetEpisodeCompletedUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(episode: Episode, podcastTitle: String, completed: Boolean): Flow<Result<Unit>> =
        repo.setCompleted(episode, podcastTitle, completed).asResult()
}

class RemoveFromHistoryUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(episodeId: String): Flow<Result<Unit>> = repo.removeFromHistory(episodeId).asResult()
}

/**
 * Persists listening progress. Positions within the last [COMPLETION_TAIL_MS]
 * count as finished so the next play starts a new episode, not the credits.
 */
class SaveEpisodeProgressUseCase @Inject constructor(private val repo: PodcastRepository) {
    /** A negative position (player not ready) saves nothing and emits nothing. */
    operator fun invoke(episodeId: String, positionMs: Long, durationMs: Long): Flow<Result<Unit>> {
        if (positionMs < 0) return emptyFlow()
        return repo.saveProgress(episodeId, positionMs, durationMs).asResult()
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
