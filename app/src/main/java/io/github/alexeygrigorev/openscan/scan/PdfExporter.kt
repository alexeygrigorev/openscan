package io.github.alexeygrigorev.openscan.scan

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import java.io.File
import java.io.OutputStream
import kotlin.math.min

/**
 * Builds multi-page PDFs from page JPEGs with the framework PdfDocument.
 * Pages are placed centered on an A4 sheet (595×842 pt, swapped for
 * landscape scans) — and, as everywhere in OpenScan, nothing is ever stamped
 * on top of them.
 *
 * Resolution (item 10): pages decode at up to 3600 px so a full-bleed A4
 * embeds at ~300 DPI (3600 px / 11.69 in); the old 2400 px ceiling topped
 * out near 200 DPI.
 */
object PdfExporter {

    /** Centered, aspect-preserving placement of an image on a page. Pure math — unit-tested. */
    data class Fit(val x: Float, val y: Float, val width: Float, val height: Float)

    fun fitInto(imageWidth: Int, imageHeight: Int, pageWidth: Float, pageHeight: Float): Fit {
        val scale = min(pageWidth / imageWidth, pageHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return Fit(
            x = (pageWidth - width) / 2f,
            y = (pageHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

    /** Decode bound for PDF pages: covers 300 DPI on A4's long edge. */
    const val PDF_MAX_DIM = 3600

    fun exportPdf(pageFiles: List<File>, out: OutputStream) {
        exportPdf(pageFiles, List(pageFiles.size) { ScanFilter.ORIGINAL }, out)
    }

    /**
     * Filtered export (item 6): each page is enhanced with its effective
     * filter before embedding. A filter that fails (e.g. OOM) falls back to
     * the original page — export never crashes on enhancement.
     */
    fun exportPdf(pageFiles: List<File>, filters: List<ScanFilter>, out: OutputStream) {
        val document = PdfDocument()
        try {
            pageFiles.forEachIndexed { index, file ->
                val decoded = Images.decodeScaled(file, maxDim = PDF_MAX_DIM) ?: return@forEachIndexed
                val filter = filters.getOrElse(index) { ScanFilter.ORIGINAL }
                val bitmap = if (filter == ScanFilter.ORIGINAL) {
                    decoded
                } else {
                    ScanFilters.apply(decoded, filter)?.also { decoded.recycle() } ?: decoded
                }
                val landscape = bitmap.width > bitmap.height
                val pageWidth = if (landscape) A4_LONG_PT else A4_SHORT_PT
                val pageHeight = if (landscape) A4_SHORT_PT else A4_LONG_PT

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)
                val fit = fitInto(bitmap.width, bitmap.height, pageWidth.toFloat(), pageHeight.toFloat())
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(fit.x, fit.y, fit.x + fit.width, fit.y + fit.height),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                document.finishPage(page)
                bitmap.recycle()
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
    }

    const val A4_SHORT_PT = 595
    const val A4_LONG_PT = 842
}
