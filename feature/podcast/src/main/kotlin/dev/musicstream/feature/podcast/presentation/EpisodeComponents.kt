package dev.musicstream.feature.podcast.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.musicstream.core.ui.component.MenuAction
import dev.musicstream.core.ui.component.OverflowMenu
import dev.musicstream.core.ui.component.formatDuration
import dev.musicstream.feature.podcast.R
import dev.musicstream.feature.podcast.domain.model.Episode
import java.text.DateFormat
import java.util.Date

/**
 * One episode with its listening status. [overline] is the small line on
 * top (publish date in a feed, podcast name in the history).
 */
@Composable
internal fun EpisodeRow(
    e: Episode,
    overline: String,
    onPlay: () -> Unit,
    onSetCompleted: (Boolean) -> Unit,
    extraMenu: List<MenuAction> = emptyList(),
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(overline, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.description.isNotBlank()) {
                Text(e.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EpisodeStatus(e)
        }
        OverflowMenu(
            listOf(
                if (e.completed) MenuAction(stringResource(R.string.podcast_mark_unplayed)) { onSetCompleted(false) }
                else MenuAction(stringResource(R.string.podcast_mark_played)) { onSetCompleted(true) },
            ) + extraMenu,
        )
    }
}

@Composable
private fun EpisodeStatus(e: Episode) {
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
            e.positionMs > 0 -> Text(stringResource(R.string.podcast_in_progress), style = MaterialTheme.typography.labelMedium)
            e.durationMs > 0 -> Text(formatDuration(e.durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun rememberDate(millis: Long): String = remember(millis) {
    if (millis > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis)) else ""
}
