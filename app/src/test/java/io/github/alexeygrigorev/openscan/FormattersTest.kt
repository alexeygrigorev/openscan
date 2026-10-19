package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.util.formatPageCount
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun `formats singular page count`() {
        assertEquals("1 page", formatPageCount(1))
    }

    @Test
    fun `formats plural page count`() {
        assertEquals("0 pages", formatPageCount(0))
        assertEquals("2 pages", formatPageCount(2))
        assertEquals("42 pages", formatPageCount(42))
    }

    @Test
    fun `formats page count with qualifier`() {
        assertEquals("1 captured page", formatPageCount(1, "captured"))
        assertEquals("5 captured pages", formatPageCount(5, "captured"))
        assertEquals("1 new page", formatPageCount(1, "new"))
        assertEquals("3 new pages", formatPageCount(3, "new"))
    }
}
