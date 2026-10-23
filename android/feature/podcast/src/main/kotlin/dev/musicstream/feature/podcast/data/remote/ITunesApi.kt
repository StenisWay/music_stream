package dev.musicstream.feature.podcast.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/** iTunes Search API (no key required). */
interface ITunesApi {
    @GET("search")
    suspend fun searchPodcasts(
        @Query("term") term: String,
        @Query("media") media: String = "podcast",
        @Query("entity") entity: String = "podcast",
        @Query("limit") limit: Int = 30,
        @Query("country") country: String = "TW",
    ): ITunesSearchDto
}

@Serializable
data class ITunesSearchDto(val results: List<ITunesPodcastDto> = emptyList())

@Serializable
data class ITunesPodcastDto(
    val feedUrl: String? = null,
    val collectionName: String = "",
    val artistName: String = "",
    val artworkUrl600: String? = null,
    val artworkUrl100: String? = null,
)
