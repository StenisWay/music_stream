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
    val positionMs: Long = 0,
    val completed: Boolean = false,
    val lastPlayedAt: Long = 0,
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
