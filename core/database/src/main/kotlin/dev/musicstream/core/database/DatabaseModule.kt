package dev.musicstream.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "musicstream.db")
            .addMigrations(Migrations.MIGRATION_1_2)
            .build()

    @Provides fun podcastDao(db: AppDatabase) = db.podcasts()
    @Provides fun episodeDao(db: AppDatabase) = db.episodes()
    @Provides fun episodeHistoryDao(db: AppDatabase) = db.episodeHistory()
    @Provides fun stationDao(db: AppDatabase) = db.stations()
}
