package dev.musicstream.feature.radio.domain.repository

import dev.musicstream.feature.radio.domain.model.RadioStation
import kotlinx.coroutines.flow.Flow

/** Internet radio directory plus locally saved favourites. All failures are [dev.musicstream.core.common.AppError]. */
interface RadioRepository {
    val favorites: Flow<List<RadioStation>>
    suspend fun search(name: String, countryCode: String?, limit: Int = 50): List<RadioStation>
    suspend fun top(countryCode: String?, limit: Int = 50): List<RadioStation>
    suspend fun setFavorite(station: RadioStation, favorite: Boolean)
    /** Radio Browser asks clients to report plays so popularity stays accurate. */
    suspend fun reportClick(stationId: String)
}
