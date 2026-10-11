package dev.musicstream.feature.radio.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.musicstream.core.network.RetrofitFactory
import dev.musicstream.core.network.di.PublicClient
import dev.musicstream.core.playback.api.PlaybackEventListener
import dev.musicstream.feature.radio.data.RadioPlayReporter
import dev.musicstream.feature.radio.data.remote.RadioBrowserApi
import dev.musicstream.feature.radio.data.remote.RadioBrowserFailover
import dev.musicstream.feature.radio.data.repository.RadioRepositoryImpl
import dev.musicstream.feature.radio.domain.repository.RadioRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RadioBindings {
    @Binds abstract fun radio(impl: RadioRepositoryImpl): RadioRepository

    @Binds @IntoSet abstract fun playReporter(impl: RadioPlayReporter): PlaybackEventListener
}

@Module
@InstallIn(SingletonComponent::class)
object RadioApiModule {
    @Provides @Singleton
    fun radioApi(@PublicClient client: OkHttpClient): RadioBrowserApi =
        RetrofitFactory.create(RadioBrowserFailover.PRIMARY, client.newBuilder().addInterceptor(RadioBrowserFailover()).build())
}
