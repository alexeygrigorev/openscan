package io.github.alexeygrigorev.openscan.scan

import android.graphics.Bitmap

/**
 * Android seam for the enhancement filters (item 6): moves pixels between
 * [Bitmap] and the pure [FilterPixels] kernels. Bitmap allocation failures
 * (OOM on huge pages) surface as null so export can fall back to the
 * original instead of crashing.
 */
object ScanFilters {

    /** Applies [filter] to [source], returning a new bitmap (never mutates). */
    fun apply(source: Bitmap, filter: ScanFilter): Bitmap? {
        if (filter == ScanFilter.ORIGINAL) return source
        return try {
            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            val out = when (filter) {
                ScanFilter.DOCUMENT -> FilterPixels.applyDocument(pixels, w, h)
                ScanFilter.BLACK_WHITE -> FilterPixels.applyBlackWhite(pixels, w, h)
                ScanFilter.GRAYSCALE -> FilterPixels.applyGrayscale(pixels)
                ScanFilter.ORIGINAL -> pixels
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.setPixels(out, 0, w, 0, 0, w, h)
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
