package dev.musicstream.feature.library.domain.usecase

import dev.musicstream.core.common.Page
import dev.musicstream.core.common.runCatchingApp
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
import javax.inject.Inject

class GetTracksPageUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(query: TrackQuery, offset: Int, limit: Int): Page<Track> =
        library.tracks(query.copy(search = query.search?.trim()?.takeIf { it.isNotEmpty() }), offset, limit)
}

class GetArtistsPageUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(search: String?, category: CategoryFilter, offset: Int, limit: Int): Page<Artist> =
        library.artists(search?.trim()?.takeIf { it.isNotEmpty() }, category, offset, limit)
}

class GetAlbumsPageUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(search: String?, category: CategoryFilter, offset: Int, limit: Int): Page<Album> =
        library.albums(null, search?.trim()?.takeIf { it.isNotEmpty() }, category, offset, limit)
}

/** The library's 類型/語區 folders, mirrored from the server's music folder. */
class GetCategoriesUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(): Result<List<LibraryCategory>> = runCatchingApp { library.categories() }
}

class GetArtistDetailUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(id: String): Result<ArtistDetail> = runCatchingApp { library.artist(id) }
}

class GetAlbumDetailUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(id: String): Result<AlbumDetail> = runCatchingApp { library.album(id) }
}

/** Searches the library; queries shorter than 1 visible character return nothing. */
class SearchLibraryUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(query: String): Result<SearchResults> {
        val q = query.trim()
        if (q.isEmpty()) return Result.success(SearchResults(emptyList(), emptyList(), emptyList()))
        return runCatchingApp { library.search(q) }
    }
}

class ToggleFavoriteUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(track: Track): Result<Boolean> = runCatchingApp {
        val target = !track.favorite
        library.setFavorite(track.id, target)
        target
    }
}

class GetFavoritesPageUseCase @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(offset: Int, limit: Int): Page<Track> = library.favorites(offset, limit)
}
