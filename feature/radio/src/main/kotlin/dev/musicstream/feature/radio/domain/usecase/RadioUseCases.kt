package dev.musicstream.feature.radio.domain.usecase

import dev.musicstream.core.common.runCatchingApp
import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.QueueItem
import dev.musicstream.feature.radio.domain.model.RadioStation
import dev.musicstream.feature.radio.domain.repository.RadioRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SearchStationsUseCase @Inject constructor(private val repo: RadioRepository) {
    suspend operator fun invoke(name: String, countryCode: String?): Result<List<RadioStation>> = runCatchingApp {
        val n = name.trim()
        if (n.isEmpty()) repo.top(countryCode) else repo.search(n, countryCode)
    }
}

class ObserveFavoriteStationsUseCase @Inject constructor(private val repo: RadioRepository) {
    operator fun invoke(): Flow<List<RadioStation>> = repo.favorites
}

class ToggleFavoriteStationUseCase @Inject constructor(private val repo: RadioRepository) {
    suspend operator fun invoke(station: RadioStation): Result<Unit> =
        runCatchingApp { repo.setFavorite(station, !station.favorite) }
}

/** Maps a station to a source-independent queue entry. */
fun RadioStation.toQueueItem() = QueueItem(
    mediaId = QueueItem.mediaId(MediaKind.RADIO, id),
    kind = MediaKind.RADIO,
    uri = streamUrl,
    title = name,
    subtitle = listOf(country, tags.take(3).joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
    artworkUri = faviconUrl,
)
