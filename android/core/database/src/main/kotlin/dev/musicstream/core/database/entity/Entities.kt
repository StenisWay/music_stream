package dev.musicstream.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val feedUrl: String,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val subscribedAt: Long,
    val lastRefreshedAt: Long,
    /** RFC 5005 "next" page of the feed (older episodes) not fetched yet; null when there is none. */
    val olderPageUrl: String? = null,
)

@Entity(
    tableName = "episodes",
    foreignKeys = [ForeignKey(PodcastEntity::class, ["feedUrl"], ["feedUrl"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("feedUrl"), Index("publishedAt")],
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val feedUrl: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val publishedAt: Long,
    val durationMs: Long,
    val artworkUrl: String?,
)

/**
 * What the user listened to and whether they finished it. Independent of
 * subscriptions (no foreign key) so previews are recorded too and the
 * history survives unsubscribing; it carries enough metadata to replay.
 */
@Entity(tableName = "episode_history", indices = [Index("feedUrl"), Index("lastPlayedAt")])
data class EpisodeHistoryEntity(
    @PrimaryKey val episodeId: String,
    val feedUrl: String,
    val podcastTitle: String,
    val title: String,
    val audioUrl: String,
    val artworkUrl: String?,
    val durationMs: Long,
    val positionMs: Long,
    val completed: Boolean,
    val lastPlayedAt: Long,
)

@Entity(tableName = "radio_favorites")
data class StationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val streamUrl: String,
    val country: String,
    val tags: String,
    val faviconUrl: String?,
    val codec: String,
    val bitrateKbps: Int,
    val addedAt: Long,
)
