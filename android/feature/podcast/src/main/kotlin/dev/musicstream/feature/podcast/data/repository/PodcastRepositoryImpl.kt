package dev.musicstream.feature.podcast.data.repository

import dev.musicstream.core.common.AppError
import dev.musicstream.core.common.Page
import dev.musicstream.core.database.dao.EpisodeDao
import dev.musicstream.core.database.dao.EpisodeHistoryDao
import dev.musicstream.core.database.dao.PodcastDao
import dev.musicstream.core.database.entity.EpisodeEntity
import dev.musicstream.core.database.entity.EpisodeHistoryEntity
import dev.musicstream.core.database.entity.PodcastEntity
import dev.musicstream.core.network.apiCall
import dev.musicstream.core.network.di.PublicClient
import dev.musicstream.feature.podcast.data.remote.ITunesApi
import dev.musicstream.feature.podcast.data.rss.ParsedFeed
import dev.musicstream.feature.podcast.data.rss.RssParser
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.FeedPage
import dev.musicstream.feature.podcast.domain.model.Podcast
import dev.musicstream.feature.podcast.domain.model.PodcastSearchResult
import dev.musicstream.feature.podcast.domain.repository.PodcastRepository
import dev.musicstream.feature.podcast.domain.usecase.SaveEpisodeProgressUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Podcasts are fetched directly from their public RSS feeds, so they work
 * even when the home PC is off. Subscriptions and listening history live in Room.
 */
