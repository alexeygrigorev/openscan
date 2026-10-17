package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.DebugArchive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `archive carries pages, raws and markup`() {
        val page = file("page.jpg", byteArrayOf(1, 2, 3))
        val raw = file("raw.jpg", byteArrayOf(9, 8, 7))
        val out = ByteArrayOutputStream()
        DebugArchive.build(
            entries = listOf(
                DebugArchive.Entry(
                    pageId = 11, position = 0, pageFile = page, rawFile = raw,
                    imageWidth = 1000, imageHeight = 1400,
                    storedWidth = 900, storedHeight = 1300,
                    autoCorners = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f),
                    refinedCorners = null, userCorners = null,
                    cornerSchema = 1, filterId = "document",
                ),
                DebugArchive.Entry(
                    pageId = 12, position = 1, pageFile = page, rawFile = null,
                    imageWidth = 0, imageHeight = 0,
                    storedWidth = 0, storedHeight = 0,
                    autoCorners = null, refinedCorners = null, userCorners = null,
                    cornerSchema = 0, filterId = null,
                ),
            ),
            documentTitle = "Test doc",
            appVersion = "0.2.4-test",
            deviceModel = "TestDevice",
            exportedAt = 123L,
            out = out,
        )

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        assertEquals(
            setOf("page-001.jpg", "page-001-raw.jpg", "page-002.jpg", "markup.json"),
            entries.keys,
        )
        assertTrue(entries.getValue("page-001.jpg").contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(entries.getValue("page-001-raw.jpg").contentEquals(byteArrayOf(9, 8, 7)))

        val markup = JSONObject(String(entries.getValue("markup.json")))
        assertEquals(1, markup.getInt("schema"))
        assertEquals("Test doc", markup.getString("documentTitle"))
        assertEquals("0.2.4-test", markup.getString("appVersion"))
        assertEquals("TestDevice", markup.getString("deviceModel"))
        val pages = markup.getJSONArray("pages")
        assertEquals(2, pages.length())
        val first = pages.getJSONObject(0)
        assertEquals(11, first.getInt("pageId"))
        assertEquals("page-001.jpg", first.getString("file"))
        assertEquals("page-001-raw.jpg", first.getString("rawFile"))
        assertEquals(8, first.getJSONArray("autoCorners").length())
        assertTrue(first.isNull("refinedCorners"))
        assertEquals("document", first.getString("filter"))
        val second = pages.getJSONObject(1)
        assertTrue(second.isNull("rawFile"))
        assertTrue(second.isNull("autoCorners"))
        assertEquals(0, second.getInt("cornerSchema"))
    }

    @Test
    fun `entry names sort by position`() {
        assertEquals("page-001.jpg", DebugArchive.pageEntryName(0))
        assertEquals("page-010.jpg", DebugArchive.pageEntryName(9))
        assertEquals("page-001-raw.jpg", DebugArchive.rawEntryName(0))
    }
}
