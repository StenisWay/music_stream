package dev.musicstream.feature.podcast.data

import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.PlaybackEventListener
import dev.musicstream.feature.podcast.domain.usecase.SaveEpisodeProgressUseCase
import javax.inject.Inject

/** Remembers where podcast episodes were left ("listen halfway, resume later"). */
class EpisodeProgressRecorder @Inject constructor(
    private val saveProgress: SaveEpisodeProgressUseCase,
) : PlaybackEventListener {
    override suspend fun onPositionCheckpoint(kind: MediaKind, key: String, positionMs: Long, durationMs: Long) {
        if (kind == MediaKind.PODCAST) saveProgress(key, positionMs, durationMs)
    }
}
