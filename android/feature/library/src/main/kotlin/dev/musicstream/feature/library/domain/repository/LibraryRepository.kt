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
import kotlinx.coroutines.flow.Flow

/** Home-server music catalogue. Each call is a cold flow that emits once; failures are [dev.musicstream.core.common.AppError]. */
interface LibraryRepository {
    fun tracks(query: TrackQuery, offset: Int, limit: Int): Flow<Page<Track>>
    fun artists(search: String?, category: CategoryFilter, offset: Int, limit: Int): Flow<Page<Artist>>
    fun albums(artistId: String?, search: String?, category: CategoryFilter, offset: Int, limit: Int): Flow<Page<Album>>
    fun categories(): Flow<List<LibraryCategory>>
    fun artist(id: String): Flow<ArtistDetail>
    fun album(id: String): Flow<AlbumDetail>
    fun search(query: String): Flow<SearchResults>
}
