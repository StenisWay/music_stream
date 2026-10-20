package dev.musicstream.feature.discover.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.musicstream.core.network.RetrofitFactory
import dev.musicstream.core.network.ServerUrls
import dev.musicstream.core.network.di.ServerClient
import dev.musicstream.feature.discover.data.remote.DiscoverApi
import dev.musicstream.feature.discover.data.repository.DiscoverRepositoryImpl
import dev.musicstream.feature.discover.domain.repository.DiscoverRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoverBindings {
    @Binds abstract fun discover(impl: DiscoverRepositoryImpl): DiscoverRepository
}

@Module
@InstallIn(SingletonComponent::class)
object DiscoverApiModule {
    /** Online searches run yt-dlp on the server and can take a while. */
    @Provides @Singleton
    fun discoverApi(@ServerClient client: OkHttpClient): DiscoverApi =
        RetrofitFactory.create(ServerUrls.BASE_URL, client.newBuilder().readTimeout(45, TimeUnit.SECONDS).build())
}
