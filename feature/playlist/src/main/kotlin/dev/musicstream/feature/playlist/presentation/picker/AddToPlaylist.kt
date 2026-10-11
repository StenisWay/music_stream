package dev.musicstream.feature.playlist.presentation.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.common.AppError
import dev.musicstream.core.ui.R as UiR
import dev.musicstream.core.ui.component.MenuAction
import dev.musicstream.core.ui.component.NameDialog
import dev.musicstream.core.ui.error.ErrorTexts
import dev.musicstream.core.ui.error.asAppError
import dev.musicstream.feature.library.domain.model.Track
import dev.musicstream.feature.playlist.R
import dev.musicstream.feature.playlist.domain.model.PlaylistSummary
import dev.musicstream.feature.playlist.domain.usecase.AddToPlaylistUseCase
import dev.musicstream.feature.playlist.domain.usecase.CreatePlaylistUseCase
import dev.musicstream.feature.playlist.domain.usecase.GetPlaylistsUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-off feedback shown as a snackbar. */
sealed interface AddToPlaylistEvent {
    data class Added(val name: String) : AddToPlaylistEvent
    data class Failed(val error: AppError) : AddToPlaylistEvent
}

/** "Add to playlist" for any track row: picks or creates a playlist. */
@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val getPlaylists: GetPlaylistsUseCase,
    private val addToPlaylist: AddToPlaylistUseCase,
    private val createPlaylist: CreatePlaylistUseCase,
) : ViewModel() {
    private val _pickerFor = MutableStateFlow<Track?>(null)
    val pickerFor: StateFlow<Track?> = _pickerFor
    private val _playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())
    val playlists: StateFlow<List<PlaylistSummary>> = _playlists

    private val _events = Channel<AddToPlaylistEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun openPicker(track: Track) = viewModelScope.launch {
        getPlaylists().onSuccess { _playlists.value = it }
        _pickerFor.value = track
    }

    fun dismissPicker() { _pickerFor.value = null }

    fun addTo(playlist: PlaylistSummary) = viewModelScope.launch {
        val track = _pickerFor.value ?: return@launch
        _pickerFor.value = null
        addToPlaylist(playlist.id, listOf(track.id))
            .onSuccess { _events.send(AddToPlaylistEvent.Added(playlist.name)) }
            .onFailure { _events.send(AddToPlaylistEvent.Failed(it.asAppError())) }
    }

    fun createAndAdd(name: String) = viewModelScope.launch {
        val track = _pickerFor.value ?: return@launch
        _pickerFor.value = null
        createPlaylist(name, listOf(track.id))
            .onSuccess { _events.send(AddToPlaylistEvent.Added(it.name)) }
            .onFailure { _events.send(AddToPlaylistEvent.Failed(it.asAppError())) }
    }
}

/** Menu entry contributed to library track rows (see LocalTrackMenuExtensions). */
@Composable
fun addToPlaylistMenu(vm: AddToPlaylistViewModel, track: Track): List<MenuAction> =
    listOf(MenuAction(stringResource(R.string.action_add_to_playlist)) { vm.openPicker(track) })

/** Hosts the playlist picker dialog and turns events into snackbars. */
@Composable
fun AddToPlaylistHost(vm: AddToPlaylistViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val picker by vm.pickerFor.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val errorTexts = ErrorTexts.rememberAll()

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            val text = when (e) {
                is AddToPlaylistEvent.Added -> context.getString(R.string.added_to_playlist, e.name)
                is AddToPlaylistEvent.Failed -> errorTexts(e.error)
            }
            snackbar.showSnackbar(text)
        }
    }

    if (picker != null && !creating) {
        AlertDialog(
            onDismissRequest = vm::dismissPicker,
            title = { Text(stringResource(R.string.choose_playlist)) },
            text = {
                LazyColumn {
                    item {
                        Text(
                            stringResource(R.string.new_playlist),
                            Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                        )
                    }
                    items(playlists, key = { it.id }) { p ->
                        Text(p.name, Modifier.fillMaxWidth().clickable { vm.addTo(p) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissPicker) { Text(stringResource(UiR.string.cancel)) } },
        )
    }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.new_playlist),
            label = stringResource(R.string.playlist_name),
            initial = "",
            onConfirm = { creating = false; vm.createAndAdd(it) },
            onDismiss = { creating = false; vm.dismissPicker() },
        )
    }
}
