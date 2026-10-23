package dev.musicstream.core.playback.api

/**
 * Extension point for features that react to playback without the player
 * knowing about them (podcast progress, radio play counts). Contribute an
 * implementation with Hilt `@IntoSet`; callbacks run on a background thread.
 */
interface PlaybackEventListener {
    /** An item became the current one. */
    suspend fun onItemStarted(kind: MediaKind, key: String) {}

    /**
     * Periodic / pause / item-change snapshot of where [key] was left.
     * [durationMs] is 0 when unknown.
     */
    suspend fun onPositionCheckpoint(kind: MediaKind, key: String, positionMs: Long, durationMs: Long) {}
}
