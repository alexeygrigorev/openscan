package io.github.alexeygrigorev.openscan.scan

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc

/**
 * Post-capture corner refinement (item 8b): after the shutter fires, a
 * slower, more accurate pass runs on the full-resolution still — a fresh
 * full-pipeline detection, a second attempt at half scale when the first
 * looks implausible, and sub-pixel corner polishing. [QuadPlausibility]
 * judges every candidate; when everything is flagged the best candidate
 * still ships but [Result.needsCheck] tells the review UI to highlight
 * "check corners".
 *
 * [refine] needs the OpenCV native library (present on device); the
 * selection itself ([pickBest]) is pure and unit-tested.
 */
object CaptureRefine {

    data class Result(
        /** The quad to store/show (null when nothing usable was found). */
        val quad: FloatArray?,
        /** The live quad the capture started from (may be null). */
        val initial: FloatArray?,
        /** The refined detector answer (null when refinement found nothing). */
        val refined: FloatArray?,
        /** True when the result is flagged and the user should verify. */
        val needsCheck: Boolean,
    )

    fun refine(still: Bitmap, initial: FloatArray?): Result {
        val w = still.width
        val h = still.height
        val fresh = runCatching { ScanPipeline.detectQuad(still) }.getOrNull()
        if (fresh != null && QuadPlausibility.check(fresh, w, h).plausible) {
            val polished = runCatching { subpixelPolish(still, fresh) }.getOrNull() ?: fresh
            return Result(
                quad = polished,
                initial = initial,
                refined = polished,
                needsCheck = !QuadPlausibility.check(polished, w, h).plausible,
            )
        }
        // Re-run at half scale: resampling shifts the edge response, so a
        // different (sometimes better) candidate pool wins the election.
        val halfW = (w / 2).coerceAtLeast(1)
        val halfH = (h / 2).coerceAtLeast(1)
        val half = Bitmap.createScaledBitmap(still, halfW, halfH, true)
        val fresh2 = try {
            runCatching { ScanPipeline.detectQuad(half) }.getOrNull()
                ?.let { scale(it, halfW, halfH, w, h) }
        } finally {
            if (half !== still) half.recycle()
        }
        val winner = pickBest(initial, fresh, fresh2, w, h)
        if (winner == null) {
            return Result(quad = null, initial = initial, refined = null, needsCheck = true)
        }
        val verdict = QuadPlausibility.check(winner, w, h)
        val polished = if (verdict.plausible) {
            runCatching { subpixelPolish(still, winner) }.getOrNull() ?: winner
        } else {
            // A flagged winner is polished no further: cornerSubPix on a
            // garbage quad wanders instead of converging.
            winner
        }
        return Result(
            quad = polished,
            initial = initial,
            refined = polished,
            needsCheck = !QuadPlausibility.check(polished, w, h).plausible,
        )
    }

    /**
     * Pure candidate selection: the first plausible quad in detector order
     * (fresh, second attempt, live), otherwise the highest-scoring fallback,
     * otherwise null. Unit-tested with synthetic quads.
     */
    fun pickBest(
        initial: FloatArray?,
        fresh: FloatArray?,
        second: FloatArray?,
        frameWidth: Int,
        frameHeight: Int,
    ): FloatArray? {
        val ordered = listOf(fresh, second, initial)
        for (candidate in ordered) {
            if (candidate != null &&
                QuadPlausibility.check(candidate, frameWidth, frameHeight).plausible
            ) {
                return candidate
            }
        }
        return ordered.filterNotNull()
            .maxByOrNull { QuadPlausibility.check(it, frameWidth, frameHeight).score }
    }

    /** Sub-pixel corner polish via cornerSubPix on the still's gray channel. */
    private fun subpixelPolish(still: Bitmap, quad: FloatArray): FloatArray? {
        if (!OpenCVLoader.initLocal()) return null
        val rgba = Mat()
        Utils.bitmapToMat(still, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val corners = MatOfPoint2f(
            *Array(4) { i -> org.opencv.core.Point(quad[2 * i].toDouble(), quad[2 * i + 1].toDouble()) }
        )
        Imgproc.cornerSubPix(
            gray, corners, Size(5.0, 5.0), Size(-1.0, -1.0),
            TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 40, 0.001),
        )
        gray.release()
        val out = FloatArray(8) { i ->
            val p = corners.toArray()[i / 2]
            val v = if (i % 2 == 0) p.x else p.y
            v.toFloat()
        }
        corners.release()
        // cornerSubPix can step outside on border corners; scans never leave
        // the picture, so clamp back into the frame.
        for (i in 0 until 4) {
            out[2 * i] = out[2 * i].coerceIn(0f, (still.width - 1).toFloat())
            out[2 * i + 1] = out[2 * i + 1].coerceIn(0f, (still.height - 1).toFloat())
        }
        return out
    }

    private fun scale(quad: FloatArray, fromW: Int, fromH: Int, toW: Int, toH: Int): FloatArray? {
        if (fromW == 0 || fromH == 0) return null
        val sx = toW.toFloat() / fromW
        val sy = toH.toFloat() / fromH
        return FloatArray(8) { i -> if (i % 2 == 0) quad[i] * sx else quad[i] * sy }
    }
}
