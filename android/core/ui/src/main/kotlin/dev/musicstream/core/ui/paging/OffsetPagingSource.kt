package dev.musicstream.core.ui.paging

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.musicstream.core.common.Page
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Adapts an offset/limit use case (domain [Page]) to Paging 3 so the domain
 * layer stays free of AndroidX Paging.
 */
class OffsetPagingSource<T : Any>(
    private val load: suspend (offset: Int, limit: Int) -> Page<T>,
) : PagingSource<Int, T>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = try {
        val offset = params.key ?: 0
        val page = load(offset, params.loadSize)
        LoadResult.Page(
            data = page.items,
            prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
            nextKey = page.nextOffset,
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<Int, T>): Int? =
        state.anchorPosition?.let { anchor -> (anchor - state.config.pageSize / 2).coerceAtLeast(0) }
}

fun <T : Any> pager(pageSize: Int = 50, load: suspend (offset: Int, limit: Int) -> Page<T>): Flow<PagingData<T>> =
    Pager(PagingConfig(pageSize = pageSize, initialLoadSize = pageSize, enablePlaceholders = false)) {
        OffsetPagingSource(load)
    }.flow

/** Paging over a use case that returns each page as a result flow; the first emission is the page. */
fun <T : Any> pagerOf(pageSize: Int = 50, load: (offset: Int, limit: Int) -> Flow<Result<Page<T>>>): Flow<PagingData<T>> =
    pager(pageSize) { offset, limit -> load(offset, limit).first().getOrThrow() }
