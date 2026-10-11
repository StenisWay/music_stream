package dev.musicstream.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.PlayerState
import dev.musicstream.core.playback.api.RepeatMode
import dev.musicstream.core.ui.R as UiR
import dev.musicstream.core.ui.component.Artwork
import dev.musicstream.core.ui.component.formatDuration

/** Compact player shown above the bottom navigation. */
@Composable
fun MiniPlayer(state: PlayerState, onToggle: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit) {
    val item = state.current ?: return
    Surface(tonalElevation = 3.dp) {
        Column {
            if (item.kind != MediaKind.RADIO && state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(item.artworkUri, size = 40.dp, placeholder = if (item.kind == MediaKind.RADIO) Icons.Default.Radio else Icons.AutoMirrored.Filled.QueueMusic)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PlayPauseButton(state, onToggle)
                if (item.kind != MediaKind.RADIO) {
                    IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, stringResource(R.string.next)) }
                }
            }
        }
    }
}

@Composable
private fun PlayPauseButton(state: PlayerState, onToggle: () -> Unit) {
    if (state.isBuffering && !state.isPlaying) {
        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
    } else {
        IconButton(onClick = onToggle) {
            Icon(
                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                stringResource(if (state.isPlaying) R.string.pause else R.string.play),
            )
        }
    }
}

/** Full-screen "now playing" sheet with seek bar and queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(vm: PlayerViewModel, state: PlayerState, onDismiss: () -> Unit) {
    val item = state.current ?: return
    var showQueue by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (showQueue) {
            QueueList(state, onSelect = { vm.skipTo(it) }, onClose = { showQueue = false })
            return@ModalBottomSheet
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.KeyboardArrowDown, stringResource(UiR.string.back)) }
                Text(stringResource(R.string.now_playing), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue)) }
            }
            Artwork(
                item.artworkUri,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 16.dp),
                size = 320.dp,
                placeholder = if (item.kind == MediaKind.RADIO) Icons.Default.Radio else Icons.AutoMirrored.Filled.QueueMusic,
            )
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))

            if (item.kind == MediaKind.RADIO) {
                Text(stringResource(R.string.live), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            } else {
                SeekBar(state, vm::seekTo)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val musicControls = item.kind == MediaKind.MUSIC
                IconButton(onClick = vm::toggleShuffle, enabled = musicControls) {
                    Icon(Icons.Default.Shuffle, stringResource(UiR.string.shuffle), tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = vm::previous, enabled = item.kind != MediaKind.RADIO) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous), Modifier.size(36.dp)) }
                FilledIconButton(onClick = vm::toggle, modifier = Modifier.size(72.dp)) {
                    Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (state.isPlaying) R.string.pause else R.string.play), Modifier.size(40.dp))
                }
                IconButton(onClick = vm::next, enabled = item.kind != MediaKind.RADIO) { Icon(Icons.Default.SkipNext, stringResource(R.string.next), Modifier.size(36.dp)) }
                IconButton(onClick = vm::cycleRepeat, enabled = musicControls) {
                    Icon(
                        if (state.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        stringResource(R.string.repeat),
                        tint = if (state.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SeekBar(state: PlayerState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val position = if (dragging) dragValue else state.positionMs.toFloat() / duration
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = position.coerceIn(0f, 1f),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { onSeek((dragValue * duration).toLong()); dragging = false },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((position * duration).toLong()), style = MaterialTheme.typography.labelMedium)
            Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun QueueList(state: PlayerState, onSelect: (Int) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, stringResource(UiR.string.back)) }
            Text(stringResource(R.string.queue), style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn {
            itemsIndexed(state.queue, key = { i, q -> "$i-${q.mediaId}" }) { i, q ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(q.artworkUri, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(q.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i == state.currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(q.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
