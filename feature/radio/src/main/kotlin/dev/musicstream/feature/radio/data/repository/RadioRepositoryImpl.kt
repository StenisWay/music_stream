package dev.musicstream.feature.radio.data.repository

import dev.musicstream.core.database.dao.StationDao
import dev.musicstream.core.network.apiCall
import dev.musicstream.feature.radio.data.remote.RadioBrowserApi
import dev.musicstream.feature.radio.domain.model.RadioStation
import dev.musicstream.feature.radio.domain.repository.RadioRepository
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Internet radio via the community Radio Browser directory (no API key). */
@Singleton
class RadioRepositoryImpl @Inject constructor(
    private val api: RadioBrowserApi,
    private val stations: StationDao,
) : RadioRepository {
    override val favorites = stations.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun search(name: String, countryCode: String?, limit: Int) = load(name, countryCode, limit)

    override suspend fun top(countryCode: String?, limit: Int) = load(null, countryCode, limit)

    private suspend fun load(name: String?, countryCode: String?, limit: Int): List<RadioStation> {
        val favs = stations.ids().toSet()
        return apiCall { api.search(name, countryCode?.takeIf { it.isNotBlank() }, limit) }
            .filter { it.lastCheckOk == 1 && (it.urlResolved.isNotBlank() || it.url.isNotBlank()) }
            .distinctBy { it.uuid }
            .map { it.toDomain(it.uuid in favs) }
    }

    override suspend fun setFavorite(station: RadioStation, favorite: Boolean) {
        if (favorite) stations.upsert(station.toEntity(System.currentTimeMillis())) else stations.delete(station.id)
    }

    override suspend fun reportClick(stationId: String) {
        runCatching { api.click(stationId) }
    }
}
