package dev.musicstream.feature.playlist.presentation.detail

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.ui.R as UiR
import dev.musicstream.core.ui.component.DetailScaffold
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.MenuAction
import dev.musicstream.core.ui.component.NameDialog
import dev.musicstream.core.ui.component.PlayButtons
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.asUiState
import dev.musicstream.feature.library.presentation.actions.TrackActionsViewModel
import dev.musicstream.feature.library.presentation.actions.trackMenu
import dev.musicstream.feature.library.presentation.component.TrackRow
import dev.musicstream.feature.library.presentation.component.playTracks
import dev.musicstream.feature.playlist.R
import dev.musicstream.feature.playlist.domain.model.Playlist
import dev.musicstream.feature.playlist.domain.usecase.DeletePlaylistUseCase
import dev.musicstream.feature.playlist.domain.usecase.GetPlaylistUseCase
import dev.musicstream.feature.playlist.domain.usecase.RemoveFromPlaylistUseCase
import dev.musicstream.feature.playlist.domain.usecase.RenamePlaylistUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val getPlaylist: GetPlaylistUseCase,
    private val removeFromPlaylist: RemoveFromPlaylistUseCase,
    private val renamePlaylist: RenamePlaylistUseCase,
    private val deletePlaylist: DeletePlaylistUseCase,
) : ViewModel() {
    private val id: Long = checkNotNull(savedState["id"])
    private val _state = MutableStateFlow<UiState<Playlist>>(UiState.Loading)
    val state: StateFlow<UiState<Playlist>> = _state

    init { load() }

    private var loading: Job? = null

    fun load() {
        loading?.cancel()
        loading = getPlaylist(id).asUiState()
            // Keep showing the playlist while refreshing it.
            .onEach { if (it !is UiState.Loading || _state.value !is UiState.Success) _state.value = it }
            .launchIn(viewModelScope)
    }

    fun remove(position: Int) {
        val current = (_state.value as? UiState.Success)?.data ?: return
        removeFromPlaylist(current, position)
            .onEach { result -> result.onSuccess { _state.value = UiState.Success(it) } }
            .launchIn(viewModelScope)
    }

    fun rename(name: String) {
        renamePlaylist(id, name)
            .onEach { result -> result.onSuccess { load() } }
            .launchIn(viewModelScope)
    }

    fun delete(done: () -> Unit) {
        deletePlaylist(id)
            .onEach { result -> result.onSuccess { done() } }
            .launchIn(viewModelScope)
    }
}

@Composable
fun PlaylistScreen(
    id: Long,
    trackActions: TrackActionsViewModel,
    player: PlayerController,
    onBack: () -> Unit,
    vm: PlaylistViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val playlist = (state as? UiState.Success)?.data

    DetailScaffold(playlist?.name.orEmpty(), onBack) {
        when (val s = state) {
            UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(s.error, vm::load)
            is UiState.Success -> LazyColumn(Modifier.fillMaxSize()) {
                item { PlayButtons({ player.playTracks(s.data.tracks, 0) }, { player.playTracks(s.data.tracks, 0, shuffle = true) }) }
                item {
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        OutlinedButton(onClick = { renaming = true }) { Text(stringResource(R.string.rename)) }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { confirmDelete = true }) { Text(stringResource(UiR.string.delete)) }
                    }
                }
                if (s.data.tracks.isEmpty()) item { EmptyState(stringResource(UiR.string.empty_generic)) }
                itemsIndexed(s.data.tracks, key = { i, t -> "$i-${t.id}" }) { i, t ->
                    TrackRow(
                        t,
                        onClick = { player.playTracks(s.data.tracks, i) },
                        menu = trackMenu(trackActions, t) + MenuAction(stringResource(R.string.remove_from_playlist)) { vm.remove(i) },
                    )
                }
            }
        }
    }
    if (renaming && playlist != null) {
        NameDialog(stringResource(R.string.rename), stringResource(R.string.playlist_name), playlist.name, onConfirm = { renaming = false; vm.rename(it) }, onDismiss = { renaming = false })
    }
    if (confirmDelete && playlist != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.delete_playlist_confirm, playlist.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text(stringResource(UiR.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }
}

