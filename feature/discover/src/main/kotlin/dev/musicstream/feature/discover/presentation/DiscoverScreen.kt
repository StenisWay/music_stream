package dev.musicstream.feature.discover.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.common.AppError
import dev.musicstream.core.ui.component.Artwork
import dev.musicstream.core.ui.component.EmptyState
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.component.SectionHeader
import dev.musicstream.core.ui.component.formatDuration
import dev.musicstream.core.ui.error.ErrorTexts
import dev.musicstream.core.ui.error.asAppError
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.toUiState
import dev.musicstream.feature.discover.R
import dev.musicstream.feature.discover.domain.model.DownloadJob
import dev.musicstream.feature.discover.domain.model.DownloadStatus
import dev.musicstream.feature.discover.domain.model.OnlineSong
import dev.musicstream.feature.discover.domain.model.OnlineSource
import dev.musicstream.feature.discover.domain.model.SongInfo
import dev.musicstream.feature.discover.domain.usecase.AddToLibraryUseCase
import dev.musicstream.feature.discover.domain.usecase.GetDownloadsUseCase
import dev.musicstream.feature.discover.domain.usecase.SearchOnlineUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DiscoverEvent {
    data class Queued(val title: String) : DiscoverEvent
    data class Done(val title: String) : DiscoverEvent
    data class DownloadFailed(val title: String) : DiscoverEvent
    data class Failed(val error: AppError) : DiscoverEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val searchOnline: SearchOnlineUseCase,
    private val addToLibrary: AddToLibraryUseCase,
    private val getDownloads: GetDownloadsUseCase,
) : ViewModel() {
    val query = MutableStateFlow("")
    val source = MutableStateFlow(OnlineSource.YOUTUBE)

    /** Online searches are slow, so they run on submit, not per keystroke. */
    private data class Submission(val query: String, val source: OnlineSource, val attempt: Int)
    private val submitted = MutableStateFlow<Submission?>(null)

    /** null = nothing searched yet. */
    val results: StateFlow<UiState<List<OnlineSong>>?> = submitted
        .flatMapLatest { s ->
            flow {
                if (s == null) { emit(null); return@flow }
                emit(UiState.Loading)
                emit(searchOnline(s.query, s.source).toUiState())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    /** Server download jobs, newest first. */
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _events = Channel<DiscoverEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var polling: Job? = null

    init { pollDownloads() }

    fun search() {
        val q = query.value.trim()
        submitted.value = if (q.isEmpty()) null else Submission(q, source.value, (submitted.value?.attempt ?: 0) + 1)
    }

    fun selectSource(s: OnlineSource) {
        source.value = s
        if (submitted.value != null) search()
    }

    fun clear() {
        query.value = ""
        submitted.value = null
    }

    fun add(song: OnlineSong, info: SongInfo) = viewModelScope.launch {
        addToLibrary(song, info)
            .onSuccess { job ->
                _jobs.update { list -> listOf(job) + list.filter { it.id != job.id } }
                _events.send(DiscoverEvent.Queued(job.info.title))
                pollDownloads()
            }
            .onFailure { _events.send(DiscoverEvent.Failed(it.asAppError())) }
    }

    /** Refreshes job states, every 2 s while something is still downloading. */
    private fun pollDownloads() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (true) {
                getDownloads().onSuccess { fresh ->
                    val before = _jobs.value.associateBy { it.id }
                    fresh.filter { before[it.id]?.active == true }.forEach { job ->
                        when (job.status) {
                            DownloadStatus.DONE -> _events.send(DiscoverEvent.Done(job.info.title))
                            DownloadStatus.FAILED -> _events.send(DiscoverEvent.DownloadFailed(job.info.title))
                            else -> Unit
                        }
                    }
                    _jobs.value = fresh
                }
                if (_jobs.value.none { it.active }) break
                delay(2_000)
            }
        }
    }
}

