package dev.musicstream.feature.library.presentation.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.ui.paging.pager
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.toUiState
import dev.musicstream.feature.library.domain.model.Album
import dev.musicstream.feature.library.domain.model.AlbumDetail
import dev.musicstream.feature.library.domain.model.Artist
import dev.musicstream.feature.library.domain.model.ArtistDetail
import dev.musicstream.feature.library.domain.model.Track
import dev.musicstream.feature.library.domain.model.TrackQuery
import dev.musicstream.feature.library.domain.model.TrackSort
import dev.musicstream.feature.library.domain.usecase.GetAlbumDetailUseCase
import dev.musicstream.feature.library.domain.usecase.GetAlbumsPageUseCase
import dev.musicstream.feature.library.domain.usecase.GetArtistDetailUseCase
import dev.musicstream.feature.library.domain.usecase.GetArtistsPageUseCase
import dev.musicstream.feature.library.domain.usecase.GetTracksPageUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { SONGS, ARTISTS, ALBUMS }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val getTracks: GetTracksPageUseCase,
    private val getArtists: GetArtistsPageUseCase,
    private val getAlbums: GetAlbumsPageUseCase,
) : ViewModel() {
    val tab = MutableStateFlow(LibraryTab.SONGS)
    val sort = MutableStateFlow(TrackSort.TITLE)
    val filter = MutableStateFlow("")
    private val debouncedFilter = filter.debounce(300).distinctUntilChanged()

    val tracks: Flow<PagingData<Track>> = combine(debouncedFilter, sort) { f, s -> f to s }
        .flatMapLatest { (f, s) -> pager { offset, limit -> getTracks(TrackQuery(search = f, sort = s), offset, limit) } }
        .cachedIn(viewModelScope)

    val artists: Flow<PagingData<Artist>> = debouncedFilter
        .flatMapLatest { f -> pager(100) { offset, limit -> getArtists(f, offset, limit) } }
        .cachedIn(viewModelScope)

    val albums: Flow<PagingData<Album>> = debouncedFilter
        .flatMapLatest { f -> pager(60) { offset, limit -> getAlbums(f, offset, limit) } }
        .cachedIn(viewModelScope)
}

@HiltViewModel
class ArtistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val getArtist: GetArtistDetailUseCase,
    private val getTracks: GetTracksPageUseCase,
) : ViewModel() {
    private val id: String = checkNotNull(savedState["id"])
    private val _state = MutableStateFlow<UiState<ArtistDetail>>(UiState.Loading)
    val state: StateFlow<UiState<ArtistDetail>> = _state

    val tracks: Flow<PagingData<Track>> =
        pager { offset, limit -> getTracks(TrackQuery(artistId = id, sort = TrackSort.ALBUM), offset, limit) }.cachedIn(viewModelScope)

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        _state.value = getArtist(id).toUiState()
    }
}

@HiltViewModel
class AlbumViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val getAlbum: GetAlbumDetailUseCase,
) : ViewModel() {
    private val id: String = checkNotNull(savedState["id"])
    private val _state = MutableStateFlow<UiState<AlbumDetail>>(UiState.Loading)
    val state: StateFlow<UiState<AlbumDetail>> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        _state.value = getAlbum(id).toUiState()
    }
}
