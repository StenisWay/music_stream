package dev.musicstream.feature.library.presentation.actions

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.common.AppError
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.ui.component.MenuAction
import dev.musicstream.core.ui.error.ErrorTexts
import dev.musicstream.core.ui.error.asAppError
import dev.musicstream.feature.library.R
import dev.musicstream.feature.library.domain.model.Track
import dev.musicstream.feature.library.domain.repository.LibraryRepository
import dev.musicstream.feature.library.domain.usecase.ToggleFavoriteUseCase
import dev.musicstream.feature.library.domain.usecase.toQueueItem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-off feedback shown as a snackbar. */
sealed interface TrackEvent {
    data object AddedToQueue : TrackEvent
    data class Failed(val error: AppError) : TrackEvent
}

/**
 * Library actions available on any track row (favorite, queue). Scoped to
 * the main screen so every list shares the favourite overrides. Other
 * features add their own entries through [LocalTrackMenuExtensions].
 */
@HiltViewModel
class TrackActionsViewModel @Inject constructor(
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val player: PlayerController,
    library: LibraryRepository,
) : ViewModel() {

    /** Favourite flags changed during this session (lists may hold stale copies). */
    private val _favorites = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val favorites: StateFlow<Map<Long, Boolean>> = _favorites

    private val _events = Channel<TrackEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            library.favoriteChanges.collect { (id, fav) -> _favorites.update { it + (id to fav) } }
        }
    }

    fun isFavorite(track: Track): Boolean = _favorites.value[track.id] ?: track.favorite

    fun toggleFavorite(track: Track) = viewModelScope.launch {
        toggleFavoriteUseCase(track.copy(favorite = isFavorite(track)))
            .onFailure { _events.send(TrackEvent.Failed(it.asAppError())) }
    }

    fun enqueue(track: Track) {
        player.enqueue(listOf(track.toQueueItem()))
        _events.trySend(TrackEvent.AddedToQueue)
    }
}

/**
 * Extra overflow-menu entries contributed by other features (e.g. "add to
 * playlist"). Provided by the app shell so this feature does not depend on them.
 */
val LocalTrackMenuExtensions = staticCompositionLocalOf<@Composable (Track) -> List<MenuAction>> { { emptyList() } }

/** Builds the overflow menu for a track. */
@Composable
fun trackMenu(vm: TrackActionsViewModel, track: Track, favorite: Boolean): List<MenuAction> =
    listOf(MenuAction(stringResource(if (favorite) R.string.action_unfavorite else R.string.action_favorite)) { vm.toggleFavorite(track) }) +
        LocalTrackMenuExtensions.current(track) +
        MenuAction(stringResource(R.string.action_play_next)) { vm.enqueue(track) }

/** Turns track events into snackbars. */
@Composable
fun TrackActionsHost(vm: TrackActionsViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val errorTexts = ErrorTexts.rememberAll()

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            val text = when (e) {
                TrackEvent.AddedToQueue -> context.getString(R.string.added_to_queue)
                is TrackEvent.Failed -> errorTexts(e.error)
            }
            snackbar.showSnackbar(text)
        }
    }
}
