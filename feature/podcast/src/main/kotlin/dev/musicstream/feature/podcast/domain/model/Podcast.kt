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
}
