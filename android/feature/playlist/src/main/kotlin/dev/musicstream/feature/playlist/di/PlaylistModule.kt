package dev.musicstream.feature.playlist.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.musicstream.core.network.RetrofitFactory
import dev.musicstream.core.network.ServerUrls
import dev.musicstream.core.network.di.ServerClient
import dev.musicstream.feature.playlist.data.remote.PlaylistApi
import dev.musicstream.feature.playlist.data.repository.PlaylistRepositoryImpl
import dev.musicstream.feature.playlist.domain.repository.PlaylistRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaylistBindings {
    @Binds abstract fun playlists(impl: PlaylistRepositoryImpl): PlaylistRepository
}

@Module
@InstallIn(SingletonComponent::class)
object PlaylistApiModule {
    @Provides @Singleton
    fun playlistApi(@ServerClient client: OkHttpClient): PlaylistApi = RetrofitFactory.create(ServerUrls.BASE_URL, client)
}
