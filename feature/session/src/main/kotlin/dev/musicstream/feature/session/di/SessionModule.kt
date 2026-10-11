package dev.musicstream.feature.session.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.musicstream.core.network.RetrofitFactory
import dev.musicstream.core.network.ServerEndpointResolver
import dev.musicstream.core.network.ServerUrls
import dev.musicstream.core.network.di.AuthClient
import dev.musicstream.core.network.di.ServerClient
import dev.musicstream.core.network.tls.PinProvider
import dev.musicstream.feature.session.data.local.ConnectionStore
import dev.musicstream.feature.session.data.remote.AuthApi
import dev.musicstream.feature.session.data.remote.ServerInfoApi
import dev.musicstream.feature.session.data.repository.AuthRepositoryImpl
import dev.musicstream.feature.session.data.repository.ConnectionRepositoryImpl
import dev.musicstream.feature.session.data.repository.ServerInfoRepositoryImpl
import dev.musicstream.feature.session.domain.repository.AuthRepository
import dev.musicstream.feature.session.domain.repository.ConnectionRepository
import dev.musicstream.feature.session.domain.repository.ServerInfoRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionBindings {
    @Binds abstract fun connection(impl: ConnectionRepositoryImpl): ConnectionRepository
    @Binds abstract fun auth(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun serverInfo(impl: ServerInfoRepositoryImpl): ServerInfoRepository

    // Ports required by core:network.
    @Binds abstract fun endpointResolver(impl: ConnectionRepositoryImpl): ServerEndpointResolver
    @Binds abstract fun pinProvider(impl: ConnectionStore): PinProvider
}

@Module
@InstallIn(SingletonComponent::class)
object SessionApiModule {
    @Provides @Singleton
    fun authApi(@AuthClient client: OkHttpClient): AuthApi = RetrofitFactory.create(ServerUrls.BASE_URL, client)

    @Provides @Singleton
    fun serverInfoApi(@ServerClient client: OkHttpClient): ServerInfoApi = RetrofitFactory.create(ServerUrls.BASE_URL, client)
}
