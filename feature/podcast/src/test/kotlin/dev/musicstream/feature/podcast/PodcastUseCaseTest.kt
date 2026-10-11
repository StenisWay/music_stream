package dev.musicstream.feature.podcast

import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.QueueItem
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.usecase.SaveEpisodeProgressUseCase
import dev.musicstream.feature.podcast.domain.usecase.toQueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastUseCaseTest {
    @Test
    fun completedEpisodesRestartFromBeginning() {
        val e = Episode("g1", "f", "t", "", "https://cdn/a.mp3", 0, 3_600_000, 3_590_000, true, null)
        assertEquals(0, e.toQueueItem("p").startPositionMs)
        assertEquals(1_000L, e.copy(completed = false, positionMs = 1_000).toQueueItem("p").startPositionMs)
    }

    @Test
    fun completionThreshold() {
        assertTrue(SaveEpisodeProgressUseCase.isCompleted(3_580_000, 3_600_000))
        assertFalse(SaveEpisodeProgressUseCase.isCompleted(1_000, 3_600_000))
        assertFalse(SaveEpisodeProgressUseCase.isCompleted(1_000, 0))
    }

    @Test
    fun mediaIdRoundTrip() {
        val id = QueueItem.mediaId(MediaKind.PODCAST, "guid:with:colons")
        assertEquals(MediaKind.PODCAST to "guid:with:colons", QueueItem.parse(id))
    }
}
