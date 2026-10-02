package io.github.alexeygrigorev.scanlet

import io.github.alexeygrigorev.scanlet.scan.JpegExporter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JpegExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun jpegFile(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `zip entry names sort by page number`() {
        assertEquals("page-001.jpg", JpegExporter.zipEntryName(0))
        assertEquals("page-002.jpg", JpegExporter.zipEntryName(1))
        assertEquals("page-010.jpg", JpegExporter.zipEntryName(9))
        assertEquals("page-100.jpg", JpegExporter.zipEntryName(99))
    }

    @Test
    fun `zip export carries every page in order with its bytes intact`() {
        val pageA = jpegFile("a.jpg", byteArrayOf(1, 2, 3))
        val pageB = jpegFile("b.jpg", byteArrayOf(9, 8))
        val out = ByteArrayOutputStream()

        JpegExporter.exportZip(listOf(pageA, pageB), out)

        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry.name to zip.readBytes())
                entry = zip.nextEntry
            }
        }
        assertEquals(listOf("page-001.jpg", "page-002.jpg"), entries.map { it.first })
        assertTrue(entries[0].second.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(entries[1].second.contentEquals(byteArrayOf(9, 8)))
    }

    @Test
    fun `single export is a byte-for-byte copy`() {
        val bytes = ByteArray(64) { it.toByte() }
        val page = jpegFile("page.jpg", bytes)
        val out = ByteArrayOutputStream()

        JpegExporter.exportSingle(page, out)

        assertTrue(out.toByteArray().contentEquals(bytes))
    }
}
