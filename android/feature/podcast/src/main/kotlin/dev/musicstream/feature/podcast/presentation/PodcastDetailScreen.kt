package dev.musicstream.feature.podcast.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.common.AppError
import dev.musicstream.core.common.Page
import dev.musicstream.core.ui.R as UiR
import dev.musicstream.core.ui.component.Artwork
import dev.musicstream.core.ui.component.DetailScaffold
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.error.asAppError
import dev.musicstream.core.ui.error.message
import dev.musicstream.core.ui.paging.pager
import dev.musicstream.core.ui.paging.pagerOf
import dev.musicstream.feature.podcast.R
import dev.musicstream.feature.podcast.domain.model.Episode
import dev.musicstream.feature.podcast.domain.model.EpisodeProgress
import dev.musicstream.feature.podcast.domain.model.Podcast
import dev.musicstream.feature.podcast.domain.usecase.FetchFeedPageUseCase
import dev.musicstream.feature.podcast.domain.usecase.GetEpisodePageUseCase
import dev.musicstream.feature.podcast.domain.usecase.ObserveEpisodeProgressUseCase
import dev.musicstream.feature.podcast.domain.usecase.ObserveSubscriptionsUseCase
import dev.musicstream.feature.podcast.domain.usecase.PlayEpisodesUseCase
import dev.musicstream.feature.podcast.domain.usecase.RefreshPodcastsUseCase
import dev.musicstream.feature.podcast.domain.usecase.SetEpisodeCompletedUseCase
import dev.musicstream.feature.podcast.domain.usecase.SubscribePodcastUseCase
import dev.musicstream.feature.podcast.domain.usecase.UnsubscribePodcastUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Header of the detail page: a subscribed feed from Room, or the first page of a live preview. */
data class PodcastHeader(val podcast: Podcast, val subscribed: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PodcastDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    observeSubscriptions: ObserveSubscriptionsUseCase,
    observeProgress: ObserveEpisodeProgressUseCase,
    private val getEpisodePage: GetEpisodePageUseCase,
    private val fetchFeedPage: FetchFeedPageUseCase,
    private val playEpisodes: PlayEpisodesUseCase,
    private val setCompleted: SetEpisodeCompletedUseCase,
    private val refreshPodcasts: RefreshPodcastsUseCase,
    private val subscribe: SubscribePodcastUseCase,
    private val unsubscribe: UnsubscribePodcastUseCase,
) : ViewModel() {
    val feedUrl: String = checkNotNull(savedState["feedUrl"])
    private val subscription = observeSubscriptions().map { l -> l.firstOrNull { it.feedUrl == feedUrl } }

    /** Filled by the preview pager's first page while not subscribed. */
    private val previewPodcast = MutableStateFlow<Podcast?>(null)

    val header: StateFlow<PodcastHeader?> = combine(subscription, previewPodcast) { sub, preview ->
        when {
            sub != null -> PodcastHeader(sub, subscribed = true)
            preview != null -> PodcastHeader(preview, subscribed = false)
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Bumped after a refresh or subscribe so the list reloads. */
    private val reload = MutableStateFlow(0)

    val episodes: Flow<PagingData<Episode>> =
        combine(subscription.map { it != null }.distinctUntilChanged(), reload) { subscribed, _ -> subscribed }
            .flatMapLatest { subscribed -> if (subscribed) pagerOf { o, l -> getEpisodePage(feedUrl, o, l) } else previewPager() }
            .cachedIn(viewModelScope)

    val progress: StateFlow<Map<String, EpisodeProgress>> = observeProgress(feedUrl)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val refreshing = MutableStateFlow(false)
    val subscribing = MutableStateFlow(false)
    val error = MutableStateFlow<AppError?>(null)

    /**
     * Pages an unsubscribed feed straight from RSS: the first page, then
     * older RSS pages (RFC 5005) as the user scrolls. Nothing is stored.
     */
    private fun previewPager(): Flow<PagingData<Episode>> {
        val lock = Mutex()
        val loaded = mutableListOf<Episode>()
        var started = false
        var next: String? = null
        return pager { offset, limit ->
            lock.withLock {
                if (!started) {
                    val first = fetchFeedPage(feedUrl).first().getOrThrow()
                    previewPodcast.value = first.podcast
                    loaded += first.episodes
                    next = first.nextPageUrl
                    started = true
                }
                while (loaded.size < offset + limit) {
                    val url = next ?: break
                    val page = fetchFeedPage(feedUrl, url).first().getOrThrow()
                    loaded += page.episodes.filter { e -> loaded.none { it.id == e.id } }
                    next = page.nextPageUrl?.takeIf { it != url }
                }
                Page(loaded.drop(offset).take(limit), total = loaded.size + if (next != null) 1 else 0, offset = offset)
            }
        }
    }

    /** Refetches a subscribed feed (a preview only reloads), then reloads the list. */
    fun refresh() {
        val fetch = if (header.value?.subscribed == true) {
            refreshPodcasts(feedUrl)
                .onStart { refreshing.value = true }
                .onEach { result -> result.onFailure { error.value = it.asAppError() } }
        } else {
            flowOf(Result.success(Unit))
        }
        fetch
            .onCompletion {
                refreshing.value = false
                reload.value++
            }
            .launchIn(viewModelScope)
    }

    fun play(loaded: List<Episode>, index: Int) {
        val title = header.value?.podcast?.title.orEmpty()
        val p = progress.value
        playEpisodes(loaded.map { it.withProgress(p[it.id]) }, index, title)
    }

    fun setCompleted(episode: Episode, completed: Boolean) {
        setCompleted.invoke(episode, header.value?.podcast?.title.orEmpty(), completed)
            .onEach { result -> result.onFailure { error.value = it.asAppError() } }
            .launchIn(viewModelScope)
    }

    fun subscribe() {
        subscribe.invoke(feedUrl)
            .onStart {
                error.value = null
                subscribing.value = true
            }
            .onEach { result -> result.onFailure { error.value = it.asAppError() } }
            .onCompletion { subscribing.value = false }
            .launchIn(viewModelScope)
    }

    fun unsubscribe(done: () -> Unit) {
        unsubscribe.invoke(feedUrl)
            .onStart { error.value = null }
            .onEach { result ->
                result
                    .onSuccess { done() }
                    .onFailure { error.value = it.asAppError() }
            }
            .launchIn(viewModelScope)
    }
}

@Composable
fun PodcastDetailScreen(
    feedUrl: String,
    onBack: () -> Unit,
    vm: PodcastDetailViewModel = hiltViewModel(),
) {
    val header by vm.header.collectAsStateWithLifecycle()
    val items = vm.episodes.collectAsLazyPagingItems()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val subscribing by vm.subscribing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var confirmUnsubscribe by rememberSaveable { mutableStateOf(false) }

    DetailScaffold(header?.podcast?.title.orEmpty(), onBack) {
        val refresh = items.loadState.refresh
        when {
            items.itemCount == 0 && refresh is LoadState.Error -> ErrorState(refresh.error.asAppError(), items::retry)
            header == null -> LoadingState()
            else -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
                val h = header!!
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Header(
                            h, subscribing, error,
                            onRefresh = vm::refresh, onSubscribe = vm::subscribe, onUnsubscribe = { confirmUnsubscribe = true },
                        )
                    }
                    if (items.itemCount == 0) {
                        item { if (refresh is LoadState.Loading) LoadingState() else EmptyState(stringResource(R.string.podcast_no_episodes)) }
                    }
                    items(items.itemCount, key = items.itemKey { it.id }) { i ->
                        val raw = items[i] ?: return@items
                        val e = raw.withProgress(progress[raw.id])
                        EpisodeRow(
                            e,
                            overline = rememberDate(e.publishedAt),
                            onPlay = { vm.play(items.itemSnapshotList.items, i) },
                            onSetCompleted = { vm.setCompleted(e, it) },
                        )
                        HorizontalDivider()
                    }
                    when (val append = items.loadState.append) {
                        is LoadState.Loading -> item { LoadingState() }
                        is LoadState.Error -> item {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(append.error.asAppError().message(), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = items::retry) { Text(stringResource(UiR.string.retry)) }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
    if (confirmUnsubscribe) {
        UnsubscribeDialog(
            header?.podcast?.title.orEmpty(),
            onConfirm = { confirmUnsubscribe = false; vm.unsubscribe(onBack) },
            onDismiss = { confirmUnsubscribe = false },
        )
    }
}

@Composable
private fun Header(
    h: PodcastHeader,
    subscribing: Boolean,
    error: AppError?,
    onRefresh: () -> Unit,
    onSubscribe: () -> Unit,
    onUnsubscribe: () -> Unit,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(h.podcast.artworkUrl, size = 96.dp, placeholder = Icons.Default.Podcasts)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(h.podcast.author, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (h.subscribed) {
                        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, stringResource(R.string.podcast_refresh)) }
                        OutlinedButton(onClick = onUnsubscribe) { Text(stringResource(R.string.podcast_unsubscribe)) }
                    } else {
                        FilledTonalButton(onClick = onSubscribe, enabled = !subscribing) {
                            Text(stringResource(if (subscribing) UiR.string.loading else R.string.podcast_subscribe))
                        }
                    }
                }
            }
        }
        // Not subscribed yet: show the whole description so the user can decide.
        Text(
            h.podcast.description,
            maxLines = if (h.subscribed) 4 else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
        )
        error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
    }
}
