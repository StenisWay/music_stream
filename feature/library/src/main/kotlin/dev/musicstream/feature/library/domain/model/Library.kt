package dev.musicstream.feature.library.domain.model

/**
 * A track of the home music library. [streamUrl] and [artworkUrl] use the
 * stable virtual host `https://server.musicstream/`; the data layer routes
 * them to whichever internal address is reachable (home LAN or VPN).
 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val artistId: String,
    val albumId: String?,
    val trackNo: Int,
    val year: Int,
    val durationMs: Long,
    val format: String,
    val streamUrl: String,
    val artworkUrl: String?,
    val playable: Boolean,
    val favorite: Boolean,
) {
    /** Artist shown in lists: album artist wins for compilations. */
    val displayArtist: String get() = artist.ifBlank { albumArtist.ifBlank { UNKNOWN_ARTIST } }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown Artist"
    }
}

data class Artist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val albumCount: Int,
    val artworkUrl: String?,
)

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String,
    val year: Int,
    val trackCount: Int,
    val durationMs: Long,
    val artworkUrl: String?,
)

data class ArtistDetail(val artist: Artist, val albums: List<Album>)

data class AlbumDetail(val album: Album, val tracks: List<Track>)

data class SearchResults(
    val tracks: List<Track>,
    val artists: List<Artist>,
    val albums: List<Album>,
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty()
}

enum class TrackSort(val apiValue: String) {
    TITLE("title"),
    ARTIST("artist"),
    ALBUM("album"),
    RECENTLY_ADDED("-addedAt"),
}

data class TrackQuery(
    val search: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val category: CategoryFilter = CategoryFilter.ALL,
    val sort: TrackSort = TrackSort.TITLE,
)

/**
 * One folder of the home library's two classification levels,
 * `library/<type>/<region>/`: type is 歌曲 (vocals) or 純音樂 (instrumental),
 * region is 華語 / 日本 / 韓國 / 歐美 / 其他. The names come from the server
 * exactly as they are on disk.
 */
data class LibraryCategory(
    val type: String,
    val region: String,
    val trackCount: Int,
    val artistCount: Int,
)

/** Narrows lists to a type and/or region; null means "all". */
data class CategoryFilter(val type: String? = null, val region: String? = null) {
    val isAll: Boolean get() = type == null && region == null

    companion object {
        val ALL = CategoryFilter()
    }
}
