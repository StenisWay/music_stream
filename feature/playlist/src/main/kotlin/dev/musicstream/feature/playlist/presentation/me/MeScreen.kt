package dev.musicstream.feature.playlist.presentation.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.NameDialog
import dev.musicstream.core.ui.component.SectionHeader
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.toUiState
import dev.musicstream.feature.library.R as LibraryR
import dev.musicstream.feature.playlist.R
import dev.musicstream.feature.playlist.domain.model.PlaylistSummary
import dev.musicstream.feature.playlist.domain.usecase.CreatePlaylistUseCase
import dev.musicstream.feature.playlist.domain.usecase.GetPlaylistsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeViewModel @Inject constructor(
    private val getPlaylists: GetPlaylistsUseCase,
    private val createPlaylist: CreatePlaylistUseCase,
) : ViewModel() {
    private val _playlists = MutableStateFlow<UiState<List<PlaylistSummary>>>(UiState.Loading)
    val playlists: StateFlow<UiState<List<PlaylistSummary>>> = _playlists

    fun load() = viewModelScope.launch { _playlists.value = getPlaylists().toUiState() }

    fun create(name: String) = viewModelScope.launch { createPlaylist(name).onSuccess { load() } }
}

@Composable
fun MeScreen(
    onFavorites: () -> Unit,
    onPlaylist: (Long) -> Unit,
    onSettings: () -> Unit,
    vm: MeViewModel = hiltViewModel(),
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.load() } // refresh when returning to the tab

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.me_favorites)) },
                leadingContent = { Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable(onClick = onFavorites),
            )
        }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.me_settings)) },
                leadingContent = { Icon(Icons.Default.Settings, null) },
                modifier = Modifier.clickable(onClick = onSettings),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(R.string.playlists), Modifier.weight(1f))
                IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, stringResource(R.string.new_playlist)) }
            }
        }
        when (val p = playlists) {
            UiState.Loading -> item { LoadingState() }
            is UiState.Error -> item { ErrorState(p.error, vm::load) }
            is UiState.Success -> if (p.data.isEmpty()) {
                item { EmptyState(stringResource(R.string.no_playlists)) }
            } else {
                items(p.data, key = { it.id }) { pl ->
                    ListItem(
                        headlineContent = { Text(pl.name) },
                        supportingContent = { Text(stringResource(LibraryR.string.track_count, pl.trackCount)) },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                        modifier = Modifier.clickable { onPlaylist(pl.id) },
                    )
                }
            }
        }
    }
    if (creating) {
        NameDialog(stringResource(R.string.new_playlist), stringResource(R.string.playlist_name), "", onConfirm = { creating = false; vm.create(it) }, onDismiss = { creating = false })
    }
}

