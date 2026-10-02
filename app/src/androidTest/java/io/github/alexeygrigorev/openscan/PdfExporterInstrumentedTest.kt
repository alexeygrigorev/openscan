package io.github.alexeygrigorev.openscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexeygrigorev.openscan.scan.Images
import io.github.alexeygrigorev.openscan.scan.PdfExporter
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exports real PDFs and re-opens them with the framework PdfRenderer to
 * inspect the rendered pixels: every page of a batch must land on its own
 * A4 sheet, in scan order, centered on white — and nothing else, because
 * OpenScan never stamps anything onto an export.
 */
@RunWith(AndroidJUnit4::class)
class PdfExporterInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun batchExportKeepsEveryPageInOrderOnItsOwnA4Sheet() {
        val pages = listOf(
            PageSource(Color.rgb(200, 30, 30), portrait = true),
            PageSource(Color.rgb(30, 170, 30), portrait = true),
            PageSource(Color.rgb(30, 30, 210), portrait = false),
        )
        val pdf = File(context.cacheDir, "pdf-exporter-e2e.pdf")
        PdfExporter.exportPdf(pages.map { it.writeJpeg() }, pdf.outputStream())

        PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            assertEquals("one PDF page per scanned page", pages.size, renderer.pageCount)
            pages.forEachIndexed { index, source ->
                renderer.openPage(index).use { page ->
                    val expectedWidth = if (source.portrait) PdfExporter.A4_SHORT_PT else PdfExporter.A4_LONG_PT
                    val expectedHeight = if (source.portrait) PdfExporter.A4_LONG_PT else PdfExporter.A4_SHORT_PT
                    assertEquals("page $index width", expectedWidth, page.width)
                    assertEquals("page $index height", expectedHeight, page.height)

                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    assertColorClose(
                        "page $index center",
                        rendered.getPixel(rendered.width / 2, rendered.height / 2),
                        source.color,
                    )
                    assertColorClose("page $index letterbox corner", rendered.getPixel(2, 2), Color.WHITE)
                    rendered.recycle()
                }
            }
        }
        pdf.delete()
    }

    private inner class PageSource(val color: Int, val portrait: Boolean) {
        fun writeJpeg(): File {
            val bitmap = Bitmap.createBitmap(800, if (portrait) 1000 else 640, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            val file = File.createTempFile("pdf-e2e", ".jpg", context.cacheDir)
            Images.saveJpeg(bitmap, file)
            bitmap.recycle()
            return file
        }
    }

    private fun assertColorClose(what: String, actual: Int, expected: Int, tolerance: Int = 24) {
        listOf({ c: Int -> Color.red(c) }, { c: Int -> Color.green(c) }, { c: Int -> Color.blue(c) })
            .forEach { channel ->
                assertTrue(
                    "$what: ${channel(actual)} vs expected ${channel(expected)}",
                    abs(channel(actual) - channel(expected)) <= tolerance,
                )
            }
    }
}
