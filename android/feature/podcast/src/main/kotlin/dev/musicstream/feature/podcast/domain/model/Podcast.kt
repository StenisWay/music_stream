package dev.musicstream.feature.podcast.domain.model

data class PodcastSearchResult(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artworkUrl: String?,
)

data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val lastRefreshedAt: Long,
)

/**
 * One fetched page of a feed, read straight from its RSS without storing
 * anything. [nextPageUrl] points at older episodes (RFC 5005 paged feeds).
 */
data class FeedPage(val podcast: Podcast, val episodes: List<Episode>, val nextPageUrl: String?)

/** Where the user left an episode; kept even after unsubscribing. */
data class EpisodeProgress(val positionMs: Long, val durationMs: Long, val completed: Boolean, val lastPlayedAt: Long)

/** A row of the listening history. */
data class HistoryEntry(val episode: Episode, val podcastTitle: String, val lastPlayedAt: Long)

data class Episode(
    val id: String,
    val feedUrl: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val publishedAt: Long,
    val durationMs: Long,
    val positionMs: Long,
    val completed: Boolean,
    val artworkUrl: String?,
) {
    /** 0..1 listening progress for the UI. */
    val progress: Float
        get() = if (completed) 1f else if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun withProgress(p: EpisodeProgress?): Episode = if (p == null) this else copy(
        positionMs = p.positionMs,
        completed = p.completed,
        durationMs = if (p.durationMs > 0) p.durationMs else durationMs,
    )
}
