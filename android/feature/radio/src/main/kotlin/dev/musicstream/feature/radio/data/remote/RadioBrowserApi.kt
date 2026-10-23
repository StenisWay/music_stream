package dev.musicstream.feature.radio.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Community Radio Browser directory (https://api.radio-browser.info, no key). */
interface RadioBrowserApi {
    @GET("json/stations/search")
    suspend fun search(
        @Query("name") name: String?,
        @Query("countrycode") countryCode: String?,
        @Query("limit") limit: Int,
        @Query("order") order: String = "clickcount",
        @Query("reverse") reverse: Boolean = true,
        @Query("hidebroken") hideBroken: Boolean = true,
    ): List<RadioStationDto>

    @GET("json/url/{uuid}")
    suspend fun click(@Path("uuid") uuid: String)
}

@Serializable
data class RadioStationDto(
    @SerialName("stationuuid") val uuid: String,
    val name: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val url: String = "",
    val country: String = "",
    @SerialName("countrycode") val countryCode: String = "",
    val tags: String = "",
    val favicon: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    @SerialName("lastcheckok") val lastCheckOk: Int = 1,
)
