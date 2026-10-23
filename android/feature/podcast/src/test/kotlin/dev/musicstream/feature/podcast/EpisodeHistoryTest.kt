package dev.musicstream.feature.podcast

import dev.musicstream.core.database.dao.EpisodeDao
import dev.musicstream.core.database.dao.EpisodeHistoryDao
import dev.musicstream.core.database.dao.PodcastDao
import dev.musicstream.core.database.entity.EpisodeEntity
import dev.musicstream.core.database.entity.EpisodeHistoryEntity
import dev.musicstream.core.database.entity.PodcastEntity
import dev.musicstream.feature.podcast.data.remote.ITunesApi
import dev.musicstream.feature.podcast.data.repository.PodcastRepositoryImpl
import dev.musicstream.feature.podcast.domain.model.Episode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class EpisodeHistoryTest {
    private class FakeHistory : EpisodeHistoryDao {
        val rows = MutableStateFlow<Map<String, EpisodeHistoryEntity>>(emptyMap())
        override fun observeAll() = rows.map { it.values.sortedByDescending { e -> e.lastPlayedAt } }
        override fun observeByFeed(feedUrl: String) = rows.map { it.values.filter { e -> e.feedUrl == feedUrl } }
        override suspend fun get(id: String) = rows.value[id]
        override suspend fun upsert(entry: EpisodeHistoryEntity) { rows.value += entry.episodeId to entry }
        override suspend fun delete(id: String) { rows.value -= id }
    }

    private class FakeEpisodes(private val stored: List<EpisodeEntity> = emptyList()) : EpisodeDao {
        override suspend fun page(feedUrl: String, offset: Int, limit: Int) = stored.drop(offset).take(limit)
        override suspend fun count(feedUrl: String) = stored.size
        override suspend fun get(id: String) = stored.firstOrNull { it.id == id }
        override suspend fun insertIgnore(episodes: List<EpisodeEntity>) = Unit
        override suspend fun updateMetadata(id: String, title: String, description: String, audioUrl: String, publishedAt: Long, durationMs: Long, artworkUrl: String?) = Unit
    }

    private class FakePodcasts(private val stored: List<PodcastEntity> = emptyList()) : PodcastDao {
        override fun observeAll() = MutableStateFlow(stored)
        override suspend fun all() = stored
        override suspend fun get(feedUrl: String) = stored.firstOrNull { it.feedUrl == feedUrl }
        override suspend fun upsert(podcast: PodcastEntity) = Unit
        override suspend fun delete(feedUrl: String) = Unit
        override suspend fun setOlderPageUrl(feedUrl: String, url: String?) = Unit
    }

    /** The network is never touched by the history paths. */
    private val noItunes = Proxy.newProxyInstance(ITunesApi::class.java.classLoader, arrayOf(ITunesApi::class.java)) { _, _, _ -> error("no network") } as ITunesApi

    private fun repo(history: FakeHistory, episodes: FakeEpisodes = FakeEpisodes(), podcasts: FakePodcasts = FakePodcasts()) =
        PodcastRepositoryImpl(noItunes, podcasts, episodes, history, OkHttpClient())

    private fun episode(id: String) = Episode(id, "feed", "Ep $id", "", "https://cdn/$id.mp3", 0, 600_000, 0, false, null)

    @Test
    fun previewEpisodesAreRecordedOnceTheyStart() = runBlocking {
        val history = FakeHistory()
        val repo = repo(history)
        repo.prepareForPlayback(listOf(episode("a"), episode("b")), "Show")

        repo.markStarted("a").collect()
        val a = history.get("a")!!
        assertEquals("Show", a.podcastTitle)
        assertTrue(a.lastPlayedAt > 0)
        // Queued but not started yet: not in the history.
        assertNull(history.get("b"))
        // Unknown episode (neither queued nor stored): ignored, no crash.
        repo.saveProgress("zzz", 1_000, 0).collect()
        assertNull(history.get("zzz"))
    }

    @Test
    fun autoAdvanceWithoutDurationStillCompletes() = runBlocking {
        val history = FakeHistory()
        val repo = repo(history)
        repo.prepareForPlayback(listOf(episode("a")), "Show")
        repo.saveProgress("a", 120_000, 600_000).collect()
        assertFalse(history.get("a")!!.completed)

        // The player reports the item it left with durationMs = 0.
        repo.saveProgress("a", 599_000, 0).collect()
        val a = history.get("a")!!
        assertTrue(a.completed)
        assertEquals(600_000, a.durationMs)
    }

    @Test
    fun storedEpisodesAreRecordedWithoutPreparing() = runBlocking {
        val history = FakeHistory()
        val stored = EpisodeEntity("s", "feed", "Stored", "", "https://cdn/s.mp3", 0, 600_000, null)
        val repo = repo(history, FakeEpisodes(listOf(stored)), FakePodcasts(listOf(PodcastEntity("feed", "Show", "", "", null, 0, 0))))
        repo.saveProgress("s", 30_000, 600_000).collect()
        assertEquals("Show", history.get("s")!!.podcastTitle)
        assertEquals(30_000, history.get("s")!!.positionMs)
    }

    @Test
    fun markingUnplayedRewinds() = runBlocking {
        val history = FakeHistory()
        val repo = repo(history)
        repo.setCompleted(episode("a"), "Show", completed = true).collect()
        assertTrue(history.get("a")!!.completed)
        repo.setCompleted(episode("a"), "Show", completed = false).collect()
        assertFalse(history.get("a")!!.completed)
        assertEquals(0, history.get("a")!!.positionMs)
    }
}
