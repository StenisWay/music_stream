package dev.musicstream.feature.discover.domain

import dev.musicstream.core.common.AppError
import dev.musicstream.feature.discover.domain.model.DownloadJob
import dev.musicstream.feature.discover.domain.model.DownloadStatus
import dev.musicstream.feature.discover.domain.model.OnlineSong
import dev.musicstream.feature.discover.domain.model.OnlineSource
import dev.musicstream.feature.discover.domain.model.SongInfo
import dev.musicstream.feature.discover.domain.repository.DiscoverRepository
import dev.musicstream.feature.discover.domain.usecase.AddToLibraryUseCase
import dev.musicstream.feature.discover.domain.usecase.SearchOnlineUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverUseCaseTest {
    private class FakeRepo : DiscoverRepository {
        val searches = mutableListOf<String>()
        var downloaded: SongInfo? = null
        override suspend fun search(query: String, source: OnlineSource): List<OnlineSong> {
            searches += query
            return listOf(song)
        }
        override suspend fun download(url: String, info: SongInfo): DownloadJob {
            downloaded = info
            return DownloadJob(1, url, info, DownloadStatus.QUEUED, null)
        }
        override suspend fun downloads() = emptyList<DownloadJob>()
    }

    private companion object {
        val song = OnlineSong("https://youtu.be/a", OnlineSource.YOUTUBE, "周杰倫【晴天】MV", "周杰倫", 319_000, null, "周杰倫", "晴天")
    }

    @Test
    fun blankQueryDoesNotHitTheServer() = runBlocking {
        val repo = FakeRepo()
        assertEquals(emptyList<OnlineSong>(), SearchOnlineUseCase(repo)("   ", OnlineSource.YOUTUBE).getOrThrow())
        SearchOnlineUseCase(repo)("  晴天 ", OnlineSource.YOUTUBE)
        assertEquals(listOf("晴天"), repo.searches)
    }

    @Test
    fun addTrimsAndRequiresTitle() = runBlocking {
        val repo = FakeRepo()
        val failure = AddToLibraryUseCase(repo)(song, SongInfo("周杰倫", "  ")).exceptionOrNull()
        assertTrue(failure is AppError.Invalid)
        AddToLibraryUseCase(repo)(song, SongInfo(" 周杰倫 ", " 晴天 ", " 葉惠美 ")).getOrThrow()
        assertEquals(SongInfo("周杰倫", "晴天", "葉惠美"), repo.downloaded)
    }
}
