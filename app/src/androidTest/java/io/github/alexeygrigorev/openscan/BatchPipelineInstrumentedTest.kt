package io.github.alexeygrigorev.openscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexeygrigorev.openscan.data.DocumentFiles
import io.github.alexeygrigorev.openscan.data.DocumentsRepository
import io.github.alexeygrigorev.openscan.data.OpenScanDatabase
import io.github.alexeygrigorev.openscan.data.PageEntity
import io.github.alexeygrigorev.openscan.scan.Images
import io.github.alexeygrigorev.openscan.scan.JpegExporter
import io.github.alexeygrigorev.openscan.scan.PageImporter
import io.github.alexeygrigorev.openscan.scan.PdfExporter
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full batch pipeline against a real (in-memory) Room database and real
 * page files: import a three-page batch, reorder it, delete a page, then
 * produce both exports and inspect them. These are the exact code paths the
 * UI drives after ML Kit capture — the GMS scan activity itself needs Play
 * Services hardware and is verified on device, not here.
 *
 * Regression guard: the first version of PageImporter threw "cannot open"
 * on every URI because the elvis was attached to the bounds-only decode
 * result (always null) instead of the stream. This test fails on any
 * regression of that shape.
 */
@RunWith(AndroidJUnit4::class)
class BatchPipelineInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val colors = mapOf(
        "red" to Color.rgb(200, 30, 30),
        "green" to Color.rgb(30, 170, 30),
        "blue" to Color.rgb(30, 30, 210),
    )

    @Test
    fun batchImportReorderDeleteThenExportPdfAndZip() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(context, OpenScanDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val files = DocumentFiles(context)
        val repo = DocumentsRepository(db.openscanDao(), files, PageImporter(context))

        try {
            val documentId = repo.createDocument("E2E batch")
            listOf("red", "green", "blue").forEach { name ->
                repo.importPage(documentId, writeScanJpeg(name))
            }

            // Batch import: three pages stored in scan order, files on disk.
            var pages = repo.getPages(documentId)
            assertEquals(listOf(0, 1, 2), pages.map { it.position })
            pages.forEach { page -> assertTrue("page file written", File(page.filePath).length() > 0) }
            assertPageOrder(pages, listOf("red", "green", "blue"))

            // Reorder: the green page moves to the front.
            repo.movePage(pages[1], -1)
            pages = repo.getPages(documentId)
            assertPageOrder(pages, listOf("green", "red", "blue"))

            // Delete a page: the rest keep their relative order and its file
            // is removed. Position gaps after deletes are by design —
            // PageOrdering.move survives them.
            val deletedPath = pages.first().filePath
            repo.deletePage(pages.first())
            pages = repo.getPages(documentId)
            assertEquals(2, pages.size)
            assertTrue(
                "positions stay strictly increasing",
                pages.zipWithNext().all { (a, b) -> a.position < b.position },
            )
            assertFalse("deleted page file removed", File(deletedPath).exists())
            assertPageOrder(pages, listOf("red", "blue"))

            // PDF export of the surviving batch, verified by re-rendering.
            val pdf = File(context.filesDir, "e2e-export.pdf")
            PdfExporter.exportPdf(pages.map { File(it.filePath) }, pdf.outputStream())
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals("pdf has one page per surviving page", 2, renderer.pageCount)
                val expected = listOf("red", "blue")
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertColorClose(
                            "pdf page $index center",
                            rendered.getPixel(rendered.width / 2, rendered.height / 2),
                            colors.getValue(expected[index]),
                        )
                        rendered.recycle()
                    }
                }
            }

            // JPEG zip export: one entry per page, byte-for-byte, in order.
            val zip = File(context.filesDir, "e2e-export.zip")
            JpegExporter.exportZip(pages.map { File(it.filePath) }, zip.outputStream())
            ZipFile(zip).use { archive ->
                assertEquals(
                    listOf("page-001.jpg", "page-002.jpg"),
                    archive.entries().asSequence().map { it.name }.toList(),
                )
                pages.forEachIndexed { index, page ->
                    val exported = archive.getInputStream(archive.getEntry("page-%03d.jpg".format(index + 1)))
                    assertTrue(
                        "zip page $index is a byte-for-byte copy",
                        exported.readBytes().contentEquals(File(page.filePath).readBytes()),
                    )
                }
            }

            // Deleting the document wipes its rows and its files; the exports
            // already handed to the user stay put.
            val documentDir = files.documentDir(documentId)
            repo.deleteDocument(documentId)
            assertFalse(documentDir.exists())
            assertTrue(pdf.exists())
        } finally {
            db.close()
        }
    }

    /**
     * Writes a scan into the FileProvider-covered exports cache and hands it
     * back as a content:// URI — the same shape of source the app receives
     * from ML Kit capture and the photo picker.
     */
    private fun writeScanJpeg(name: String): Uri {
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(colors.getValue(name))
        val file = File(File(context.cacheDir, "exports").apply { mkdirs() }, "batch-$name.jpg")
        Images.saveJpeg(bitmap, file)
        bitmap.recycle()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun assertPageOrder(pages: List<PageEntity>, expected: List<String>) {
        expected.forEachIndexed { index, name ->
            val bitmap = Images.decodeScaled(File(pages[index].filePath), maxDim = 64)!!
            val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            bitmap.recycle()
            assertColorClose("page at position $index", center, colors.getValue(name))
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
