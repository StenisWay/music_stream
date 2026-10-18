package dev.musicstream.feature.library.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.ui.component.Artwork
import dev.musicstream.core.ui.component.DetailScaffold
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.PagedList
import dev.musicstream.core.ui.component.PlayButtons
import dev.musicstream.core.ui.component.SectionHeader
import dev.musicstream.core.ui.error.asAppError
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.feature.library.R
import dev.musicstream.feature.library.domain.model.Album
import dev.musicstream.feature.library.domain.model.Artist
import dev.musicstream.feature.library.domain.model.TrackSort
import dev.musicstream.feature.library.presentation.actions.TrackActionsViewModel
import dev.musicstream.feature.library.presentation.actions.trackMenu
import dev.musicstream.feature.library.presentation.component.AlbumCard
import dev.musicstream.feature.library.presentation.component.ArtistRow
import dev.musicstream.feature.library.presentation.component.TrackRow
import dev.musicstream.feature.library.presentation.component.ifUnknown
import dev.musicstream.feature.library.presentation.component.playTracks

@Composable
fun LibraryScreen(
    trackActions: TrackActionsViewModel,
    player: PlayerController,
    onArtist: (String) -> Unit,
    onAlbum: (String) -> Unit,
    onMyFavorites: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        MyFavoritesCard(onMyFavorites)
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            listOf(R.string.tab_songs, R.string.tab_artists, R.string.tab_albums).forEachIndexed { i, label ->
                Tab(selected = tab.ordinal == i, onClick = { vm.tab.value = LibraryTab.entries[i] }, text = { Text(stringResource(label)) })
            }
        }
        OutlinedTextField(
            value = filter, onValueChange = { vm.filter.value = it },
            placeholder = { Text(stringResource(R.string.filter_hint)) },
            singleLine = true,
            trailingIcon = { if (filter.isNotEmpty()) IconButton(onClick = { vm.filter.value = "" }) { Icon(Icons.Default.Clear, null) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        when (tab) {
            LibraryTab.SONGS -> SongsTab(vm, trackActions, player)
            LibraryTab.ARTISTS -> ArtistsTab(vm.artists.collectAsLazyPagingItems(), onArtist)
            LibraryTab.ALBUMS -> AlbumsTab(vm.albums.collectAsLazyPagingItems(), onAlbum)
        }
    }
}

/** Entry to the favourites / custom playlists hub, above the library tabs. */
@Composable
private fun MyFavoritesCard(onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.my_favorites), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(stringResource(R.string.my_favorites_hint)) },
            leadingContent = { Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun SongsTab(vm: LibraryViewModel, trackActions: TrackActionsViewModel, player: PlayerController) {
    val sort by vm.sort.collectAsStateWithLifecycle()
    val items = vm.tracks.collectAsLazyPagingItems()
    val favorites by trackActions.favorites.collectAsStateWithLifecycle()
    val current by player.state.collectAsStateWithLifecycle()
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TrackSort.TITLE to R.string.sort_title, TrackSort.ARTIST to R.string.sort_artist, TrackSort.RECENTLY_ADDED to R.string.sort_recent)
            .forEach { (s, label) -> FilterChip(selected = sort == s, onClick = { vm.sort.value = s }, label = { Text(stringResource(label)) }) }
    }
    PagedList(items) {
        items(items.itemCount, key = items.itemKey { it.id }) { i ->
            val track = items[i] ?: return@items
            val fav = favorites[track.id] ?: track.favorite
            TrackRow(
                track = track, favorite = fav,
                isCurrent = current.current?.mediaId == "music:${track.id}",
                onClick = { player.playTracks(items.itemSnapshotList.items, i) },
                menu = trackMenu(trackActions, track, fav),
            )
        }
    }
}

@Composable
private fun ArtistsTab(items: LazyPagingItems<Artist>, onArtist: (String) -> Unit) {
    PagedList(items) {
        items(items.itemCount, key = items.itemKey { it.id }) { i ->
            items[i]?.let { ArtistRow(it, onArtist) }
        }
    }
}

@Composable
private fun AlbumsTab(items: LazyPagingItems<Album>, onAlbum: (String) -> Unit) {
    when (val refresh = items.loadState.refresh) {
        is LoadState.Loading -> if (items.itemCount == 0) { LoadingState(); return }
        is LoadState.Error -> if (items.itemCount == 0) { ErrorState(refresh.error.asAppError(), items::retry); return }
        else -> Unit
    }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items.itemCount, key = items.itemKey { it.id }) { i ->
            items[i]?.let { AlbumCard(it, onAlbum) }
        }
    }
}

@Composable
fun ArtistScreen(
    id: String,
    trackActions: TrackActionsViewModel,
    player: PlayerController,
    onAlbum: (String) -> Unit,
    onBack: () -> Unit,
    vm: ArtistViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tracks = vm.tracks.collectAsLazyPagingItems()
    val favorites by trackActions.favorites.collectAsStateWithLifecycle()
    val title = (state as? UiState.Success)?.data?.artist?.name.orEmpty()
    DetailScaffold(title.ifUnknown(), onBack) {
        when (val s = state) {
            UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(s.error, vm::load)
            is UiState.Success -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    PlayButtons(
                        onPlay = { player.playTracks(tracks.itemSnapshotList.items, 0) },
                        onShuffle = { player.playTracks(tracks.itemSnapshotList.items, 0, shuffle = true) },
                    )
                }
                if (s.data.albums.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.albums)) }
                    item {
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(s.data.albums, key = { it.id }) { a ->
                                Column(Modifier.width(140.dp)) { AlbumCard(a, onAlbum) }
                            }
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.all_songs)) }
                items(tracks.itemCount, key = tracks.itemKey { it.id }) { i ->
                    val track = tracks[i] ?: return@items
                    val fav = favorites[track.id] ?: track.favorite
                    TrackRow(track, fav, onClick = { player.playTracks(tracks.itemSnapshotList.items, i) }, menu = trackMenu(trackActions, track, fav))
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(
    id: String,
    trackActions: TrackActionsViewModel,
    player: PlayerController,
    onBack: () -> Unit,
    vm: AlbumViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val favorites by trackActions.favorites.collectAsStateWithLifecycle()
    val title = (state as? UiState.Success)?.data?.album?.title.orEmpty()
    DetailScaffold(title, onBack) {
        when (val s = state) {
            UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(s.error, vm::load)
            is UiState.Success -> {
                val tracks = s.data.tracks
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Artwork(s.data.album.artworkUrl, size = 200.dp, placeholder = Icons.Default.Album)
                            Text(s.data.album.artist.ifUnknown(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                            Text(stringResource(R.string.track_count, tracks.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    item { PlayButtons({ player.playTracks(tracks, 0) }, { player.playTracks(tracks, 0, shuffle = true) }) }
                    itemsIndexed(tracks, key = { _, t -> t.id }) { i, track ->
                        val fav = favorites[track.id] ?: track.favorite
                        TrackRow(
                            track, fav,
                            onClick = { player.playTracks(tracks, i) },
                            menu = trackMenu(trackActions, track, fav),
                            leading = {
                                Text(
                                    if (track.trackNo > 0) track.trackNo.toString() else "•",
                                    Modifier.width(32.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
