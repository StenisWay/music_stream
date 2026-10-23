package dev.musicstream.feature.radio.domain.usecase

import dev.musicstream.core.common.asResult
import dev.musicstream.core.playback.api.MediaKind
import dev.musicstream.core.playback.api.QueueItem
import dev.musicstream.feature.radio.domain.model.RadioStation
import dev.musicstream.feature.radio.domain.repository.RadioRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SearchStationsUseCase @Inject constructor(private val repo: RadioRepository) {
    /** A blank [name] lists the most popular stations instead. */
    operator fun invoke(name: String, countryCode: String?): Flow<Result<List<RadioStation>>> {
        val n = name.trim()
        return (if (n.isEmpty()) repo.top(countryCode) else repo.search(n, countryCode)).asResult()
    }
}

class ObserveFavoriteStationsUseCase @Inject constructor(private val repo: RadioRepository) {
    operator fun invoke(): Flow<List<RadioStation>> = repo.favorites
}

class ToggleFavoriteStationUseCase @Inject constructor(private val repo: RadioRepository) {
    operator fun invoke(station: RadioStation): Flow<Result<Unit>> =
        repo.setFavorite(station, !station.favorite).asResult()
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
