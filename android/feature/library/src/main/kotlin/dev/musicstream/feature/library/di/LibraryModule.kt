package dev.musicstream.feature.library.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.musicstream.core.network.RetrofitFactory
import dev.musicstream.core.network.ServerUrls
import dev.musicstream.core.network.di.ServerClient
import dev.musicstream.feature.library.data.remote.LibraryApi
import dev.musicstream.feature.library.data.repository.LibraryRepositoryImpl
import dev.musicstream.feature.library.domain.repository.LibraryRepository
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryBindings {
    @Binds abstract fun library(impl: LibraryRepositoryImpl): LibraryRepository
}

@Module
@InstallIn(SingletonComponent::class)
object LibraryApiModule {
    @Provides @Singleton
    fun libraryApi(@ServerClient client: OkHttpClient): LibraryApi = RetrofitFactory.create(ServerUrls.BASE_URL, client)
}
