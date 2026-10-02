package io.github.alexeygrigorev.scanlet.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Bitmap decode/save helpers shared by import, thumbnails, rotation and export. */
object Images {

    /**
     * Decodes [source] (file path) downscaled so that the longest edge is at
     * most [maxDim]. Region-unsafe but memory-safe: inSampleSize keeps the
     * peak allocation proportional to the output size.
     */
    fun decodeScaled(source: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        }
        return BitmapFactory.decodeFile(source.absolutePath, options)
    }

    fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxDim) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    fun saveJpeg(bitmap: Bitmap, dest: File, quality: Int = 90) {
        FileOutputStream(dest).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
    }
}

/**
 * Copies a source image (content:// URI from the ML Kit scanner result or the
 * photo picker) into the app's private storage as a page JPEG.
 */
class PageImporter(private val context: Context) {

    fun import(source: Uri, dest: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IllegalStateException("cannot open $source")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("not a decodable image: $source")
        }

        val maxDim = 2560
        val options = BitmapFactory.Options().apply {
            inSampleSize = Images.sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        }
        val bitmap = context.contentResolver.openInputStream(source)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IllegalStateException("cannot decode $source")

        Images.saveJpeg(bitmap, dest)
        bitmap.recycle()
        check(dest.length() > 0) { "empty page file: $dest" }
    }
}
