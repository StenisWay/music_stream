package dev.musicstream.feature.library.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.musicstream.core.playback.api.PlayerController
import dev.musicstream.core.ui.component.Artwork
import dev.musicstream.core.ui.component.MenuAction
import dev.musicstream.core.ui.component.OverflowMenu
import dev.musicstream.core.ui.component.formatDuration
import dev.musicstream.feature.library.R
import dev.musicstream.feature.library.domain.model.Album
import dev.musicstream.feature.library.domain.model.Artist
import dev.musicstream.feature.library.domain.model.Track
import dev.musicstream.feature.library.domain.usecase.toQueueItem

// Music widgets shared by every screen that lists library tracks
// (library, search, playlists).

/** Plays [tracks] starting at [index], skipping formats the server cannot stream. */
fun PlayerController.playTracks(tracks: List<Track>, index: Int, shuffle: Boolean = false) {
    val playable = tracks.filter { it.playable }
    if (playable.isEmpty()) return
    val start = tracks.getOrNull(index)?.let { playable.indexOf(it) }?.coerceAtLeast(0) ?: 0
    val queue = playable.map { it.toQueueItem() }
    if (shuffle) {
        play(queue.shuffled(), 0)
    } else {
        play(queue, start)
    }
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    menu: List<MenuAction>,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    isCurrent: Boolean = false,
) {
    val enabled = track.playable
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else Artwork(track.artworkUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (enabled) track.displayArtist.ifUnknown() else stringResource(R.string.unsupported_format),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.durationMs > 0) {
            Text(
                formatDuration(track.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        OverflowMenu(menu)
    }
}

@Composable
fun String.ifUnknown(): String = if (this == Track.UNKNOWN_ARTIST) stringResource(R.string.unknown_artist) else this

@Composable
fun ArtistRow(artist: Artist, onClick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick(artist.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artist.artworkUrl, placeholder = Icons.Default.Person)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name.ifUnknown(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.track_count, artist.trackCount) +
                    if (artist.albumCount > 0) " · " + stringResource(R.string.album_count, artist.albumCount) else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AlbumCard(album: Album, onClick: (String) -> Unit) {
    Column(Modifier.clickable { onClick(album.id) }) {
        Artwork(album.artworkUrl, Modifier.fillMaxWidth(), size = 160.dp, placeholder = Icons.Default.Album)
        Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text(album.artist.ifUnknown(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
