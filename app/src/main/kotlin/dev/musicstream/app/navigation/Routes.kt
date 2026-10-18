package dev.musicstream.app.navigation

import kotlinx.serialization.Serializable

// Type-safe Navigation Compose destinations.

@Serializable data object LibraryRoute
@Serializable data object SearchRoute
@Serializable data object PodcastsRoute
@Serializable data object RadioRoute
@Serializable data object MeRoute

@Serializable data class ArtistRoute(val id: String)
@Serializable data class AlbumRoute(val id: String)
@Serializable data class PlaylistRoute(val id: Long)
@Serializable data class PodcastRoute(val feedUrl: String)
@Serializable data object PodcastHistoryRoute
@Serializable data object CollectionsRoute
@Serializable data object FavoritesRoute
@Serializable data object SettingsRoute
