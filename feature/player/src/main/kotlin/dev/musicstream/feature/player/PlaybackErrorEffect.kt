package dev.musicstream.feature.player

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import dev.musicstream.core.playback.api.PlaybackError

/** Radio stations go offline; tell the user instead of failing silently. */
@Composable
fun PlaybackErrorEffect(error: PlaybackError?, snackbar: SnackbarHostState, onShown: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(error) {
        val text = when (error) {
            null -> return@LaunchedEffect
            is PlaybackError.SourceUnavailable -> context.getString(R.string.radio_unavailable, error.title)
            PlaybackError.Unauthorized -> context.getString(R.string.playback_unauthorized)
            is PlaybackError.Other -> context.getString(R.string.playback_error, error.message)
        }
        snackbar.showSnackbar(text)
        onShown()
    }
}
