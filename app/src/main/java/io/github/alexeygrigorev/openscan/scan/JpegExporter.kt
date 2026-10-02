package io.github.alexeygrigorev.openscan.scan

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JPEG export: pages are already stored as JPEGs on disk, so exporting is a
 * byte-for-byte copy — one file for a single page, a zip for many. Nothing is
 * drawn or stamped onto the image on the way out.
 */
object JpegExporter {

    fun exportSingle(pageFile: File, out: OutputStream) {
        pageFile.inputStream().use { input -> out.use { output -> input.copyTo(output) } }
    }

    fun exportZip(pageFiles: List<File>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            pageFiles.forEachIndexed { index, file ->
                zip.putNextEntry(ZipEntry(zipEntryName(index)))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Stable, sort-friendly entry names: page-001.jpg, page-002.jpg, … */
    fun zipEntryName(index: Int): String = "page-%03d.jpg".format(index + 1)
}
