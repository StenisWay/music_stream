package dev.musicstream.core.common

/** One window of a server-side paginated list. */
data class Page<T>(val items: List<T>, val total: Int, val offset: Int) {
    val nextOffset: Int? get() = (offset + items.size).takeIf { it < total && items.isNotEmpty() }
}
