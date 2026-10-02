package io.github.alexeygrigorev.scanlet

import io.github.alexeygrigorev.scanlet.data.PageEntity
import io.github.alexeygrigorev.scanlet.data.PageOrdering.move
import io.github.alexeygrigorev.scanlet.data.reindex
import org.junit.Assert.assertEquals
import org.junit.Test

class PageOrderingTest {

    private var nextId = 1L
    private fun page(position: Int) = PageEntity(
        id = nextId++,
        documentId = 1,
        position = position,
        filePath = "page-$position.jpg",
        createdAt = 0,
    )

    @Test
    fun `moving down swaps with the next page`() {
        val pages = listOf(page(0), page(1), page(2))
        val moved = move(pages, pageId = 2, delta = 1)
        assertEquals(listOf(1L, 3L, 2L), moved.map { it.id })
        assertEquals(listOf(0, 1, 2), moved.map { it.position })
    }

    @Test
    fun `moving up swaps with the previous page`() {
        val pages = listOf(page(0), page(1), page(2))
        val moved = move(pages, pageId = 2, delta = -1)
        assertEquals(listOf(2L, 1L, 3L), moved.map { it.id })
        assertEquals(listOf(0, 1, 2), moved.map { it.position })
    }

    @Test
    fun `moving the first page up is a no-op`() {
        val pages = listOf(page(0), page(1), page(2))
        assertEquals(pages, move(pages, pageId = 1, delta = -1))
    }

    @Test
    fun `moving the last page down is a no-op`() {
        val pages = listOf(page(0), page(1), page(2))
        assertEquals(pages, move(pages, pageId = 3, delta = 1))
    }

    @Test
    fun `moves survive gaps left by deleted pages`() {
        // Page 1 was deleted: positions are 0 and 2 before re-indexing.
        val pages = listOf(page(0), page(2))
        val moved = move(pages, pageId = 2, delta = -1)
        assertEquals(listOf(2L, 1L), moved.map { it.id })
        assertEquals(listOf(0, 1), moved.map { it.position })
    }

    @Test
    fun `unknown page leaves the order untouched`() {
        val pages = listOf(page(0), page(1))
        assertEquals(pages, move(pages, pageId = 99, delta = 1))
    }

    @Test
    fun `reindex keeps unchanged rows identical`() {
        val pages = listOf(page(0), page(1))
        assertEquals(pages, pages.reindex())
    }
}
