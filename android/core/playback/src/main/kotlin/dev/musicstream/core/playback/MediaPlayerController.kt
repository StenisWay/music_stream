package dev.musicstream.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.PlaybackError
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.playback.api.PlayerState
import dev.musicstream.core.playback.api.QueueItem
import dev.musicstream.core.playback.api.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlayerController] backed by a Media3 [MediaController] connected to
 * [PlaybackService]. Commands issued before the connection is ready are
 * queued and replayed.
 */
@Singleton
class MediaPlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var positionJob: Job? = null

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { block(it); return }
        pending += block
        connect()
    }

    private fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                controllerFuture = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            publish(c)
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
        }, MoreExecutors.directExecutor())
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = controller?.currentMediaItem?.let(MediaItemMapper::toQueueItem)
            _state.update { it.copy(error = mapError(error, current)) }
        }
    }

    private fun mapError(e: PlaybackException, item: QueueItem?): PlaybackError {
        val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        return when {
            http?.responseCode == 401 -> PlaybackError.Unauthorized
            item?.kind == MediaKind.RADIO || e.errorCode in SOURCE_ERRORS -> PlaybackError.SourceUnavailable(item?.title.orEmpty())
            else -> PlaybackError.Other(e.errorCodeName)
        }
    }

    private fun publish(p: Player) {
        val queue = (0 until p.mediaItemCount).map { MediaItemMapper.toQueueItem(p.getMediaItemAt(it)) }
        val index = p.currentMediaItemIndex
        _state.update {
            it.copy(
                current = queue.getOrNull(index),
                queue = queue,
                currentIndex = if (queue.isEmpty()) -1 else index,
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d > 0 } ?: queue.getOrNull(index)?.durationMs ?: 0,
                shuffle = p.shuffleModeEnabled,
                repeat = when (p.repeatMode) {
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    else -> RepeatMode.OFF
                },
            )
        }
        if (p.isPlaying) startPositionUpdates() else positionJob?.cancel()
    }

    /** The player does not emit position events; poll while playing. */
    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                controller?.let { c -> _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) } }
                delay(500)
            }
        }
    }

    override fun play(queue: List<QueueItem>, startIndex: Int) {
        if (queue.isEmpty()) return
        val index = startIndex.coerceIn(queue.indices)
        withController { c ->
            c.setMediaItems(queue.map(MediaItemMapper::toMediaItem), index, queue[index].startPositionMs)
            c.prepare()
            c.play()
        }
        _state.update { it.copy(error = null) }
    }

    override fun enqueue(items: List<QueueItem>) = withController { c ->
        c.addMediaItems(items.map(MediaItemMapper::toMediaItem))
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
    }

    override fun togglePlayPause() = withController { c ->
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            c.play()
        }
    }

    override fun next() = withController { it.seekToNext() }
    override fun previous() = withController { it.seekToPrevious() }
    override fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }
    override fun skipToIndex(index: Int) = withController { it.seekToDefaultPosition(index) }
    override fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    override fun setRepeat(mode: RepeatMode) = withController {
        it.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    override fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        val SOURCE_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }
}
