package dev.musicstream.feature.podcast.data.repository

import dev.musicstream.core.common.AppError
import dev.musicstream.core.database.dao.EpisodeDao
import dev.musicstream.core.database.dao.PodcastDao
import dev.musicstream.core.database.entity.EpisodeEntity
import dev.musicstream.core.database.entity.PodcastEntity
import dev.musicstream.core.network.apiCall
import dev.musicstream.core.network.di.PublicClient
import dev.musicstream.feature.podcast.data.remote.ITunesApi
import dev.musicstream.feature.podcast.data.rss.ParsedFeed
import dev.musicstream.feature.podcast.data.rss.RssParser
import dev.musicstream.feature.podcast.domain.model.PodcastSearchResult
import dev.musicstream.feature.podcast.domain.repository.PodcastRepository
import dev.musicstream.feature.podcast.domain.usecase.SaveEpisodeProgressUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Podcasts are fetched directly from their public RSS feeds, so they work
 * even when the home PC is off. Subscriptions and progress live in Room.
 */
@Singleton
class PodcastRepositoryImpl @Inject constructor(
    private val itunes: ITunesApi,
    private val podcasts: PodcastDao,
    private val episodes: EpisodeDao,
    @PublicClient private val http: OkHttpClient,
) : PodcastRepository {

    override val subscriptions = podcasts.observeAll().map { list -> list.map { it.toDomain() } }

    override fun episodes(feedUrl: String) = episodes.observeByFeed(feedUrl).map { list -> list.map { it.toDomain() } }

    override fun episode(id: String) = episodes.observe(id).map { it?.toDomain() }

    override suspend fun search(term: String): List<PodcastSearchResult> = apiCall {
        itunes.searchPodcasts(term).results.mapNotNull { r ->
            val feed = r.feedUrl ?: return@mapNotNull null
            PodcastSearchResult(feed, r.collectionName, r.artistName, r.artworkUrl600 ?: r.artworkUrl100)
        }
    }

    override suspend fun subscribe(feedUrl: String) = withContext(Dispatchers.IO) {
        val feed = fetch(feedUrl)
        val now = System.currentTimeMillis()
        val entity = PodcastEntity(feedUrl, feed.title, feed.author, feed.description, feed.artworkUrl, now, now)
        podcasts.upsert(entity)
        episodes.merge(feed.toEntities(feedUrl))
        entity.toDomain()
    }

    override suspend fun unsubscribe(feedUrl: String) = podcasts.delete(feedUrl)

    override suspend fun refresh(feedUrl: String) = withContext(Dispatchers.IO) {
        val existing = podcasts.get(feedUrl) ?: throw AppError.NotFound()
        val feed = fetch(feedUrl)
        podcasts.upsert(
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

    override suspend fun saveProgress(episodeId: String, positionMs: Long, durationMs: Long) {
        val completed = SaveEpisodeProgressUseCase.isCompleted(positionMs, durationMs)
        episodes.saveProgress(episodeId, positionMs, durationMs, completed, System.currentTimeMillis())
    }

    private fun fetch(feedUrl: String): ParsedFeed = try {
        val req = Request.Builder().url(feedUrl).header("Accept", "application/rss+xml, application/xml, text/xml").build()
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
