package dev.musicstream.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import dev.musicstream.core.network.di.ServerClient
import dev.musicstream.feature.podcast.data.work.PodcastRefreshWorker
import dev.musicstream.feature.session.data.NetworkMonitor
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class MusicStreamApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var networkMonitor: NetworkMonitor
    @Inject @ServerClient lateinit var serverClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        networkMonitor.start()
        PodcastRefreshWorker.schedule(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /** Artwork from the home server needs the authenticated, address-rewriting client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { serverClient })) }
        .crossfade(true)
        .build()
}
