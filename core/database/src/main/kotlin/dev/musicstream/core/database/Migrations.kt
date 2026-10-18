package dev.musicstream.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {
    /**
     * v2: listening progress moves out of `episodes` into `episode_history`
     * (kept across unsubscribe, also records previews), and podcasts remember
     * the RFC 5005 page with older episodes. Statements are plain SQL so the
     * JVM test can replay them; table SQL is copied from schemas/2.json.
     */
    val MIGRATION_1_2_SQL = listOf(
        "ALTER TABLE `podcasts` ADD COLUMN `olderPageUrl` TEXT",
        "CREATE TABLE IF NOT EXISTS `episode_history` (`episodeId` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `podcastTitle` TEXT NOT NULL, `title` TEXT NOT NULL, `audioUrl` TEXT NOT NULL, `artworkUrl` TEXT, `durationMs` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, PRIMARY KEY(`episodeId`))",
        "CREATE INDEX IF NOT EXISTS `index_episode_history_feedUrl` ON `episode_history` (`feedUrl`)",
        "CREATE INDEX IF NOT EXISTS `index_episode_history_lastPlayedAt` ON `episode_history` (`lastPlayedAt`)",
        """INSERT INTO `episode_history` (episodeId, feedUrl, podcastTitle, title, audioUrl, artworkUrl, durationMs, positionMs, completed, lastPlayedAt)
           SELECT e.id, e.feedUrl, p.title, e.title, e.audioUrl, e.artworkUrl, e.durationMs, e.positionMs, e.completed, e.lastPlayedAt
           FROM episodes e JOIN podcasts p ON p.feedUrl = e.feedUrl
           WHERE e.positionMs > 0 OR e.completed != 0 OR e.lastPlayedAt > 0""",
        "CREATE TABLE IF NOT EXISTS `episodes_new` (`id` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `audioUrl` TEXT NOT NULL, `publishedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `artworkUrl` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`feedUrl`) REFERENCES `podcasts`(`feedUrl`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "INSERT INTO `episodes_new` SELECT id, feedUrl, title, description, audioUrl, publishedAt, durationMs, artworkUrl FROM `episodes`",
        "DROP TABLE `episodes`",
        "ALTER TABLE `episodes_new` RENAME TO `episodes`",
        "CREATE INDEX IF NOT EXISTS `index_episodes_feedUrl` ON `episodes` (`feedUrl`)",
        "CREATE INDEX IF NOT EXISTS `index_episodes_publishedAt` ON `episodes` (`publishedAt`)",
    )

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_SQL.forEach(db::execSQL)
        }
    }
}
