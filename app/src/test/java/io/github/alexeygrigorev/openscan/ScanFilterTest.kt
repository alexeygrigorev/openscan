package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanFilterTest {

    @Test
    fun `default is the high-contrast document mode`() {
        assertEquals(ScanFilter.DOCUMENT, ScanFilter.DEFAULT)
        assertEquals("document", ScanFilter.DEFAULT.id)
    }

    @Test
    fun `unknown and null ids fall back to the default`() {
        assertEquals(ScanFilter.DOCUMENT, ScanFilter.fromId(null))
        assertEquals(ScanFilter.DOCUMENT, ScanFilter.fromId(""))
        assertEquals(ScanFilter.DOCUMENT, ScanFilter.fromId("sepia"))
    }

    @Test
    fun `every known id resolves to itself`() {
        for (filter in ScanFilter.entries) {
            assertEquals(filter, ScanFilter.fromId(filter.id))
        }
    }

    @Test
    fun `effective prefers page over document over app default`() {
        assertEquals(
            ScanFilter.GRAYSCALE,
            ScanFilter.effective("gray", "bw", "original"),
        )
        assertEquals(
            ScanFilter.BLACK_WHITE,
            ScanFilter.effective(null, "bw", "original"),
        )
        assertEquals(
            ScanFilter.ORIGINAL,
            ScanFilter.effective(null, null, "original"),
        )
        assertEquals(
            ScanFilter.DOCUMENT,
            ScanFilter.effective(null, null, null),
        )
    }

    @Test
    fun `effective ignores unknown page and document ids`() {
        assertEquals(
            ScanFilter.DOCUMENT,
            ScanFilter.effective("sepia", "nope", null),
        )
        assertEquals(
            ScanFilter.GRAYSCALE,
            ScanFilter.effective("sepia", null, "gray"),
        )
    }
}
