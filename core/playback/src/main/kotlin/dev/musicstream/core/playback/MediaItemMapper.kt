package dev.musicstream.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.QueueItem

/** Converts between domain [QueueItem]s and Media3 [MediaItem]s. */
internal object MediaItemMapper {
    private const val KEY_KIND = "ms.kind"
    private const val KEY_DURATION = "ms.duration"
    private const val KEY_START = "ms.start"

    fun toMediaItem(item: QueueItem): MediaItem {
        val uri = Uri.parse(item.uri)
        val extras = Bundle().apply {
            putString(KEY_KIND, item.kind.name)
            putLong(KEY_DURATION, item.durationMs)
            putLong(KEY_START, item.startPositionMs)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.subtitle)
            .setArtworkUri(item.artworkUri?.let(Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(
                when (item.kind) {
                    MediaKind.MUSIC -> MediaMetadata.MEDIA_TYPE_MUSIC
                    MediaKind.PODCAST -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                    MediaKind.RADIO -> MediaMetadata.MEDIA_TYPE_RADIO_STATION
                },
            )
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(uri)
            // The URI also travels in request metadata: controllers in other
            // processes (Bluetooth, Auto) do not receive localConfiguration.
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(metadata)
            .apply { if (item.kind == MediaKind.RADIO) setLiveConfiguration(MediaItem.LiveConfiguration.UNSET) }
            .build()
    }

    fun toQueueItem(media: MediaItem): QueueItem {
        val md = media.mediaMetadata
        val extras = md.extras
        val kind = extras?.getString(KEY_KIND)?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
            ?: QueueItem.parse(media.mediaId)?.first ?: MediaKind.MUSIC
        return QueueItem(
            mediaId = media.mediaId,
            kind = kind,
            uri = (media.localConfiguration?.uri ?: media.requestMetadata.mediaUri)?.toString().orEmpty(),
            title = md.title?.toString().orEmpty(),
            subtitle = md.artist?.toString().orEmpty(),
            artworkUri = md.artworkUri?.toString(),
            durationMs = extras?.getLong(KEY_DURATION) ?: 0,
            startPositionMs = extras?.getLong(KEY_START) ?: 0,
        )
    }

    /** Restores the playable URI for items that arrived without localConfiguration. */
    fun resolve(media: MediaItem): MediaItem =
        if (media.localConfiguration != null) media
        else media.buildUpon().setUri(media.requestMetadata.mediaUri).build()
}
