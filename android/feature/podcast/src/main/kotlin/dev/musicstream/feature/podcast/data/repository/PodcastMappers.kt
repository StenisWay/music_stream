package dev.musicstream.feature.podcast.data.repository

import dev.musicstream.core.database.entity.EpisodeEntity
import dev.musicstream.core.database.entity.EpisodeHistoryEntity
import dev.musicstream.core.database.entity.PodcastEntity
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.EpisodeProgress
import dev.musicstream.feature.podcast.domain.model.HistoryEntry
import dev.musicstream.feature.podcast.domain.model.Podcast

internal fun PodcastEntity.toDomain() = Podcast(feedUrl, title, author, description, artworkUrl, lastRefreshedAt)

/** Progress is not stored with the episode; overlay it with [Episode.withProgress]. */
internal fun EpisodeEntity.toDomain() =
    Episode(id, feedUrl, title, description, audioUrl, publishedAt, durationMs, 0, false, artworkUrl)

internal fun EpisodeHistoryEntity.toProgress() = EpisodeProgress(positionMs, durationMs, completed, lastPlayedAt)

internal fun EpisodeHistoryEntity.toHistoryEntry() = HistoryEntry(
    Episode(episodeId, feedUrl, title, "", audioUrl, 0, durationMs, positionMs, completed, artworkUrl),
    podcastTitle,
    lastPlayedAt,
)

internal fun Episode.toHistory(podcastTitle: String, positionMs: Long, completed: Boolean, lastPlayedAt: Long) =
    EpisodeHistoryEntity(id, feedUrl, podcastTitle, title, audioUrl, artworkUrl, durationMs, positionMs, completed, lastPlayedAt)
