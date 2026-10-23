package dev.musicstream.feature.playlist.presentation.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
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

/** The user's server-side playlists, shown on the Me tab. */
@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val getPlaylists: GetPlaylistsUseCase,
    private val createPlaylist: CreatePlaylistUseCase,
) : ViewModel() {
    private val _playlists = MutableStateFlow<UiState<List<PlaylistSummary>>>(UiState.Loading)
    val playlists: StateFlow<UiState<List<PlaylistSummary>>> = _playlists

    fun load() = viewModelScope.launch { _playlists.value = getPlaylists().toUiState() }

    fun create(name: String) = viewModelScope.launch { createPlaylist(name).onSuccess { load() } }
}

@Composable
fun NewPlaylistDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    NameDialog(stringResource(R.string.new_playlist), stringResource(R.string.playlist_name), "", onConfirm = onConfirm, onDismiss = onDismiss)
}

/** Header with a "new playlist" button followed by one row per playlist. */
fun LazyListScope.playlistsSection(
    state: UiState<List<PlaylistSummary>>,
    onRetry: () -> Unit,
    onCreate: () -> Unit,
    onPlaylist: (Long) -> Unit,
) {
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.playlists), Modifier.weight(1f))
            IconButton(onClick = onCreate) { Icon(Icons.Default.Add, stringResource(R.string.new_playlist)) }
        }
    }
    when (state) {
        UiState.Loading -> item { LoadingState() }
        is UiState.Error -> item { ErrorState(state.error, onRetry) }
        is UiState.Success -> if (state.data.isEmpty()) {
            item { EmptyState(stringResource(R.string.no_playlists)) }
        } else {
            items(state.data, key = { it.id }) { pl ->
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
