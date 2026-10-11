package dev.musicstream.core.network.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.musicstream.core.network.AuthInterceptor
import dev.musicstream.core.network.RetrofitFactory
import dev.musicstream.core.network.ServerUrlInterceptor
import dev.musicstream.core.network.ServerUrls
import dev.musicstream.core.network.TokenAuthenticator
import dev.musicstream.core.network.session.SessionPreferences
import dev.musicstream.core.network.session.TokenRefreshApi
import dev.musicstream.core.network.session.TokenStorage
import dev.musicstream.core.network.session.TokenStore
import dev.musicstream.core.network.session.sessionDataStore
import dev.musicstream.core.network.tls.PinProvider
import dev.musicstream.core.network.tls.ServerHostnameVerifier
import dev.musicstream.core.network.tls.ServerTrustManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.SSLContext

/**
 * Shared HTTP stack. [PinProvider] and [dev.musicstream.core.network.ServerEndpointResolver]
 * are bound by the session feature, which owns the paired server.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TokenModule {
    @Binds abstract fun tokens(impl: TokenStore): TokenStorage
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private const val USER_AGENT = "MusicStream-Android/1.0"

    private val userAgent = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
    }

    @Provides @Singleton @SessionPreferences
    fun sessionPreferences(@ApplicationContext context: Context): DataStore<Preferences> = context.sessionDataStore

    /** Base with pinned TLS shared by the server-facing clients. */
    @Provides @Singleton
    fun pinnedBase(pins: PinProvider): OkHttpClient {
        val trust = ServerTrustManager(pins)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .hostnameVerifier(ServerHostnameVerifier(pins))
            .addInterceptor(userAgent)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides @Singleton @ProbeClient
    fun probeClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Provides @Singleton @AuthClient
    fun authClient(base: OkHttpClient, url: ServerUrlInterceptor): OkHttpClient =
        base.newBuilder().addInterceptor(url).build()

    @Provides @Singleton @ServerClient
    fun serverClient(
        base: OkHttpClient,
        url: ServerUrlInterceptor,
        auth: AuthInterceptor,
        authenticator: TokenAuthenticator,
    ): OkHttpClient = base.newBuilder()
        // Order matters: auth must see the virtual host before it is rewritten.
        .addInterceptor(auth)
        .addInterceptor(url)
        .authenticator(authenticator)
        .build()

    @Provides @Singleton @PublicClient
    fun publicClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(userAgent)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun tokenRefreshApi(@AuthClient client: OkHttpClient): TokenRefreshApi = RetrofitFactory.create(ServerUrls.BASE_URL, client)
}
