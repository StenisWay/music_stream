package dev.musicstream.feature.podcast.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import dev.musicstream.core.ui.component.DetailScaffold
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.MenuAction
import dev.musicstream.feature.podcast.R
import dev.musicstream.feature.podcast.domain.model.HistoryEntry
import dev.musicstream.feature.podcast.domain.usecase.ObserveHistoryUseCase
import dev.musicstream.feature.podcast.domain.usecase.PlayEpisodesUseCase
import dev.musicstream.feature.podcast.domain.usecase.RemoveFromHistoryUseCase
import dev.musicstream.feature.podcast.domain.usecase.SetEpisodeCompletedUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.launchIn
import javax.inject.Inject

enum class HistoryFilter { ALL, UNFINISHED, FINISHED }

@HiltViewModel
class PodcastHistoryViewModel @Inject constructor(
    observeHistory: ObserveHistoryUseCase,
    private val playEpisodes: PlayEpisodesUseCase,
    private val setCompleted: SetEpisodeCompletedUseCase,
    private val removeFromHistory: RemoveFromHistoryUseCase,
) : ViewModel() {
    val filter = MutableStateFlow(HistoryFilter.ALL)

    /** Most recently played first; null while loading. */
    val entries: StateFlow<List<HistoryEntry>?> = combine(observeHistory(), filter) { list, f ->
        when (f) {
            HistoryFilter.ALL -> list
            HistoryFilter.UNFINISHED -> list.filter { !it.episode.completed }
            HistoryFilter.FINISHED -> list.filter { it.episode.completed }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Resumes where it was left (finished ones start over). */
    fun play(entry: HistoryEntry) = playEpisodes(listOf(entry.episode), 0, entry.podcastTitle)

    fun setCompleted(entry: HistoryEntry, completed: Boolean) {
        setCompleted.invoke(entry.episode, entry.podcastTitle, completed).launchIn(viewModelScope)
    }

    fun remove(entry: HistoryEntry) {
        removeFromHistory(entry.episode.id).launchIn(viewModelScope)
    }
}

@Composable
fun PodcastHistoryScreen(onBack: () -> Unit, vm: PodcastHistoryViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()

    DetailScaffold(stringResource(R.string.podcast_history), onBack) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    HistoryFilter.ALL to R.string.podcast_history_filter_all,
                    HistoryFilter.UNFINISHED to R.string.podcast_history_filter_unfinished,
                    HistoryFilter.FINISHED to R.string.podcast_history_filter_finished,
                ).forEach { (f, label) ->
                    FilterChip(selected = filter == f, onClick = { vm.filter.value = f }, label = { Text(stringResource(label)) })
                }
            }
            val list = entries
            when {
                list == null -> LoadingState()
                list.isEmpty() -> EmptyState(stringResource(R.string.podcast_history_empty))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.episode.id }) { entry ->
                        val date = rememberDate(entry.lastPlayedAt)
                        EpisodeRow(
                            entry.episode,
                            overline = listOf(entry.podcastTitle, date).filter { it.isNotBlank() }.joinToString(" · "),
                            onPlay = { vm.play(entry) },
                            onSetCompleted = { vm.setCompleted(entry, it) },
                            extraMenu = listOf(MenuAction(stringResource(R.string.podcast_remove_from_history)) { vm.remove(entry) }),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
