package dev.musicstream.feature.playlist.presentation.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.musicstream.feature.playlist.R
import dev.musicstream.feature.playlist.presentation.collections.FavoritesEntry
import dev.musicstream.feature.playlist.presentation.collections.NewPlaylistDialog
import dev.musicstream.feature.playlist.presentation.collections.PlaylistsViewModel
import dev.musicstream.feature.playlist.presentation.collections.playlistsSection

@Composable
fun MeScreen(
    onFavorites: () -> Unit,
    onPlaylist: (Long) -> Unit,
    onSettings: () -> Unit,
    vm: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() } // refresh when returning to the tab

    LazyColumn(Modifier.fillMaxSize()) {
        item { FavoritesEntry(onFavorites) }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.me_settings)) },
                leadingContent = { Icon(Icons.Default.Settings, null) },
                modifier = Modifier.clickable(onClick = onSettings),
            )
        }
        playlistsSection(playlists, onRetry = { vm.load() }, onCreate = { creating = true }, onPlaylist = onPlaylist)
    }
    if (creating) NewPlaylistDialog(onConfirm = { creating = false; vm.create(it) }, onDismiss = { creating = false })
}
