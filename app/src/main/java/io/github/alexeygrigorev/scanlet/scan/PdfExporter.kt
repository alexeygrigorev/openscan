package io.github.alexeygrigorev.scanlet.scan

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
 * landscape scans) — and, as everywhere in Scanlet, nothing is ever stamped
 * on top of them.
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

    fun exportPdf(pageFiles: List<File>, out: OutputStream) {
        val document = PdfDocument()
        try {
            pageFiles.forEachIndexed { index, file ->
                val bitmap = Images.decodeScaled(file, maxDim = 2400) ?: return@forEachIndexed
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
