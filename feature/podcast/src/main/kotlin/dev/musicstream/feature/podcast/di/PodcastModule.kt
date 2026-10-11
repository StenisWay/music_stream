package dev.musicstream.feature.podcast.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.musicstream.core.network.RetrofitFactory
import dev.musicstream.core.network.di.PublicClient
import dev.musicstream.core.playback.api.PlaybackEventListener
import dev.musicstream.feature.podcast.data.EpisodeProgressRecorder
import dev.musicstream.feature.podcast.data.remote.ITunesApi
import dev.musicstream.feature.podcast.data.repository.PodcastRepositoryImpl
import dev.musicstream.feature.podcast.domain.repository.PodcastRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PodcastBindings {
    @Binds abstract fun podcasts(impl: PodcastRepositoryImpl): PodcastRepository

    @Binds @IntoSet abstract fun progressRecorder(impl: EpisodeProgressRecorder): PlaybackEventListener
}

@Module
@InstallIn(SingletonComponent::class)
object PodcastApiModule {
    @Provides @Singleton
    fun itunesApi(@PublicClient client: OkHttpClient): ITunesApi = RetrofitFactory.create("https://itunes.apple.com/", client)
}