@Composable
fun DiscoverScreen(snackbar: SnackbarHostState, vm: DiscoverViewModel = hiltViewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<OnlineSong?>(null) }
    val focus = LocalFocusManager.current
    // Latest job per song, so result rows show their download state.
    val jobByUrl = remember(jobs) { jobs.distinctBy { it.url }.associateBy { it.url } }

    DiscoverEvents(vm, snackbar)

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it },
            placeholder = { Text(stringResource(R.string.discover_search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Default.Clear, null) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(source == OnlineSource.YOUTUBE, { vm.selectSource(OnlineSource.YOUTUBE) },
                label = { Text(stringResource(R.string.discover_source_youtube)) })
            FilterChip(source == OnlineSource.SOUNDCLOUD, { vm.selectSource(OnlineSource.SOUNDCLOUD) },
                label = { Text(stringResource(R.string.discover_source_soundcloud)) })
        }
        when (val r = results) {
            null -> if (jobs.isEmpty()) {
                EmptyState(stringResource(R.string.discover_intro))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SectionHeader(stringResource(R.string.discover_downloads)) }
                    items(jobs, key = { it.id }) { JobRow(it) }
                }
            }
            UiState.Loading -> LoadingState()
            is UiState.Error -> DiscoverError(r.error, onRetry = vm::search)
            is UiState.Success -> if (r.data.isEmpty()) {
                EmptyState(stringResource(R.string.discover_no_results, query.trim()))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(r.data, key = { it.url }) { song -> SongRow(song, jobByUrl[song.url], onAdd = { adding = song }) }
                }
            }
        }
    }

    adding?.let { song ->
        AddDialog(song, onConfirm = { info -> vm.add(song, info); adding = null }, onDismiss = { adding = null })
    }
}

@Composable
private fun DiscoverEvents(vm: DiscoverViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val errorTexts = ErrorTexts.rememberAll()
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            val text = when (e) {
                is DiscoverEvent.Queued -> context.getString(R.string.discover_queued, e.title)
                is DiscoverEvent.Done -> context.getString(R.string.discover_done, e.title)
                is DiscoverEvent.DownloadFailed -> context.getString(R.string.discover_failed, e.title)
                is DiscoverEvent.Failed -> discoverMessage(e.error)?.let(context::getString) ?: errorTexts(e.error)
            }
            snackbar.showSnackbar(text)
        }
    }
}

/** Server-side reasons specific to online sources (see the discover API). */
private fun discoverMessage(e: AppError): Int? = when ((e as? AppError.Server)?.code) {
    503 -> R.string.discover_unavailable
    502 -> R.string.discover_upstream
    else -> null
}

@Composable
private fun DiscoverError(error: AppError, onRetry: () -> Unit) {
    val specific = discoverMessage(error)
    if (specific == null) {
        ErrorState(error, onRetry)
        return
    }
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(specific), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(stringResource(dev.musicstream.core.ui.R.string.retry)) }
    }
}

@Composable
private fun SongRow(song: OnlineSong, job: DownloadJob?, onAdd: () -> Unit) {
    val busy = job?.active == true
    val done = job?.status == DownloadStatus.DONE
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy && !done, onClick = onAdd).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.thumbnailUrl, size = 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.videoTitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(song.uploader.takeIf { it.isNotBlank() }, song.durationMs.takeIf { it > 0 }?.let(::formatDuration))
                    .joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            busy -> CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            done -> Icon(Icons.Default.CheckCircle, stringResource(R.string.discover_status_done),
                Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.primary)
            job?.status == DownloadStatus.FAILED -> IconButton(onClick = onAdd) {
                Icon(Icons.Default.ErrorOutline, stringResource(R.string.discover_status_failed), tint = MaterialTheme.colorScheme.error)
            }
            else -> IconButton(onClick = onAdd) { Icon(Icons.Default.LibraryAdd, stringResource(R.string.discover_add)) }
        }
    }
}

@Composable
private fun JobRow(job: DownloadJob) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(job.info.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = stringResource(
                when (job.status) {
                    DownloadStatus.QUEUED -> R.string.discover_status_queued
                    DownloadStatus.DOWNLOADING -> R.string.discover_status_downloading
                    DownloadStatus.DONE -> R.string.discover_status_done
                    DownloadStatus.FAILED -> R.string.discover_status_failed
                },
            )
            Text(
                listOfNotNull(job.info.artist.takeIf { it.isNotBlank() }, status, job.error).joinToString(" · "),
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = if (job.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (job.status) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            DownloadStatus.DONE -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            DownloadStatus.FAILED -> Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** Lets the user fix the server's artist/title guess; it decides the library folder. */
@Composable
private fun AddDialog(song: OnlineSong, onConfirm: (SongInfo) -> Unit, onDismiss: () -> Unit) {
    var artist by rememberSaveable(song.url) { mutableStateOf(song.artist) }
    var title by rememberSaveable(song.url) { mutableStateOf(song.title) }
    var album by rememberSaveable(song.url) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discover_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.discover_add_explain), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.discover_artist)) }, singleLine = true)
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.discover_title)) }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.discover_album)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(SongInfo(artist, title, album)) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.discover_download))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.discover_cancel)) } },
    )
}
