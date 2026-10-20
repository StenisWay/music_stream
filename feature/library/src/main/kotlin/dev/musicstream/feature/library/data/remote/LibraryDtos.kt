package dev.musicstream.feature.library.data.remote

import dev.musicstream.core.common.Page
import dev.musicstream.core.network.ServerUrls
import dev.musicstream.feature.library.domain.model.Album
import dev.musicstream.feature.library.domain.model.Artist
import dev.musicstream.feature.library.domain.model.LibraryCategory
import dev.musicstream.feature.library.domain.model.Track
import kotlinx.serialization.Serializable

// Wire format of the Go server: docs/API.md (handlers in backend/internal/feature/*/presentation/).

/**
 * Public on purpose: other server-backed features (playlists) return tracks
 * in the same shape and reuse this DTO and its mapper.
 */
@Serializable
data class TrackDto(
    val id: Long,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val artistId: String = "",
    val albumId: String? = null,
    val trackNo: Int = 0,
    val year: Int = 0,
    val durationMs: Long = 0,
    val format: String = "",
    val streamUrl: String,
    val artworkUrl: String? = null,
    val playable: Boolean = true,
    val favorite: Boolean = false,
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String,
    val trackCount: Int = 0,
    val albumCount: Int = 0,
    val artworkUrl: String? = null,
)

@Serializable
data class AlbumDto(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String = "",
    val year: Int = 0,
    val trackCount: Int = 0,
    val durationMs: Long = 0,
    val artworkUrl: String? = null,
)

@Serializable
data class CategoryDto(val type: String, val region: String, val trackCount: Int = 0, val artistCount: Int = 0)

@Serializable
data class CategoriesDto(val categories: List<CategoryDto> = emptyList())

@Serializable
data class PageDto<T>(val items: List<T> = emptyList(), val total: Int = 0, val limit: Int = 0, val offset: Int = 0)

@Serializable
data class ArtistDetailDto(val artist: ArtistDto, val albums: List<AlbumDto> = emptyList())

@Serializable
data class AlbumDetailDto(val album: AlbumDto, val tracks: List<TrackDto> = emptyList())

@Serializable
data class SearchDto(
    val tracks: List<TrackDto> = emptyList(),
    val artists: List<ArtistDto> = emptyList(),
    val albums: List<AlbumDto> = emptyList(),
)

fun TrackDto.toDomain() = Track(
    id = id, title = title, artist = artist, album = album, albumArtist = albumArtist,
    artistId = artistId, albumId = albumId, trackNo = trackNo, year = year, durationMs = durationMs,
    format = format, streamUrl = ServerUrls.absolute(streamUrl)!!, artworkUrl = ServerUrls.absolute(artworkUrl),
    playable = playable, favorite = favorite,
)

internal fun ArtistDto.toDomain() = Artist(id, name, trackCount, albumCount, ServerUrls.absolute(artworkUrl))

internal fun AlbumDto.toDomain() =
    Album(id, title, artist, artistId, year, trackCount, durationMs, ServerUrls.absolute(artworkUrl))

internal fun CategoryDto.toDomain() = LibraryCategory(type, region, trackCount, artistCount)

internal fun <D, T> PageDto<D>.toDomain(map: (D) -> T) = Page(items.map(map), total, offset)
