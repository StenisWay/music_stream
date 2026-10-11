package dev.musicstream.feature.podcast.data.repository

import dev.musicstream.core.database.entity.EpisodeEntity
import dev.musicstream.core.database.entity.PodcastEntity
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.Podcast

internal fun PodcastEntity.toDomain() = Podcast(feedUrl, title, author, description, artworkUrl, lastRefreshedAt)

internal fun EpisodeEntity.toDomain() =
    Episode(id, feedUrl, title, description, audioUrl, publishedAt, durationMs, positionMs, completed, artworkUrl)
