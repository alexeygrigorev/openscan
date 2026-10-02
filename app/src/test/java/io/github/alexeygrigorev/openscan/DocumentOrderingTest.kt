package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.data.DocumentEntity
import io.github.alexeygrigorev.openscan.data.PageEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentOrderingTest {

    private fun doc(id: Long, updatedAt: Long) = DocumentEntity(
        id = id,
        title = "doc-$id",
        createdAt = updatedAt - 100,
        updatedAt = updatedAt,
    )

    @Test
    fun `library shows most recently updated document first`() {
        val docs = listOf(doc(1, 100), doc(2, 300), doc(3, 200))
        val sorted = docs.sortedByDescending { it.updatedAt }
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun `page positions sort in scan order`() {
        fun page(position: Int) = PageEntity(
            documentId = 1,
            position = position,
            filePath = "page-$position.jpg",
            createdAt = 0,
        )
        val shuffled = listOf(2, 0, 3, 1).map(::page)
        assertEquals(listOf(0, 1, 2, 3), shuffled.sortedBy { it.position }.map { it.position })
    }
}
