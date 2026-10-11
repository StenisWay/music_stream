package dev.musicstream.feature.library.presentation.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.SectionHeader
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.toUiState
import dev.musicstream.feature.library.R
import dev.musicstream.feature.library.domain.model.SearchResults
import dev.musicstream.feature.library.domain.usecase.SearchLibraryUseCase
import dev.musicstream.feature.library.presentation.actions.TrackActionsViewModel
import dev.musicstream.feature.library.presentation.actions.trackMenu
import dev.musicstream.feature.library.presentation.component.AlbumCard
import dev.musicstream.feature.library.presentation.component.ArtistRow
import dev.musicstream.feature.library.presentation.component.TrackRow
import dev.musicstream.feature.library.presentation.component.playTracks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(private val search: SearchLibraryUseCase) : ViewModel() {
    val query = MutableStateFlow("")

    /** null = nothing typed yet. */
    val results: StateFlow<UiState<SearchResults>?> = query
        .debounce(300)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.isBlank()) { emit(null); return@flow }
                emit(UiState.Loading)
                emit(search(q).toUiState())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun SearchScreen(
    trackActions: TrackActionsViewModel,
    player: PlayerController,
    onArtist: (String) -> Unit,
    onAlbum: (String) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val favorites by trackActions.favorites.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Clear, null) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        when (val r = results) {
            null -> Unit
            UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(r.error, onRetry = { vm.query.value = query.trim() + " "; vm.query.value = query })
            is UiState.Success -> if (r.data.isEmpty) {
                EmptyState(stringResource(R.string.search_no_results, query))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (r.data.artists.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.artists)) }
                        items(r.data.artists, key = { "a-" + it.id }) { ArtistRow(it, onArtist) }
                    }
                    if (r.data.albums.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.albums)) }
                        item {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(r.data.albums, key = { it.id }) { a -> Column(Modifier.width(140.dp)) { AlbumCard(a, onAlbum) } }
                            }
                        }
                    }
                    if (r.data.tracks.isNotEmpty()) {
                        item { SectionHeader(stringResource(R.string.songs)) }
                        itemsIndexed(r.data.tracks, key = { _, t -> "t-" + t.id }) { i, t ->
                            val fav = favorites[t.id] ?: t.favorite
                            TrackRow(t, fav, onClick = { player.playTracks(r.data.tracks, i) }, menu = trackMenu(trackActions, t, fav))
                        }
                    }
                }
            }
        }
    }
}

