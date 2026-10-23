package dev.musicstream.feature.library.domain.repository

import dev.musicstream.core.common.Page
import dev.musicstream.feature.library.domain.model.Album
import dev.musicstream.feature.library.domain.model.AlbumDetail
import dev.musicstream.feature.library.domain.model.Artist
import dev.musicstream.feature.library.domain.model.ArtistDetail
import dev.musicstream.feature.library.domain.model.CategoryFilter
import dev.musicstream.feature.library.domain.model.LibraryCategory
import dev.musicstream.feature.library.domain.model.SearchResults
import dev.musicstream.feature.library.domain.model.Track
import dev.musicstream.feature.library.domain.model.TrackQuery

/** Home-server music catalogue. All failures are [dev.musicstream.core.common.AppError]. */
interface LibraryRepository {
    suspend fun tracks(query: TrackQuery, offset: Int, limit: Int): Page<Track>
    suspend fun artists(search: String?, category: CategoryFilter, offset: Int, limit: Int): Page<Artist>
    suspend fun albums(artistId: String?, search: String?, category: CategoryFilter, offset: Int, limit: Int): Page<Album>
    suspend fun categories(): List<LibraryCategory>
    suspend fun artist(id: String): ArtistDetail
    suspend fun album(id: String): AlbumDetail
    suspend fun search(query: String): SearchResults
}
