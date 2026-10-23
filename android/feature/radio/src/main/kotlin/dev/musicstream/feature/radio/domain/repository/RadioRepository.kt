package dev.musicstream.feature.radio.domain.repository

import dev.musicstream.feature.radio.domain.model.RadioStation
import kotlinx.coroutines.flow.Flow

/** Internet radio directory plus locally saved favourites. Calls are cold flows that emit once; failures are [dev.musicstream.core.common.AppError]. */
interface RadioRepository {
    val favorites: Flow<List<RadioStation>>
    fun search(name: String, countryCode: String?, limit: Int = 50): Flow<List<RadioStation>>
    fun top(countryCode: String?, limit: Int = 50): Flow<List<RadioStation>>
    fun setFavorite(station: RadioStation, favorite: Boolean): Flow<Unit>
    /** Radio Browser asks clients to report plays so popularity stays accurate. Never fails. */
    fun reportClick(stationId: String): Flow<Unit>
}
