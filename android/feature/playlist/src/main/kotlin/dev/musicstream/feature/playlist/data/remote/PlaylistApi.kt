package dev.musicstream.feature.playlist.data.remote

import dev.musicstream.feature.library.data.remote.TrackDto
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Authenticated home-server playlist API (base URL = [dev.musicstream.core.network.ServerUrls.BASE_URL]). */
interface PlaylistApi {
    /** With [containsTrack], each summary also says whether it holds that track. */
    @GET("api/v1/playlists")
    suspend fun playlists(@Query("containsTrack") containsTrack: Long? = null): PlaylistListDto

    @POST("api/v1/playlists")
    suspend fun createPlaylist(@Body body: PlaylistRequest): PlaylistDto

    @GET("api/v1/playlists/{id}")
    suspend fun playlist(@Path("id") id: Long): PlaylistDto

    @PATCH("api/v1/playlists/{id}")
    suspend fun renamePlaylist(@Path("id") id: Long, @Body body: RenameRequest): PlaylistDto

    @DELETE("api/v1/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: Long)

    @PUT("api/v1/playlists/{id}/tracks")
    suspend fun replacePlaylistTracks(@Path("id") id: Long, @Body body: TrackIdsRequest): PlaylistDto

    @POST("api/v1/playlists/{id}/tracks")
    suspend fun appendPlaylistTracks(@Path("id") id: Long, @Body body: TrackIdsRequest): PlaylistDto

    /** Removes every occurrence of the track. */
    @DELETE("api/v1/playlists/{id}/tracks/{trackId}")
    suspend fun removePlaylistTrack(@Path("id") id: Long, @Path("trackId") trackId: Long): PlaylistDto
}

// Wire format of the Go server: docs/API.md (handlers in backend/internal/feature/*/presentation/).

@Serializable
data class PlaylistSummaryDto(val id: Long, val name: String, val trackCount: Int = 0, val containsTrack: Boolean? = null)

@Serializable
data class PlaylistListDto(val items: List<PlaylistSummaryDto> = emptyList())

@Serializable
data class PlaylistDto(val id: Long, val name: String, val tracks: List<TrackDto> = emptyList())

@Serializable
data class PlaylistRequest(val name: String, val trackIds: List<Long> = emptyList())

@Serializable
data class RenameRequest(val name: String)

@Serializable
data class TrackIdsRequest(val trackIds: List<Long>)
