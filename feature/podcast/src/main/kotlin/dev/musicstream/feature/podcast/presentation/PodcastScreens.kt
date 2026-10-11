package dev.musicstream.feature.podcast.presentation

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.common.AppError
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.ui.R as UiR
import dev.musicstream.core.ui.component.Artwork
import dev.musicstream.core.ui.component.DetailScaffold
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.SectionHeader
import dev.musicstream.core.ui.component.formatDuration
import dev.musicstream.core.ui.error.asAppError
import dev.musicstream.core.ui.error.message
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.toUiState
import dev.musicstream.feature.podcast.R
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.Podcast
import dev.musicstream.feature.podcast.domain.model.PodcastSearchResult
import dev.musicstream.feature.podcast.domain.usecase.ObserveEpisodesUseCase
import dev.musicstream.feature.podcast.domain.usecase.ObserveSubscriptionsUseCase
import dev.musicstream.feature.podcast.domain.usecase.RefreshPodcastsUseCase
import dev.musicstream.feature.podcast.domain.usecase.SearchPodcastsUseCase
import dev.musicstream.feature.podcast.domain.usecase.SubscribePodcastUseCase
import dev.musicstream.feature.podcast.domain.usecase.UnsubscribePodcastUseCase
import dev.musicstream.feature.podcast.domain.usecase.toQueueItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class PodcastsViewModel @Inject constructor(
    observeSubscriptions: ObserveSubscriptionsUseCase,
    private val searchPodcasts: SearchPodcastsUseCase,
    private val subscribe: SubscribePodcastUseCase,
) : ViewModel() {
    val query = MutableStateFlow("")
    val subscriptions: StateFlow<List<Podcast>?> = observeSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val subscribedFeeds: StateFlow<Set<String>> = observeSubscriptions().map { l -> l.map { it.feedUrl }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val results: StateFlow<UiState<List<PodcastSearchResult>>?> = query.debounce(400).distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.isBlank()) { emit(null); return@flow }
                emit(UiState.Loading)
                emit(searchPodcasts(q).toUiState())
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val busyFeed = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<AppError?>(null)

    fun subscribe(feedUrl: String) = viewModelScope.launch {
        busyFeed.value = feedUrl
        subscribe.invoke(feedUrl).onFailure { error.value = it.asAppError() }
        busyFeed.value = null
    }
}

@Composable
fun PodcastsScreen(onPodcast: (String) -> Unit, vm: PodcastsViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val subscribed by vm.subscribedFeeds.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val busy by vm.busyFeed.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var rssDialog by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it },
                placeholder = { Text(stringResource(R.string.podcast_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Clear, null) } },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { rssDialog = true }) { Icon(Icons.Default.Add, stringResource(R.string.podcast_add_rss)) }
        }
        error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

        when (val r = results) {
            null -> SubscriptionList(subs, onPodcast)
            UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(r.error, null)
            is UiState.Success -> LazyColumn {
                items(r.data, key = { it.feedUrl }) { p ->
                    val isSubscribed = p.feedUrl in subscribed
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = isSubscribed) { onPodcast(p.feedUrl) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(p.artworkUrl, size = 56.dp, placeholder = Icons.Default.Podcasts)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(p.author, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isSubscribed) {
                            OutlinedButton(onClick = { onPodcast(p.feedUrl) }) { Text(stringResource(R.string.podcast_subscribed)) }
                        } else {
                            FilledTonalButton(onClick = { vm.subscribe(p.feedUrl) }, enabled = busy == null) {
                                Text(if (busy == p.feedUrl) stringResource(UiR.string.loading) else stringResource(R.string.podcast_subscribe))
                            }
                        }
                    }
                }
            }
        }
    }

    if (rssDialog) {
        var url by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rssDialog = false },
            title = { Text(stringResource(R.string.podcast_add_rss)) },
            text = { OutlinedTextField(url, { url = it.trim() }, label = { Text(stringResource(R.string.podcast_rss_url)) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { rssDialog = false; vm.subscribe(url) }, enabled = url.startsWith("http")) { Text(stringResource(R.string.podcast_subscribe)) }
            },
            dismissButton = { TextButton(onClick = { rssDialog = false }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }
}

@Composable
private fun SubscriptionList(subs: List<Podcast>?, onPodcast: (String) -> Unit) {
    when {
        subs == null -> LoadingState()
        subs.isEmpty() -> Column {
            EmptyState(stringResource(R.string.podcast_no_subscriptions) + "\n\n" + stringResource(R.string.podcast_works_offline))
        }
        else -> LazyColumn {
            item { SectionHeader(stringResource(R.string.podcast_subscriptions)) }
            items(subs, key = { it.feedUrl }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPodcast(p.feedUrl) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(p.artworkUrl, size = 56.dp, placeholder = Icons.Default.Podcasts)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(p.author, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    observeSubscriptions: ObserveSubscriptionsUseCase,
    observeEpisodes: ObserveEpisodesUseCase,
    private val refreshPodcasts: RefreshPodcastsUseCase,
    private val unsubscribe: UnsubscribePodcastUseCase,
) : ViewModel() {
    val feedUrl: String = checkNotNull(savedState["feedUrl"])
    val podcast: StateFlow<Podcast?> = observeSubscriptions().map { l -> l.firstOrNull { it.feedUrl == feedUrl } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val episodes: StateFlow<List<Episode>?> = observeEpisodes(feedUrl)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val refreshing = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)

    fun refresh() = viewModelScope.launch {
        refreshing.value = true
        refreshPodcasts(feedUrl).onFailure { error.value = it.asAppError() }
        refreshing.value = false
    }

    fun unsubscribe(done: () -> Unit) = viewModelScope.launch {
        unsubscribe.invoke(feedUrl).onSuccess { done() }
    }
}

@Composable
fun PodcastDetailScreen(
    feedUrl: String,
    player: PlayerController,
    onBack: () -> Unit,
    vm: PodcastDetailViewModel = hiltViewModel(),
) {
    val podcast by vm.podcast.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    DetailScaffold(podcast?.title.orEmpty(), onBack) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            val list = episodes
            when {
                list == null -> LoadingState()
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Artwork(podcast?.artworkUrl, size = 96.dp, placeholder = Icons.Default.Podcasts)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(podcast?.author.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                    Row {
                                        IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, stringResource(R.string.podcast_refresh)) }
                                        TextButton(onClick = { vm.unsubscribe(onBack) }) { Text(stringResource(R.string.podcast_unsubscribe)) }
                                    }
                                }
                            }
                            Text(podcast?.description.orEmpty(), maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    if (list.isEmpty()) item { EmptyState(stringResource(R.string.podcast_no_episodes)) }
                    items(list, key = { it.id }) { e ->
                        EpisodeRow(e, onPlay = {
                            val title = podcast?.title.orEmpty()
                            val index = list.indexOf(e)
                            // Queue this episode and the older ones after it.
                            player.play(list.drop(index).map { it.toQueueItem(title) }, 0)
                        })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, onPlay: () -> Unit) {
    val date = remember(e.publishedAt) {
        if (e.publishedAt > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.publishedAt)) else ""
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(e.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            when {
                e.completed -> {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.podcast_played), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
                }
                e.positionMs > 0 && e.durationMs > 0 -> {
                    LinearProgressIndicator(progress = { e.progress }, modifier = Modifier.width(80.dp))
                    Text(stringResource(R.string.podcast_remaining, formatDuration(e.durationMs - e.positionMs)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
                }
                e.durationMs > 0 -> Text(formatDuration(e.durationMs), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
