package io.github.alexeygrigorev.openscan.scan

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Developer-mode debug archive (item 1): a ZIP with the raw (unprocessed)
 * photos plus the markup (detected corners, refined corners, user-corrected
 * corners, image sizes, app version, device model) as JSON. The stored page
 * JPEG always ships; the raw camera frame ships when keep-originals kept it.
 * Pure java.util.zip/org.json — unit-tested with temp files.
 */
object DebugArchive {

    /** Schema version of markup.json; bump with format changes. */
    const val SCHEMA_VERSION = 1

    data class Entry(
        val pageId: Long,
        val position: Int,
        /** Stored (warped) page JPEG. */
        val pageFile: File,
        /** Raw camera frame, or null when not kept. */
        val rawFile: File?,
        /** Quad coordinate-space dims (frame size when corners are known). */
        val imageWidth: Int,
        val imageHeight: Int,
        /** Stored page dims (0 when unknown). */
        val storedWidth: Int,
        val storedHeight: Int,
        val autoCorners: FloatArray?,
        val refinedCorners: FloatArray?,
        val userCorners: FloatArray?,
        val cornerSchema: Int,
        val filterId: String?,
    )

    fun pageEntryName(position: Int): String = "page-%03d.jpg".format(position + 1)

    fun rawEntryName(position: Int): String = "page-%03d-raw.jpg".format(position + 1)

    /**
     * Writes the archive: one stored JPEG per page, raw frames when present,
     * and markup.json describing every page. Never stamps anything.
     */
    fun build(
        entries: List<Entry>,
        documentTitle: String,
        appVersion: String,
        deviceModel: String,
        exportedAt: Long,
        out: OutputStream,
    ) {
        ZipOutputStream(out).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(pageEntryName(entry.position)))
                entry.pageFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                entry.rawFile?.takeIf { it.exists() }?.let { raw ->
                    zip.putNextEntry(ZipEntry(rawEntryName(entry.position)))
                    raw.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("markup.json"))
            zip.write(markup(entries, documentTitle, appVersion, deviceModel, exportedAt).toByteArray())
            zip.closeEntry()
        }
    }

    /** The markup document; visible for tests. */
    fun markup(
        entries: List<Entry>,
        documentTitle: String,
        appVersion: String,
        deviceModel: String,
        exportedAt: Long,
    ): String {
        val pages = JSONArray()
        for (entry in entries) {
            pages.put(
                JSONObject()
                    .put("pageId", entry.pageId)
                    .put("position", entry.position)
                    .put("file", pageEntryName(entry.position))
                    .put("rawFile", entry.rawFile?.takeIf { it.exists() }?.let { rawEntryName(entry.position) } ?: JSONObject.NULL)
                    .put("imageWidth", entry.imageWidth)
                    .put("imageHeight", entry.imageHeight)
                    .put("storedWidth", entry.storedWidth)
                    .put("storedHeight", entry.storedHeight)
                    .put("cornerSchema", entry.cornerSchema)
                    .put("autoCorners", quadOrNull(entry.autoCorners))
                    .put("refinedCorners", quadOrNull(entry.refinedCorners))
                    .put("userCorners", quadOrNull(entry.userCorners))
                    .put("filter", entry.filterId ?: ScanFilter.DEFAULT.id)
            )
        }
        return JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("documentTitle", documentTitle)
            .put("appVersion", appVersion)
            .put("deviceModel", deviceModel)
            .put("exportedAt", exportedAt)
            .put("pages", pages)
            .toString()
    }

    private fun quadOrNull(quad: FloatArray?): Any =
        if (quad != null && quad.size == 8 && quad.all { it.isFinite() }) {
            JSONArray().apply { for (v in quad) put(v.toDouble()) }
        } else {
            JSONObject.NULL
        }
}
