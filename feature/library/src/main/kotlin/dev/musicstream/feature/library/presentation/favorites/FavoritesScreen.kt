package dev.musicstream.feature.library.presentation.favorites

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.ui.component.DetailScaffold
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.PagedList
import dev.musicstream.core.ui.component.PlayButtons
import dev.musicstream.core.ui.paging.pager
import dev.musicstream.feature.library.R
import dev.musicstream.feature.library.domain.model.Track
import dev.musicstream.feature.library.domain.usecase.GetFavoritesPageUseCase
import dev.musicstream.feature.library.presentation.actions.TrackActionsViewModel
import dev.musicstream.feature.library.presentation.actions.trackMenu
import dev.musicstream.feature.library.presentation.component.TrackRow
import dev.musicstream.feature.library.presentation.component.playTracks
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(getFavorites: GetFavoritesPageUseCase) : ViewModel() {
    val tracks: Flow<PagingData<Track>> = pager { offset, limit -> getFavorites(offset, limit) }.cachedIn(viewModelScope)
}

@Composable
fun FavoritesScreen(
    trackActions: TrackActionsViewModel,
    player: PlayerController,
    onBack: () -> Unit,
    vm: FavoritesViewModel = hiltViewModel(),
) {
    val items = vm.tracks.collectAsLazyPagingItems()
    val favorites by trackActions.favorites.collectAsStateWithLifecycle()
    DetailScaffold(stringResource(R.string.favorites), onBack) {
        if (items.itemCount == 0 && items.loadState.append.endOfPaginationReached) {
            EmptyState(stringResource(R.string.no_favorites))
            return@DetailScaffold
        }
        PagedList(items) {
            item {
                PlayButtons(
                    { player.playTracks(items.itemSnapshotList.items, 0) },
                    { player.playTracks(items.itemSnapshotList.items, 0, shuffle = true) },
                )
            }
            items(items.itemCount, key = items.itemKey { it.id }) { i ->
                val t = items[i] ?: return@items
                val fav = favorites[t.id] ?: t.favorite
                TrackRow(t, fav, onClick = { player.playTracks(items.itemSnapshotList.items, i) }, menu = trackMenu(trackActions, t, fav))
            }
        }
    }
}

