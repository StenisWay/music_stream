package dev.musicstream.feature.radio.data.repository

import dev.musicstream.core.database.dao.StationDao
import dev.musicstream.core.network.apiCall
import dev.musicstream.feature.radio.data.remote.RadioBrowserApi
import dev.musicstream.feature.radio.domain.model.RadioStation
import dev.musicstream.feature.radio.domain.repository.RadioRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    override fun search(name: String, countryCode: String?, limit: Int) = load(name, countryCode, limit)

    override fun top(countryCode: String?, limit: Int) = load(null, countryCode, limit)

    private fun load(name: String?, countryCode: String?, limit: Int): Flow<List<RadioStation>> = flow {
        val favs = stations.ids().toSet()
        val found = apiCall { api.search(name, countryCode?.takeIf { it.isNotBlank() }, limit) }
            .filter { it.lastCheckOk == 1 && (it.urlResolved.isNotBlank() || it.url.isNotBlank()) }
            .distinctBy { it.uuid }
            .map { it.toDomain(it.uuid in favs) }
        emit(found)
    }

    override fun setFavorite(station: RadioStation, favorite: Boolean) = flow {
        if (favorite) stations.upsert(station.toEntity(System.currentTimeMillis())) else stations.delete(station.id)
        emit(Unit)
    }

    override fun reportClick(stationId: String) = flow {
        runCatching { api.click(stationId) }
        emit(Unit)
    }
}
