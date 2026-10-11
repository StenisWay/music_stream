package dev.musicstream.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import dev.musicstream.core.database.dao.EpisodeDao
import dev.musicstream.core.database.dao.PodcastDao
import dev.musicstream.core.database.dao.StationDao
import dev.musicstream.core.database.entity.EpisodeEntity
import dev.musicstream.core.database.entity.PodcastEntity
import dev.musicstream.core.database.entity.StationEntity

/**
 * Local data only: podcast subscriptions/progress and favourite radio
 * stations. These work without the home server (PC may be off).
 *
 * Room needs every entity in one database class, so the tables of all
 * features live here; each feature only sees its own DAO through Hilt.
 */
@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, StationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun podcasts(): PodcastDao
    abstract fun episodes(): EpisodeDao
    abstract fun stations(): StationDao
}
