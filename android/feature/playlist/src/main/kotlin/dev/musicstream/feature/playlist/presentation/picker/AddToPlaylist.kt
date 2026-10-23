package dev.musicstream.feature.playlist.presentation.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.common.AppError
import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.QueueItem
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.MenuAction
import dev.musicstream.core.ui.component.NameDialog
import dev.musicstream.core.ui.error.ErrorTexts
import dev.musicstream.core.ui.error.asAppError
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.asUiState
import dev.musicstream.feature.library.R as LibraryR
import dev.musicstream.feature.library.domain.model.Track
import dev.musicstream.feature.playlist.R
import dev.musicstream.feature.playlist.domain.model.PlaylistChoice
import dev.musicstream.feature.playlist.domain.usecase.AddToPlaylistUseCase
import dev.musicstream.feature.playlist.domain.usecase.CreatePlaylistUseCase
import dev.musicstream.feature.playlist.domain.usecase.GetPlaylistChoicesUseCase
import dev.musicstream.feature.playlist.domain.usecase.RemoveTrackFromPlaylistUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** One-off feedback shown as a snackbar. */
sealed interface AddToPlaylistEvent {
    data class Added(val name: String) : AddToPlaylistEvent
    data class Failed(val error: AppError) : AddToPlaylistEvent
}

/** The track the picker is open for. */
data class PickerTarget(val trackId: Long, val title: String)

/**
 * "Add to playlist" for any track: a sheet listing every playlist with a
 * checkbox. Ticking adds the track on the server, unticking removes it.
 */
@HiltViewModel
class AddToPlaylistViewModel @Inject constructor(
    private val getChoices: GetPlaylistChoicesUseCase,
    private val addToPlaylist: AddToPlaylistUseCase,
    private val removeFromPlaylist: RemoveTrackFromPlaylistUseCase,
    private val createPlaylist: CreatePlaylistUseCase,
) : ViewModel() {
    private val _pickerFor = MutableStateFlow<PickerTarget?>(null)
    val pickerFor: StateFlow<PickerTarget?> = _pickerFor
    private val _choices = MutableStateFlow<UiState<List<PlaylistChoice>>>(UiState.Loading)
    val choices: StateFlow<UiState<List<PlaylistChoice>>> = _choices

    /** Playlists with a request in flight; their checkbox is disabled. */
    private val _pending = MutableStateFlow<Set<Long>>(emptySet())
    val pending: StateFlow<Set<Long>> = _pending

    private val _events = Channel<AddToPlaylistEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun openPicker(trackId: Long, title: String) {
        _pickerFor.value = PickerTarget(trackId, title)
        load()
    }

    private var loading: Job? = null

    fun load() {
        val target = _pickerFor.value ?: return
        loading?.cancel()
        loading = getChoices(target.trackId).asUiState()
            .onEach { _choices.value = it }
            .launchIn(viewModelScope)
    }

    fun dismissPicker() { _pickerFor.value = null }

    fun toggle(choice: PlaylistChoice) {
        val target = _pickerFor.value ?: return
        val id = choice.playlist.id
        if (id in _pending.value) return
        val adding = !choice.containsTrack
        _pending.update { it + id }
        setContains(id, adding) // optimistic
        val change = if (adding) addToPlaylist(id, listOf(target.trackId)) else removeFromPlaylist(id, target.trackId)
        change
            .onEach { result ->
                result
                    .onSuccess { p -> updateChoice(id) { it.copy(playlist = it.playlist.copy(trackCount = p.tracks.size)) } }
                    .onFailure {
                        setContains(id, !adding)
                        _events.send(AddToPlaylistEvent.Failed(it.asAppError()))
                    }
            }
            .onCompletion { _pending.update { it - id } }
            .launchIn(viewModelScope)
    }

    fun createAndAdd(name: String) {
        val target = _pickerFor.value ?: return
        _pickerFor.value = null
        createPlaylist(name, listOf(target.trackId))
            .onEach { result ->
                result
                    .onSuccess { _events.send(AddToPlaylistEvent.Added(it.name)) }
                    .onFailure { _events.send(AddToPlaylistEvent.Failed(it.asAppError())) }
            }
            .launchIn(viewModelScope)
    }

    private fun setContains(id: Long, contains: Boolean) = updateChoice(id) { it.copy(containsTrack = contains) }

    private fun updateChoice(id: Long, change: (PlaylistChoice) -> PlaylistChoice) {
        val list = (_choices.value as? UiState.Success)?.data ?: return
        _choices.value = UiState.Success(list.map { if (it.playlist.id == id) change(it) else it })
    }
}

/** Menu entry contributed to library track rows (see LocalTrackMenuExtensions). */
@Composable
fun addToPlaylistMenu(vm: AddToPlaylistViewModel, track: Track): List<MenuAction> =
    listOf(MenuAction(stringResource(R.string.action_add_to_playlist)) { vm.openPicker(track.id, track.title) })

/** Menu entry for the now-playing item; only library music can go in a playlist. */
@Composable
fun addToPlaylistMenu(vm: AddToPlaylistViewModel, item: QueueItem): List<MenuAction> {
    val (kind, key) = QueueItem.parse(item.mediaId) ?: return emptyList()
    val trackId = key.toLongOrNull()
    if (kind != MediaKind.MUSIC || trackId == null) return emptyList()
    return listOf(MenuAction(stringResource(R.string.action_add_to_playlist)) { vm.openPicker(trackId, item.title) })
}

/** Hosts the playlist picker sheet and turns events into snackbars. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistHost(vm: AddToPlaylistViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val target by vm.pickerFor.collectAsStateWithLifecycle()
    val choices by vm.choices.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
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

    val current = target
    if (current != null && !creating) {
        ModalBottomSheet(onDismissRequest = vm::dismissPicker, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.navigationBarsPadding()) {
                Text(
                    stringResource(R.string.choose_playlist),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    current.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.new_playlist)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { creating = true },
                )
                HorizontalDivider()
                when (val c = choices) {
                    UiState.Loading -> LoadingState()
                    is UiState.Error -> ErrorState(c.error, vm::load)
                    is UiState.Success -> if (c.data.isEmpty()) {
                        EmptyState(stringResource(R.string.no_playlists))
                    } else {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(c.data, key = { it.playlist.id }) { choice ->
                                val busy = choice.playlist.id in pending
                                ListItem(
                                    headlineContent = { Text(choice.playlist.name) },
                                    supportingContent = { Text(stringResource(LibraryR.string.track_count, choice.playlist.trackCount)) },
                                    trailingContent = { Checkbox(checked = choice.containsTrack, onCheckedChange = null, enabled = !busy) },
                                    modifier = Modifier.clickable(enabled = !busy) { vm.toggle(choice) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.new_playlist),
            label = stringResource(R.string.playlist_name),
            initial = "",
            onConfirm = { creating = false; vm.createAndAdd(it) },
            onDismiss = { creating = false },
        )
    }
}
