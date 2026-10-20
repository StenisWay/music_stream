package dev.musicstream.feature.discover.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** Home-server discover API (backend/internal/feature/discover/presentation). */
interface DiscoverApi {
    @GET("api/v1/discover/search")
    suspend fun search(@Query("q") query: String, @Query("source") source: String, @Query("limit") limit: Int = 20): ItemsDto<CandidateDto>

    @POST("api/v1/discover/downloads")
    suspend fun download(@Body body: DownloadRequestDto): JobDto

    @GET("api/v1/discover/downloads")
    suspend fun downloads(): ItemsDto<JobDto>
}

@Serializable
data class ItemsDto<T>(val items: List<T> = emptyList())

@Serializable
data class CandidateDto(
    val source: String,
    val url: String,
    val title: String,
    val uploader: String = "",
    val durationSec: Int = 0,
    val thumbnailUrl: String? = null,
    val artist: String = "",
    val track: String = "",
)

@Serializable
data class DownloadRequestDto(val url: String, val artist: String, val title: String, val album: String)

@Serializable
data class JobDto(
    val id: Long,
    val url: String,
    val artist: String = "",
    val title: String = "",
    val album: String = "",
    val status: String,
    val error: String? = null,
)
