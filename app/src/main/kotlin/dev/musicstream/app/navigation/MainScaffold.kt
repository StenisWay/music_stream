package dev.musicstream.app.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.musicstream.app.R
import dev.musicstream.feature.discover.presentation.DiscoverScreen
import dev.musicstream.feature.library.presentation.actions.LocalTrackMenuExtensions
import dev.musicstream.feature.library.presentation.actions.TrackActionsHost
import dev.musicstream.feature.library.presentation.actions.TrackActionsViewModel
import dev.musicstream.feature.library.presentation.favorites.FavoritesScreen
import dev.musicstream.feature.library.presentation.library.AlbumScreen
import dev.musicstream.feature.library.presentation.library.ArtistScreen
import dev.musicstream.feature.library.presentation.library.LibraryScreen
import dev.musicstream.feature.player.MiniPlayer
import dev.musicstream.feature.player.NowPlayingSheet
import dev.musicstream.feature.player.PlaybackErrorEffect
import dev.musicstream.feature.player.PlayerViewModel
import dev.musicstream.feature.playlist.presentation.collections.CollectionsScreen
import dev.musicstream.feature.playlist.presentation.detail.PlaylistScreen
import dev.musicstream.feature.playlist.presentation.me.MeScreen
import dev.musicstream.feature.playlist.presentation.picker.AddToPlaylistHost
import dev.musicstream.feature.playlist.presentation.picker.AddToPlaylistViewModel
import dev.musicstream.feature.playlist.presentation.picker.addToPlaylistMenu
import dev.musicstream.feature.podcast.presentation.PodcastDetailScreen
import dev.musicstream.feature.podcast.presentation.PodcastHistoryScreen
import dev.musicstream.feature.podcast.presentation.PodcastsScreen
import dev.musicstream.feature.radio.presentation.RadioScreen
import dev.musicstream.feature.session.presentation.settings.SettingsScreen
import kotlin.reflect.KClass

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(LibraryRoute, LibraryRoute::class, R.string.nav_library, Icons.Default.LibraryMusic),
    Tab(SearchRoute, SearchRoute::class, R.string.nav_search, Icons.Default.Search),
    Tab(PodcastsRoute, PodcastsRoute::class, R.string.nav_podcasts, Icons.Default.Podcasts),
    Tab(RadioRoute, RadioRoute::class, R.string.nav_radio, Icons.Default.Radio),
    Tab(MeRoute, MeRoute::class, R.string.nav_me, Icons.Default.Person),
)

/** Shared app chrome: bottom navigation, mini player, snackbars and the nav graph. */
@Composable
fun MainScaffold() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val player: PlayerViewModel = hiltViewModel()
    val trackActions: TrackActionsViewModel = hiltViewModel()
    val addToPlaylist: AddToPlaylistViewModel = hiltViewModel()
    val playerState by player.state.collectAsStateWithLifecycle()
    var nowPlayingOpen by rememberSaveable { mutableStateOf(false) }
    // Feature screens only see the playback port, not the player feature.
    val controller = player.controller

    PlaybackErrorEffect(playerState.error, snackbar, onShown = player::dismissError)
    TrackActionsHost(trackActions, snackbar)
    AddToPlaylistHost(addToPlaylist, snackbar)

    // Playlists contribute "add to playlist" to every library track menu.
    CompositionLocalProvider(LocalTrackMenuExtensions provides { track -> addToPlaylistMenu(addToPlaylist, track) }) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    MiniPlayer(playerState, onToggle = player::toggle, onNext = player::next, onOpen = { nowPlayingOpen = true })
                    val backStack by nav.currentBackStackEntryAsState()
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected = backStack?.destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = LibraryRoute, modifier = Modifier.padding(padding)) {
                composable<LibraryRoute> {
                    LibraryScreen(
                        trackActions = trackActions, player = controller,
                        onArtist = { nav.navigate(ArtistRoute(it)) }, onAlbum = { nav.navigate(AlbumRoute(it)) },
                        onMyFavorites = { nav.navigate(CollectionsRoute) },
                    )
                }
                composable<CollectionsRoute> {
                    CollectionsScreen(
                        onFavorites = { nav.navigate(FavoritesRoute) },
                        onPlaylist = { nav.navigate(PlaylistRoute(it)) },
                        onBack = nav::popBackStack,
                    )
                }
                composable<ArtistRoute> {
                    ArtistScreen(
                        id = it.toRoute<ArtistRoute>().id, trackActions = trackActions, player = controller,
                        onAlbum = { id -> nav.navigate(AlbumRoute(id)) }, onBack = nav::popBackStack,
                    )
                }
                composable<AlbumRoute> {
                    AlbumScreen(id = it.toRoute<AlbumRoute>().id, trackActions = trackActions, player = controller, onBack = nav::popBackStack)
                }
                // Search finds songs online and adds them to the library;
                // the library tab filters what is already there.
                composable<SearchRoute> { DiscoverScreen(snackbar) }
                composable<PodcastsRoute> {
                    PodcastsScreen(onPodcast = { nav.navigate(PodcastRoute(it)) }, onHistory = { nav.navigate(PodcastHistoryRoute) })
                }
                composable<PodcastRoute> {
                    PodcastDetailScreen(feedUrl = it.toRoute<PodcastRoute>().feedUrl, onBack = nav::popBackStack)
                }
                composable<PodcastHistoryRoute> { PodcastHistoryScreen(onBack = nav::popBackStack) }
                composable<RadioRoute> { RadioScreen(player = controller) }
                composable<MeRoute> {
                    MeScreen(
                        onFavorites = { nav.navigate(FavoritesRoute) },
                        onPlaylist = { nav.navigate(PlaylistRoute(it)) },
                        onSettings = { nav.navigate(SettingsRoute) },
                    )
                }
                composable<FavoritesRoute> { FavoritesScreen(trackActions, controller, onBack = nav::popBackStack) }
                composable<PlaylistRoute> {
                    PlaylistScreen(id = it.toRoute<PlaylistRoute>().id, trackActions = trackActions, player = controller, onBack = nav::popBackStack)
                }
                composable<SettingsRoute> { SettingsScreen(onBack = nav::popBackStack) }
            }
        }
    }

    if (nowPlayingOpen) {
        NowPlayingSheet(player, playerState, onDismiss = { nowPlayingOpen = false }, itemMenu = { addToPlaylistMenu(addToPlaylist, it) })
    }
}
