package dev.musicstream.core.playback

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.musicstream.core.playback.api.PlaybackEventListener
import dev.musicstream.core.playback.api.PlayerController

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds abstract fun playerController(impl: MediaPlayerController): PlayerController

    /** Declared so the set may be empty when no feature contributes a listener. */
    @Multibinds abstract fun playbackEventListeners(): Set<PlaybackEventListener>
}