@Singleton
class PodcastRepositoryImpl @Inject constructor(
    private val itunes: ITunesApi,
    private val podcasts: PodcastDao,
    private val episodes: EpisodeDao,
    private val historyDao: EpisodeHistoryDao,
    @PublicClient private val http: OkHttpClient,
) : PodcastRepository {

    /** Serialises "fetch older page" so two scroll events do not walk the same page twice. */
    private val olderPages = Mutex()

    /** Metadata of queued episodes, for recording ones that are not stored (previews). */
    private val queued = ConcurrentHashMap<String, EpisodeHistoryEntity>()

    override val subscriptions = podcasts.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun episodePage(feedUrl: String, offset: Int, limit: Int): Page<Episode> = withContext(Dispatchers.IO) {
        var items = episodes.page(feedUrl, offset, limit)
        // Out of stored episodes: walk older RSS pages until this page is full or there are none left.
        while (items.size < limit && loadOlderPage(feedUrl)) {
            items = episodes.page(feedUrl, offset, limit)
        }
        val stored = episodes.count(feedUrl)
        val more = podcasts.get(feedUrl)?.olderPageUrl != null
        // An unfetched older page counts as "one more" so the pager asks again.
        Page(items.map { it.toDomain() }, total = stored + if (more) 1 else 0, offset = offset)
    }

    /** Fetches the next older page of a subscribed feed. Returns false when there is none. */
    private suspend fun loadOlderPage(feedUrl: String): Boolean = olderPages.withLock {
        val url = podcasts.get(feedUrl)?.olderPageUrl ?: return false
        val feed = fetch(url, feedUrl)
        episodes.merge(feed.toEntities(feedUrl))
        podcasts.setOlderPageUrl(feedUrl, feed.nextPageUrl?.takeIf { it != url }) // a page linking to itself ends the walk
        true
    }

    override suspend fun fetchFeedPage(feedUrl: String, url: String) = withContext(Dispatchers.IO) {
        val feed = fetch(url, feedUrl)
        FeedPage(
            Podcast(feedUrl, feed.title, feed.author, feed.description, feed.artworkUrl, System.currentTimeMillis()),
            feed.episodes.map {
                Episode(it.id, feedUrl, it.title, it.description, it.audioUrl, it.publishedAt, it.durationMs, 0, false, it.artworkUrl)
            }.sortedByDescending { it.publishedAt }, // same order as the stored episode list
            feed.nextPageUrl,
        )
    }

    override suspend fun search(term: String): List<PodcastSearchResult> = apiCall {
        itunes.searchPodcasts(term).results.mapNotNull { r ->
            val feed = r.feedUrl ?: return@mapNotNull null
            PodcastSearchResult(feed, r.collectionName, r.artistName, r.artworkUrl600 ?: r.artworkUrl100)
        }.distinctBy { it.feedUrl } // iTunes can list several collections that share one RSS feed
    }

    override suspend fun subscribe(feedUrl: String) = withContext(Dispatchers.IO) {
        val feed = fetch(feedUrl)
        val now = System.currentTimeMillis()
        val entity = PodcastEntity(feedUrl, feed.title, feed.author, feed.description, feed.artworkUrl, now, now, feed.nextPageUrl)
        podcasts.upsert(entity)
        episodes.merge(feed.toEntities(feedUrl))
        entity.toDomain()
    }

    /** Episodes go with the subscription; the listening history stays. */
    override suspend fun unsubscribe(feedUrl: String) = podcasts.delete(feedUrl)

    override suspend fun refresh(feedUrl: String) = withContext(Dispatchers.IO) {
        val existing = podcasts.get(feedUrl) ?: throw AppError.NotFound()
        val feed = fetch(feedUrl)
        podcasts.upsert(
            // olderPageUrl is kept: it tracks how far back we have walked, independent of new episodes.
            existing.copy(
                title = feed.title, author = feed.author, description = feed.description,
                artworkUrl = feed.artworkUrl ?: existing.artworkUrl, lastRefreshedAt = System.currentTimeMillis(),
            ),
        )
        episodes.merge(feed.toEntities(feedUrl))
    }

    override suspend fun refreshAll() = coroutineScope {
        val limit = Semaphore(4)
        val failures = podcasts.all().map { p ->
            async { limit.withPermit { runCatching { refresh(p.feedUrl) }.exceptionOrNull() } }
        }.awaitAll().filterNotNull()
        // One broken feed must not hide the others; report only if all failed.
        if (failures.isNotEmpty() && failures.size == podcasts.all().size) throw failures.first()
    }

    // Listening history

    override val history = historyDao.observeAll().map { list -> list.map { it.toHistoryEntry() } }

    override fun progress(feedUrl: String) =
        historyDao.observeByFeed(feedUrl).map { list -> list.associate { it.episodeId to it.toProgress() } }

    override fun prepareForPlayback(episodes: List<Episode>, podcastTitle: String) {
        queued.clear()
        episodes.forEach { queued[it.id] = it.toHistory(podcastTitle, positionMs = 0, completed = false, lastPlayedAt = 0) }
    }

    override suspend fun markStarted(episodeId: String) = withContext(Dispatchers.IO) {
        val entry = historyDao.get(episodeId) ?: newHistoryEntry(episodeId) ?: return@withContext
        historyDao.upsert(entry.copy(lastPlayedAt = System.currentTimeMillis()))
    }

    override suspend fun saveProgress(episodeId: String, positionMs: Long, durationMs: Long) = withContext(Dispatchers.IO) {
        val entry = historyDao.get(episodeId) ?: newHistoryEntry(episodeId) ?: return@withContext
        // Auto-advance reports the finished item without a duration: fall back to the known one.
        val duration = if (durationMs > 0) durationMs else entry.durationMs
        historyDao.upsert(
            entry.copy(
                positionMs = positionMs,
                durationMs = duration,
                completed = SaveEpisodeProgressUseCase.isCompleted(positionMs, duration),
                lastPlayedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun setCompleted(episode: Episode, podcastTitle: String, completed: Boolean) = withContext(Dispatchers.IO) {
        val entry = historyDao.get(episode.id) ?: episode.toHistory(podcastTitle, 0, false, 0)
        historyDao.upsert(
            entry.copy(
                completed = completed,
                positionMs = if (completed) entry.durationMs.coerceAtLeast(entry.positionMs) else 0,
                lastPlayedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeFromHistory(episodeId: String) = historyDao.delete(episodeId)

    /** Builds a history row from the queued metadata or the stored episode, if either is known. */
    private suspend fun newHistoryEntry(episodeId: String): EpisodeHistoryEntity? {
        queued[episodeId]?.let { return it }
        val e = episodes.get(episodeId) ?: return null
        val title = podcasts.get(e.feedUrl)?.title.orEmpty()
        return EpisodeHistoryEntity(e.id, e.feedUrl, title, e.title, e.audioUrl, e.artworkUrl, e.durationMs, 0, false, 0)
    }

    /** [url] is the page to load; [feedUrl] identifies the feed (episode ids are derived from it). */
    private fun fetch(url: String, feedUrl: String = url): ParsedFeed = try {
        val req = Request.Builder().url(url).header("Accept", "application/rss+xml, application/xml, text/xml").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AppError.Server(resp.code)
            RssParser.parse(resp.body.byteStream(), feedUrl)
        }
    } catch (e: AppError) {
        throw e
    } catch (e: org.xml.sax.SAXException) {
        throw AppError.Invalid("not a valid podcast feed")
    } catch (e: IllegalArgumentException) {
        throw AppError.Invalid("invalid feed URL")
    } catch (e: java.io.IOException) {
        throw AppError.Network(e)
    }

    private fun ParsedFeed.toEntities(feedUrl: String) = episodes.map {
        EpisodeEntity(it.id, feedUrl, it.title, it.description, it.audioUrl, it.publishedAt, it.durationMs, it.artworkUrl)
    }
}
