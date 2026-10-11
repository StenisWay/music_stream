package dev.musicstream.core.network.di

import javax.inject.Qualifier

/** Client for the home server and media: URL rewrite + bearer auth + pinned TLS. Also used by ExoPlayer and Coil. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ServerClient

/** Like [ServerClient] but without auth handling; used for login/refresh. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class AuthClient

/** Bare pinned client with short timeouts used to probe candidate addresses. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ProbeClient

/** Plain client for public services (RSS, iTunes, Radio Browser). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PublicClient
