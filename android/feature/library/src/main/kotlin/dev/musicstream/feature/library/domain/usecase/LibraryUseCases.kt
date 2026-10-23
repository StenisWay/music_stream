package dev.musicstream.feature.library.domain.usecase

import dev.musicstream.core.common.Page
import dev.musicstream.core.common.asResult
import dev.musicstream.feature.library.domain.model.Album
import dev.musicstream.feature.library.domain.model.AlbumDetail
import dev.musicstream.feature.library.domain.model.Artist
import dev.musicstream.feature.library.domain.model.ArtistDetail
import dev.musicstream.feature.library.domain.model.CategoryFilter
import dev.musicstream.feature.library.domain.model.LibraryCategory
import dev.musicstream.feature.library.domain.model.SearchResults
import dev.musicstream.feature.library.domain.model.Track
import dev.musicstream.feature.library.domain.model.TrackQuery
import dev.musicstream.feature.library.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

private fun String?.cleanSearch() = this?.trim()?.takeIf { it.isNotEmpty() }

class GetTracksPageUseCase @Inject constructor(private val library: LibraryRepository) {
    operator fun invoke(query: TrackQuery, offset: Int, limit: Int): Flow<Result<Page<Track>>> =
        library.tracks(query.copy(search = query.search.cleanSearch()), offset, limit).asResult()
}

class GetArtistsPageUseCase @Inject constructor(private val library: LibraryRepository) {
    operator fun invoke(search: String?, category: CategoryFilter, offset: Int, limit: Int): Flow<Result<Page<Artist>>> =
        library.artists(search.cleanSearch(), category, offset, limit).asResult()
}

class GetAlbumsPageUseCase @Inject constructor(private val library: LibraryRepository) {
    operator fun invoke(search: String?, category: CategoryFilter, offset: Int, limit: Int): Flow<Result<Page<Album>>> =
        library.albums(null, search.cleanSearch(), category, offset, limit).asResult()
}

/** The library's 類型/語區 folders, mirrored from the server's music folder. */
class GetCategoriesUseCase @Inject constructor(private val library: LibraryRepository) {
    operator fun invoke(): Flow<Result<List<LibraryCategory>>> = library.categories().asResult()
}

class GetArtistDetailUseCase @Inject constructor(private val library: LibraryRepository) {
    operator fun invoke(id: String): Flow<Result<ArtistDetail>> = library.artist(id).asResult()
}

class GetAlbumDetailUseCase @Inject constructor(private val library: LibraryRepository) {
    operator fun invoke(id: String): Flow<Result<AlbumDetail>> = library.album(id).asResult()
}

/** Searches the library; queries shorter than 1 visible character return nothing. */
class SearchLibraryUseCase @Inject constructor(private val library: LibraryRepository) {
    operator fun invoke(query: String): Flow<Result<SearchResults>> {
        val q = query.trim()
        if (q.isEmpty()) return flowOf(Result.success(SearchResults(emptyList(), emptyList(), emptyList())))
        return library.search(q).asResult()
    }
}
