package dev.musicstream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.musicstream.core.database.entity.EpisodeEntity
import dev.musicstream.core.database.entity.EpisodeHistoryEntity
import dev.musicstream.core.database.entity.PodcastEntity
import dev.musicstream.core.database.entity.StationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {
    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts")
    suspend fun all(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun get(feedUrl: String): PodcastEntity?

    @Upsert
    suspend fun upsert(podcast: PodcastEntity)

    @Query("DELETE FROM podcasts WHERE feedUrl = :feedUrl")
    suspend fun delete(feedUrl: String)

    @Query("UPDATE podcasts SET olderPageUrl = :url WHERE feedUrl = :feedUrl")
    suspend fun setOlderPageUrl(feedUrl: String, url: String?)
}

@Dao
interface EpisodeDao {
    /** Newest first; `id` breaks ties so pages never overlap. */
    @Query("SELECT * FROM episodes WHERE feedUrl = :feedUrl ORDER BY publishedAt DESC, id LIMIT :limit OFFSET :offset")
    suspend fun page(feedUrl: String, offset: Int, limit: Int): List<EpisodeEntity>

    @Query("SELECT COUNT(*) FROM episodes WHERE feedUrl = :feedUrl")
    suspend fun count(feedUrl: String): Int

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun get(id: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(episodes: List<EpisodeEntity>)

    @Query(
        """UPDATE episodes SET title = :title, description = :description, audioUrl = :audioUrl,
           publishedAt = :publishedAt, durationMs = :durationMs, artworkUrl = :artworkUrl WHERE id = :id""",
    )
    suspend fun updateMetadata(
        id: String, title: String, description: String, audioUrl: String,
        publishedAt: Long, durationMs: Long, artworkUrl: String?,
    )

    /** Inserts new episodes and refreshes the metadata of known ones. */
    @Transaction
    suspend fun merge(episodes: List<EpisodeEntity>) {
        insertIgnore(episodes)
        episodes.forEach { e ->
            updateMetadata(e.id, e.title, e.description, e.audioUrl, e.publishedAt, e.durationMs, e.artworkUrl)
        }
    }
}

@Dao
interface EpisodeHistoryDao {
    @Query("SELECT * FROM episode_history ORDER BY lastPlayedAt DESC")
    fun observeAll(): Flow<List<EpisodeHistoryEntity>>

    @Query("SELECT * FROM episode_history WHERE feedUrl = :feedUrl")
    fun observeByFeed(feedUrl: String): Flow<List<EpisodeHistoryEntity>>

    @Query("SELECT * FROM episode_history WHERE episodeId = :id")
    suspend fun get(id: String): EpisodeHistoryEntity?

    @Upsert
    suspend fun upsert(entry: EpisodeHistoryEntity)

    @Query("DELETE FROM episode_history WHERE episodeId = :id")
    suspend fun delete(id: String)
}

@Dao
interface StationDao {
    @Query("SELECT * FROM radio_favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<StationEntity>>

    @Query("SELECT id FROM radio_favorites")
    suspend fun ids(): List<String>

    @Upsert
    suspend fun upsert(station: StationEntity)

    @Query("DELETE FROM radio_favorites WHERE id = :id")
    suspend fun delete(id: String)
}
