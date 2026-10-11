package dev.musicstream.feature.podcast.domain.usecase

import dev.musicstream.core.common.runCatchingApp
import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.QueueItem
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.Podcast
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

class ObserveEpisodesUseCase @Inject constructor(private val repo: PodcastRepository) {
    operator fun invoke(feedUrl: String): Flow<List<Episode>> = repo.episodes(feedUrl)
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
