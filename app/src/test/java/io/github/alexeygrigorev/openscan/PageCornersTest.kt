package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.PageCorners
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageCornersTest {

    @Test
    fun `null round-trips to null`() {
        assertNull(PageCorners.encode(null))
        assertNull(PageCorners.decode(null))
        assertNull(PageCorners.decode(""))
        assertNull(PageCorners.decode("  "))
    }

    @Test
    fun `quad survives an encode-decode round trip`() {
        val quad = floatArrayOf(10f, 20f, 100f, 15f, 105f, 200f, 5f, 195f)
        val decoded = PageCorners.decode(PageCorners.encode(quad))
        assertArrayEquals(quad, decoded, 0.001f)
    }

    @Test
    fun `decode rejects malformed input without throwing`() {
        assertNull(PageCorners.decode("not json"))
        assertNull(PageCorners.decode("[1,2,3]"))
        assertNull(PageCorners.decode("[1,2,3,4,5,6,7,8,9]"))
        assertNull(PageCorners.decode("[1,2,3,4,5,6,7,\"x\"]"))
        assertNull(PageCorners.decode("[0,0,0,0,0,0,0,NaN]"))
    }

    @Test
    fun `schema version is positive and documented`() {
        assertEquals(1, PageCorners.SCHEMA_VERSION)
    }
}
