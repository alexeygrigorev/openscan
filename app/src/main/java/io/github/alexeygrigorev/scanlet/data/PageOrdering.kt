package io.github.alexeygrigorev.scanlet.data

/**
 * Pure page-order math, kept out of the ViewModel so it can be unit-tested.
 * The canonical order is always "sorted by position, ties by insertion id";
 * every move returns a full re-indexed list so gaps left by deleted pages
 * disappear instead of accumulating.
 */
object PageOrdering {

    /**
     * Moves [pageId] by [delta] slots in the page list (-1 = up, +1 = down).
     * Returns the reordered list with positions normalized to 0..n-1, or the
     * original list unchanged when the move is a no-op (page missing, or
     * already at the target edge).
     */
    fun move(pages: List<PageEntity>, pageId: Long, delta: Int): List<PageEntity> {
        if (delta == 0) return pages
        val ordered = pages.sortedWith(compareBy({ it.position }, { it.id }))
        val index = ordered.indexOfFirst { it.id == pageId }
        if (index < 0) return pages
        val target = index + delta
        if (target < 0 || target >= ordered.size) return pages

        val result = ordered.toMutableList()
        val moved = result.removeAt(index)
        result.add(target, moved)
        return result.reindex()
    }
}

/** Normalizes positions to the list index; identities are preserved. */
fun List<PageEntity>.reindex(): List<PageEntity> =
    mapIndexed { index, page -> if (page.position == index) page else page.copy(position = index) }
