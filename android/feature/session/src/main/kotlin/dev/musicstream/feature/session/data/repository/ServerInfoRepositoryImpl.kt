package dev.musicstream.feature.session.data.repository

import dev.musicstream.core.network.apiCall
import dev.musicstream.feature.session.data.remote.ServerInfoApi
import dev.musicstream.feature.session.domain.model.ServerInfo
import dev.musicstream.feature.session.domain.repository.ServerInfoRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerInfoRepositoryImpl @Inject constructor(private val api: ServerInfoApi) : ServerInfoRepository {
    override suspend fun serverInfo() = apiCall {
        api.serverInfo().let { ServerInfo(it.version, it.trackCount, it.scanning, it.transcodeAvailable) }
    }
}
