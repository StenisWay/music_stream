package dev.musicstream.feature.library.data.repository

import dev.musicstream.core.network.apiFlow
import dev.musicstream.feature.library.data.remote.LibraryApi
import dev.musicstream.feature.library.data.remote.toDomain
import dev.musicstream.feature.library.domain.model.AlbumDetail
import dev.musicstream.feature.library.domain.model.ArtistDetail
import dev.musicstream.feature.library.domain.model.CategoryFilter
import dev.musicstream.feature.library.domain.model.SearchResults
import dev.musicstream.feature.library.domain.model.TrackQuery
import dev.musicstream.feature.library.domain.repository.LibraryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(private val api: LibraryApi) : LibraryRepository {
    override fun tracks(query: TrackQuery, offset: Int, limit: Int) = apiFlow {
        api.tracks(
            query.search, query.artistId, query.albumId, query.category.type, query.category.region,
            query.sort.apiValue, limit, offset,
        ).toDomain { it.toDomain() }
    }

    override fun artists(search: String?, category: CategoryFilter, offset: Int, limit: Int) = apiFlow {
        api.artists(search, category.type, category.region, limit, offset).toDomain { it.toDomain() }
    }

    override fun albums(artistId: String?, search: String?, category: CategoryFilter, offset: Int, limit: Int) = apiFlow {
        api.albums(artistId, search, category.type, category.region, limit, offset).toDomain { it.toDomain() }
    }

    override fun categories() = apiFlow { api.categories().categories.map { it.toDomain() } }

    override fun artist(id: String) = apiFlow {
        api.artist(id).let { ArtistDetail(it.artist.toDomain(), it.albums.map { a -> a.toDomain() }) }
    }

    override fun album(id: String) = apiFlow {
        api.album(id).let { AlbumDetail(it.album.toDomain(), it.tracks.map { t -> t.toDomain() }) }
    }

    override fun search(query: String) = apiFlow {
        api.search(query).let { r ->
            SearchResults(r.tracks.map { it.toDomain() }, r.artists.map { it.toDomain() }, r.albums.map { it.toDomain() })
        }
    }
}
