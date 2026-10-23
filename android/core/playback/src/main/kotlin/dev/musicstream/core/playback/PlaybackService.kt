package dev.musicstream.core.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dev.musicstream.core.network.di.ServerClient
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Hosts the ExoPlayer and its MediaSession. Media3 turns the session into a
 * foreground service with a media notification while playing, and exposes
 * it to the lock screen, Bluetooth headsets and Android Auto.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject @ServerClient lateinit var httpClient: OkHttpClient
    @Inject lateinit var playbackEvents: PlaybackEventDispatcher

    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // One OkHttp client for every source: it routes/authenticates home
        // server URLs and uses normal TLS for podcast CDNs and radio streams.
        val http = OkHttpDataSource.Factory(httpClient)
        val dataSource = DefaultDataSource.Factory(this, http)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(
                // Larger buffer smooths over mobile-network hiccups when streaming from home.
                DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 2_500, 5_000).build(),
            )
            .build()

        playbackEvents.attach(player)

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .apply { sessionActivity()?.let(::setSessionActivity) }
            .build()
    }

    private fun sessionActivity(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Stop when the app is swiped away and nothing is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            playbackEvents.detach()
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map(MediaItemMapper::resolve).toMutableList())
    }
}
