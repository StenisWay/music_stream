package dev.musicstream.feature.discover.domain

import dev.musicstream.core.common.AppError
import dev.musicstream.feature.discover.domain.model.DownloadJob
import dev.musicstream.feature.discover.domain.model.DownloadStatus
import dev.musicstream.feature.discover.domain.model.OnlineSong
import dev.musicstream.feature.discover.domain.model.OnlineSource
import dev.musicstream.feature.discover.domain.model.SongInfo
import dev.musicstream.feature.discover.domain.repository.DiscoverRepository
import dev.musicstream.feature.discover.domain.usecase.AddToLibraryUseCase
import dev.musicstream.feature.discover.domain.usecase.ObserveDownloadsUseCase
import dev.musicstream.feature.discover.domain.usecase.SearchOnlineUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverUseCaseTest {
    private class FakeRepo : DiscoverRepository {
        val searches = mutableListOf<String>()
        var downloaded: SongInfo? = null
        /** Each call to downloads() returns the next list; the last one repeats. */
        var jobLists = listOf(emptyList<DownloadJob>())
        var downloadCalls = 0
        override fun search(query: String, source: OnlineSource): Flow<List<OnlineSong>> = flow {
            searches += query
            emit(listOf(song))
        }
        override fun download(url: String, info: SongInfo): Flow<DownloadJob> = flow {
            downloaded = info
            emit(DownloadJob(1, url, info, DownloadStatus.QUEUED, null))
        }
        override fun downloads(): Flow<List<DownloadJob>> = flow {
            emit(jobLists[minOf(downloadCalls++, jobLists.lastIndex)])
        }
    }

    private companion object {
        val song = OnlineSong("https://youtu.be/a", OnlineSource.YOUTUBE, "周杰倫【晴天】MV", "周杰倫", 319_000, null, "周杰倫", "晴天")
    }

    @Test
    fun blankQueryDoesNotHitTheServer() = runBlocking {
        val repo = FakeRepo()
        assertEquals(emptyList<OnlineSong>(), SearchOnlineUseCase(repo)("   ", OnlineSource.YOUTUBE).first().getOrThrow())
        SearchOnlineUseCase(repo)("  晴天 ", OnlineSource.YOUTUBE).first()
        assertEquals(listOf("晴天"), repo.searches)
    }

    @Test
    fun addTrimsAndRequiresTitle() = runBlocking {
        val repo = FakeRepo()
        val failure = AddToLibraryUseCase(repo)(song, SongInfo("周杰倫", "  ")).first().exceptionOrNull()
        assertTrue(failure is AppError.Invalid)
        AddToLibraryUseCase(repo)(song, SongInfo(" 周杰倫 ", " 晴天 ", " 葉惠美 ")).first().getOrThrow()
        assertEquals(SongInfo("周杰倫", "晴天", "葉惠美"), repo.downloaded)
    }

    @Test
    fun observeDownloadsPollsUntilNothingIsActive() = runBlocking {
        val repo = FakeRepo()
        val info = SongInfo("周杰倫", "晴天")
        repo.jobLists = listOf(
            listOf(DownloadJob(1, "u", info, DownloadStatus.DOWNLOADING, null)),
            listOf(DownloadJob(1, "u", info, DownloadStatus.DONE, null)),
        )
        val emitted = ObserveDownloadsUseCase(repo)(intervalMs = 0).toList().map { it.getOrThrow().single().status }
        assertEquals(listOf(DownloadStatus.DOWNLOADING, DownloadStatus.DONE), emitted)
    }
}
