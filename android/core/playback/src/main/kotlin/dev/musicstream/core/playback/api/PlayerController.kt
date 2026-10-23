package dev.musicstream.core.playback.api

import kotlinx.coroutines.flow.StateFlow

/**
 * Playback port. Implemented by [dev.musicstream.core.playback.MediaPlayerController] (Media3); features
 * only talk to this interface.
 */
interface PlayerController {
    val state: StateFlow<PlayerState>
    fun play(queue: List<QueueItem>, startIndex: Int = 0)
    fun enqueue(items: List<QueueItem>)
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun skipToIndex(index: Int)
    fun setShuffle(enabled: Boolean)
    fun setRepeat(mode: RepeatMode)
    fun dismissError()
}
