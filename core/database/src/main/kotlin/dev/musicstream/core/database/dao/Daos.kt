package dev.musicstream.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.musicstream.core.database.entity.EpisodeEntity
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
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE feedUrl = :feedUrl ORDER BY publishedAt DESC")
    fun observeByFeed(feedUrl: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observe(id: String): Flow<EpisodeEntity?>

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

    /** Inserts new episodes and refreshes metadata of known ones while keeping listening progress. */
    @Transaction
    suspend fun merge(episodes: List<EpisodeEntity>) {
        insertIgnore(episodes)
        episodes.forEach { e ->
            updateMetadata(e.id, e.title, e.description, e.audioUrl, e.publishedAt, e.durationMs, e.artworkUrl)
        }
    }

    @Query(
        """UPDATE episodes SET positionMs = :positionMs, completed = :completed, lastPlayedAt = :now,
           durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END WHERE id = :id""",
    )
    suspend fun saveProgress(id: String, positionMs: Long, durationMs: Long, completed: Boolean, now: Long)
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
