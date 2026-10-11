package dev.musicstream.feature.radio.data

import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.PlaybackEventListener
import dev.musicstream.feature.radio.domain.repository.RadioRepository
import javax.inject.Inject

/** Reports radio plays to Radio Browser so station popularity stays accurate. */
class RadioPlayReporter @Inject constructor(private val radio: RadioRepository) : PlaybackEventListener {
    override suspend fun onItemStarted(kind: MediaKind, key: String) {
        if (kind == MediaKind.RADIO) radio.reportClick(key)
    }
}
