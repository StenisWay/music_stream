package dev.musicstream.feature.library.data.remote

import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Authenticated home-server catalogue API (base URL = [dev.musicstream.core.network.ServerUrls.BASE_URL]). */
interface LibraryApi {
    @GET("api/v1/tracks")
    suspend fun tracks(
        @Query("q") query: String?,
        @Query("artistId") artistId: String?,
        @Query("albumId") albumId: String?,
        @Query("sort") sort: String?,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): PageDto<TrackDto>

    @GET("api/v1/artists")
    suspend fun artists(@Query("q") query: String?, @Query("limit") limit: Int, @Query("offset") offset: Int): PageDto<ArtistDto>

    @GET("api/v1/artists/{id}")
    suspend fun artist(@Path("id") id: String): ArtistDetailDto

    @GET("api/v1/albums")
    suspend fun albums(
        @Query("artistId") artistId: String?,
        @Query("q") query: String?,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): PageDto<AlbumDto>

    @GET("api/v1/albums/{id}")
    suspend fun album(@Path("id") id: String): AlbumDetailDto

    @GET("api/v1/search")
    suspend fun search(@Query("q") query: String, @Query("limit") limit: Int = 20): SearchDto

    @GET("api/v1/favorites")
    suspend fun favorites(@Query("limit") limit: Int, @Query("offset") offset: Int): PageDto<TrackDto>

    @PUT("api/v1/favorites/{trackId}")
    suspend fun addFavorite(@Path("trackId") trackId: Long)

    @DELETE("api/v1/favorites/{trackId}")
    suspend fun removeFavorite(@Path("trackId") trackId: Long)
}
