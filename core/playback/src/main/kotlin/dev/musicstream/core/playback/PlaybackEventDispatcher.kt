package dev.musicstream.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.musicstream.core.playback.api.PlaybackEventListener
import dev.musicstream.core.playback.api.QueueItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns player events into [PlaybackEventListener] callbacks: a position
 * checkpoint every 10 s while playing, on pause and when the item changes
 * (so podcasts can resume "halfway"), and an item-started event (radio play
 * counts). The player stays unaware of which features listen.
 */
@Singleton
class PlaybackEventDispatcher @Inject constructor(
    private val listeners: Set<@JvmSuppressWildcards PlaybackEventListener>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: Player? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicker() else { ticker?.cancel(); checkpointCurrent() }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            val (kind, key) = QueueItem.parse(mediaItem.mediaId) ?: return
            dispatch { it.onItemStarted(kind, key) }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // Leaving an item (skip/auto-advance): report its final position.
            if (old.mediaItemIndex != new.mediaItemIndex) old.mediaItem?.let { checkpoint(it, old.positionMs) }
        }
    }

    fun attach(player: Player) {
        this.player = player
        player.addListener(listener)
    }

    fun detach() {
        checkpointCurrent()
        player?.removeListener(listener)
        ticker?.cancel()
        player = null
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(10_000)
                checkpointCurrent()
            }
        }
    }

    private fun checkpointCurrent() {
        val p = player ?: return
        p.currentMediaItem?.let { checkpoint(it, p.currentPosition, p.duration) }
    }

    private fun checkpoint(item: MediaItem, positionMs: Long, durationMs: Long = C_TIME_UNSET) {
        val (kind, key) = QueueItem.parse(item.mediaId) ?: return
        val duration = if (durationMs > 0) durationMs else 0
        dispatch { it.onPositionCheckpoint(kind, key, positionMs, duration) }
    }

    private fun dispatch(event: suspend (PlaybackEventListener) -> Unit) {
        if (listeners.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            // One failing listener must not stop the others.
            listeners.forEach { runCatching { event(it) } }
        }
    }

    private companion object {
        const val C_TIME_UNSET = Long.MIN_VALUE + 1
    }
}
