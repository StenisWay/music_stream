package dev.musicstream.core.playback.api

/** What kind of audio a queue entry is; drives playback policies. */
enum class MediaKind { MUSIC, PODCAST, RADIO }

/**
 * A playable entry independent of its source. [uri] may point at the home
 * server (virtual host), a podcast CDN or a radio stream.
 */
data class QueueItem(
    val mediaId: String,
    val kind: MediaKind,
    val uri: String,
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val durationMs: Long = 0,
    val startPositionMs: Long = 0,
) {
    companion object {
        fun mediaId(kind: MediaKind, key: String) = "${kind.name.lowercase()}:$key"
        fun parse(mediaId: String): Pair<MediaKind, String>? {
            val kind = MediaKind.entries.firstOrNull { mediaId.startsWith(it.name.lowercase() + ":") } ?: return null
            return kind to mediaId.substringAfter(':')
        }
    }
}

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val current: QueueItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queue: List<QueueItem> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val error: PlaybackError? = null,
)

sealed interface PlaybackError {
    /** The source could not be reached (e.g. radio stream offline). */
    data class SourceUnavailable(val title: String) : PlaybackError
    data object Unauthorized : PlaybackError
    data class Other(val message: String) : PlaybackError
}
