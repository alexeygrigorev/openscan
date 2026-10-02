package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.PdfExporter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Placement math behind PDF export: aspect-preserving, centered on the sheet. */
class PdfExporterFitTest {

    @Test
    fun `portrait scan fills the width and centers vertically`() {
        val fit = PdfExporter.fitInto(800, 1000, PdfExporter.A4_SHORT_PT.toFloat(), PdfExporter.A4_LONG_PT.toFloat())
        assertEquals(595f, fit.width, 0.001f)
        assertEquals(743.75f, fit.height, 0.001f)
        assertEquals(0f, fit.x, 0.001f)
        assertEquals(49.125f, fit.y, 0.001f)
    }

    @Test
    fun `a wide scan letterboxes horizontally on a portrait sheet`() {
        val fit = PdfExporter.fitInto(2000, 500, PdfExporter.A4_SHORT_PT.toFloat(), PdfExporter.A4_LONG_PT.toFloat())
        assertEquals(595f, fit.width, 0.001f)
        assertEquals(148.75f, fit.height, 0.001f)
        assertEquals(0f, fit.x, 0.001f)
        assertEquals(346.625f, fit.y, 0.001f)
    }
}
