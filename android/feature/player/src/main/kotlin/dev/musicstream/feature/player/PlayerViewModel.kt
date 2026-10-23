package dev.musicstream.feature.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.playback.api.PlayerState
import dev.musicstream.core.playback.api.QueueItem
import dev.musicstream.core.playback.api.RepeatMode
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Drives the mini player and the now-playing sheet. */
@HiltViewModel
class PlayerViewModel @Inject constructor(private val player: PlayerController) : ViewModel() {
    /** Handed to feature screens so they can start playback without depending on this feature. */
    val controller: PlayerController get() = player

    val state: StateFlow<PlayerState> = player.state

    fun play(queue: List<QueueItem>, index: Int = 0) = player.play(queue, index)
    fun toggle() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun skipTo(index: Int) = player.skipToIndex(index)
    fun toggleShuffle() = player.setShuffle(!state.value.shuffle)
    fun cycleRepeat() = player.setRepeat(
        when (state.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        },
    )
    fun dismissError() = player.dismissError()
}
