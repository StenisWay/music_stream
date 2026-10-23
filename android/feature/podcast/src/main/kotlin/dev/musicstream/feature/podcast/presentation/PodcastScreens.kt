package dev.musicstream.feature.podcast.presentation

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.common.AppError
import dev.musicstream.core.ui.R as UiR
import dev.musicstream.core.ui.component.Artwork
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.MenuAction
import dev.musicstream.core.ui.component.OverflowMenu
import dev.musicstream.core.ui.component.SectionHeader
import dev.musicstream.core.ui.error.asAppError
import dev.musicstream.core.ui.error.message
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.asUiState
import dev.musicstream.feature.podcast.R
import dev.musicstream.feature.podcast.domain.model.Podcast
import dev.musicstream.feature.podcast.domain.model.PodcastSearchResult
import dev.musicstream.feature.podcast.domain.usecase.ObserveSubscriptionsUseCase
import dev.musicstream.feature.podcast.domain.usecase.SearchPodcastsUseCase
import dev.musicstream.feature.podcast.domain.usecase.SubscribePodcastUseCase
import dev.musicstream.feature.podcast.domain.usecase.UnsubscribePodcastUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class PodcastsViewModel @Inject constructor(
    observeSubscriptions: ObserveSubscriptionsUseCase,
    private val searchPodcasts: SearchPodcastsUseCase,
    private val subscribe: SubscribePodcastUseCase,
    private val unsubscribe: UnsubscribePodcastUseCase,
) : ViewModel() {
    val query = MutableStateFlow("")
    val subscriptions: StateFlow<List<Podcast>?> = observeSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val subscribedFeeds: StateFlow<Set<String>> = observeSubscriptions().map { l -> l.map { it.feedUrl }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val results: StateFlow<UiState<List<PodcastSearchResult>>?> = query.debounce(400).distinctUntilChanged()
        .flatMapLatest { q -> if (q.isBlank()) flowOf(null) else searchPodcasts(q).asUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val busyFeed = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<AppError?>(null)

    fun subscribe(feedUrl: String) {
        subscribe.invoke(feedUrl)
            .onStart { busyFeed.value = feedUrl }
            .onEach { result -> result.onFailure { error.value = it.asAppError() } }
            .onCompletion { busyFeed.value = null }
            .launchIn(viewModelScope)
    }

    fun unsubscribe(feedUrl: String) {
        unsubscribe.invoke(feedUrl)
            .onStart { error.value = null }
            .onEach { result -> result.onFailure { error.value = it.asAppError() } }
            .launchIn(viewModelScope)
    }
}

@Composable
fun PodcastsScreen(onPodcast: (String) -> Unit, onHistory: () -> Unit, vm: PodcastsViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val subscribed by vm.subscribedFeeds.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val busy by vm.busyFeed.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var rssDialog by rememberSaveable { mutableStateOf(false) }
    var confirmUnsubscribe by remember { mutableStateOf<Podcast?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it },
                placeholder = { Text(stringResource(R.string.podcast_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Clear, null) } },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onHistory) { Icon(Icons.Default.History, stringResource(R.string.podcast_history)) }
            IconButton(onClick = { rssDialog = true }) { Icon(Icons.Default.Add, stringResource(R.string.podcast_add_rss)) }
        }
        error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }

        when (val r = results) {
            null -> SubscriptionList(subs, onPodcast, onUnsubscribe = { confirmUnsubscribe = it })
            UiState.Loading -> LoadingState()
            is UiState.Error -> ErrorState(r.error, null)
            is UiState.Success -> LazyColumn {
                items(r.data, key = { it.feedUrl }) { p ->
                    val isSubscribed = p.feedUrl in subscribed
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

    confirmUnsubscribe?.let { p ->
        UnsubscribeDialog(p.title, onConfirm = { confirmUnsubscribe = null; vm.unsubscribe(p.feedUrl) }, onDismiss = { confirmUnsubscribe = null })
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
private fun SubscriptionList(subs: List<Podcast>?, onPodcast: (String) -> Unit, onUnsubscribe: (Podcast) -> Unit) {
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
                    OverflowMenu(listOf(MenuAction(stringResource(R.string.podcast_unsubscribe)) { onUnsubscribe(p) }))
                }
            }
        }
    }
}

/** Unsubscribing deletes the stored episode list (history is kept), so ask first. */
@Composable
internal fun UnsubscribeDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_unsubscribe)) },
        text = { Text(stringResource(R.string.podcast_unsubscribe_confirm, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.podcast_unsubscribe), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}
