package io.github.alexeygrigorev.openscan.scan

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Re-detect corners on already-saved pages (item 11): runs the current
 * detection + refinement ([CaptureRefine]) over the stored original frame —
 * or the stored page itself when no original was kept — and re-warps when
 * the result is a genuine, plausible crop. The caller decides file/DB
 * updates; this object only computes. Bitmap-touching [redetect] runs on
 * device; [isFullFrame] is pure and unit-tested.
 */
object PageRedetect {

    /** Decode bound for the re-detect source (matches the capture stills). */
    const val REDETECT_MAX_DIM = 3200

    /**
     * A quad covering this much of the frame (or more) is "no crop": the
     * page is already full-bleed and re-warping would only resample noise.
     */
    const val FULL_FRAME_FRAC = 0.97

    data class Result(
        /** New cropped page, or null to keep the existing crop. */
        val bitmap: Bitmap?,
        val auto: FloatArray?,
        val refined: FloatArray?,
        /** Quad coordinate space = the source dims. */
        val width: Int,
        val height: Int,
        val cropChanged: Boolean,
        val needsCheck: Boolean,
    )

    fun redetect(source: Bitmap): Result {
        val w = source.width
        val h = source.height
        val refined = runCatching { CaptureRefine.refine(source, null) }.getOrNull()
        val quad = refined?.quad
        if (quad == null) {
            return Result(
                bitmap = null, auto = refined?.initial, refined = refined?.refined,
                width = w, height = h, cropChanged = false, needsCheck = true,
            )
        }
        if (isFullFrame(quad, w, h)) {
            return Result(
                bitmap = null, auto = refined.initial, refined = refined.refined,
                width = w, height = h, cropChanged = false,
                needsCheck = refined.needsCheck,
            )
        }
        val warped = runCatching { ScanPipeline.crop(source, quad) }.getOrNull()
        if (warped == null) {
            return Result(
                bitmap = null, auto = refined.initial, refined = refined.refined,
                width = w, height = h, cropChanged = false, needsCheck = true,
            )
        }
        return Result(
            bitmap = warped, auto = refined.initial, refined = refined.refined,
            width = w, height = h, cropChanged = true,
            needsCheck = refined.needsCheck,
        )
    }

    /** True when [quad] covers nearly the whole frame (nothing to crop). */
    fun isFullFrame(quad: FloatArray, frameWidth: Int, frameHeight: Int): Boolean {
        if (quad.size != 8 || frameWidth <= 0 || frameHeight <= 0) return false
        var area = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += quad[2 * i].toDouble() * quad[2 * j + 1] -
                quad[2 * j].toDouble() * quad[2 * i + 1]
        }
        return abs(area) / 2.0 / (frameWidth.toDouble() * frameHeight) >= FULL_FRAME_FRAC
    }
}
