package dev.musicstream.feature.radio.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.ui.component.Artwork
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.SectionHeader
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.asUiState
import dev.musicstream.feature.radio.R
import dev.musicstream.feature.radio.domain.model.RadioStation
import dev.musicstream.feature.radio.domain.usecase.ObserveFavoriteStationsUseCase
import dev.musicstream.feature.radio.domain.usecase.SearchStationsUseCase
import dev.musicstream.feature.radio.domain.usecase.ToggleFavoriteStationUseCase
import dev.musicstream.feature.radio.domain.usecase.toQueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class RadioViewModel @Inject constructor(
    private val searchStations: SearchStationsUseCase,
    private val toggleFavorite: ToggleFavoriteStationUseCase,
    observeFavorites: ObserveFavoriteStationsUseCase,
) : ViewModel() {
    val query = MutableStateFlow("")
    /** "TW" = Taiwan, null = worldwide. */
    val country = MutableStateFlow<String?>("TW")
    private val retry = MutableStateFlow(0)

    val favorites: StateFlow<List<RadioStation>> = observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stations: StateFlow<UiState<List<RadioStation>>> =
        combine(query.debounce(400), country, retry) { q, c, _ -> q to c }
            .flatMapLatest { (q, c) -> searchStations(q, c).asUiState() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun retry() { retry.value++ }

    fun toggle(station: RadioStation, isFavorite: Boolean) {
        toggleFavorite(station.copy(favorite = isFavorite)).launchIn(viewModelScope)
    }
}

@Composable
fun RadioScreen(player: PlayerController, vm: RadioViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val country by vm.country.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val stations by vm.stations.collectAsStateWithLifecycle()
    val favIds = favorites.map { it.id }.toSet()
    val play = { s: RadioStation -> player.play(listOf(s.toQueueItem()), 0) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it },
            placeholder = { Text(stringResource(R.string.radio_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Clear, null) } },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(country == "TW", { vm.country.value = "TW" }, label = { Text(stringResource(R.string.radio_country_tw)) })
            FilterChip(country == null, { vm.country.value = null }, label = { Text(stringResource(R.string.radio_country_all)) })
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (favorites.isNotEmpty() && query.isBlank()) {
                item { SectionHeader(stringResource(R.string.radio_favorites)) }
                items(favorites, key = { "f-" + it.id }) { s -> StationRow(s, true, { play(s) }, { vm.toggle(s, true) }) }
            }
            item { SectionHeader(stringResource(R.string.radio_popular)) }
            when (val st = stations) {
                UiState.Loading -> item { LoadingState() }
                is UiState.Error -> item { ErrorState(st.error, vm::retry) }
                is UiState.Success -> items(st.data, key = { it.id }) { s ->
                    val fav = s.id in favIds
                    StationRow(s, fav, { play(s) }, { vm.toggle(s, fav) })
                }
            }
        }
    }
}

@Composable
private fun StationRow(s: RadioStation, favorite: Boolean, onPlay: () -> Unit, onToggleFavorite: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(s.faviconUrl, placeholder = Icons.Default.Radio)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(s.codec.takeIf { it.isNotBlank() }, s.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" }, s.tags.take(2).joinToString(", ").takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
