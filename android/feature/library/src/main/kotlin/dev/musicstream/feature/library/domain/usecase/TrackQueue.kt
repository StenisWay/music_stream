package dev.musicstream.feature.library.domain.usecase

import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.QueueItem
import dev.musicstream.feature.library.domain.model.Track

/** Maps a library track to a source-independent queue entry. */
fun Track.toQueueItem() = QueueItem(
    mediaId = QueueItem.mediaId(MediaKind.MUSIC, id.toString()),
    kind = MediaKind.MUSIC,
    uri = streamUrl,
    title = title,
    subtitle = listOf(displayArtist, album).filter { it.isNotBlank() }.joinToString(" · "),
    artworkUri = artworkUrl,
    durationMs = durationMs,
)
