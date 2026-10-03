package io.github.alexeygrigorev.openscan.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The app's independently testable scan component: bitmap in → document quad
 * + perspective-warped bitmap out (picture in → scanned picture out).
 * Self-contained OpenCV-only detection/warping with no GMS dependency; used
 * by the androidTest eval benchmark (DocumentEvalBenchmark) to measure the
 * full pipeline geometry over a corpus of real document photos.
 */
object ScanPipeline {

    /** One scan: the detected document quad and the warped, cropped page bitmap. */
    data class ScanResult(val quad: FloatArray, val cropped: Bitmap)

    /** Returns TL, TR, BR, BL corner coordinates in source-bitmap space, or null. */
    fun detectQuad(src: Bitmap): FloatArray? = QuadDetector.detect(src)

    /** Perspective-warps [src] through [quad] (TL, TR, BR, BL) into a rect bitmap. */
    fun crop(src: Bitmap, quad: FloatArray): Bitmap = Perspective.warpToRect(src, quad)

    /** Convenience: detect + crop, or null if no document is found. */
    fun scan(src: Bitmap): ScanResult? {
        val quad = detectQuad(src) ?: return null
        return ScanResult(quad, crop(src, quad))
    }
}

/** Paper-mask quad detection, self-contained so the benchmark needs no GMS and no native libs. */
private object QuadDetector {

    /** Returns TL, TR, BR, BL corner coordinates in source-bitmap space, or null. */
    fun detect(src: Bitmap): FloatArray? {
        if (!OpenCVLoader.initLocal()) error("OpenCV native library failed to load")
        val mat = Mat()
        org.opencv.android.Utils.bitmapToMat(src, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
        val scale = 900.0 / maxOf(src.width, src.height)
        val small = Mat()
        Imgproc.resize(mat, small, Size(src.width * scale, src.height * scale))
        mat.release()
        Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(small, edges, 40.0, 120.0)
        Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val quad = largestQuad(contours, small.size().area())
        small.release(); edges.release()
        val pts = quad ?: return null
        return FloatArray(8) { d -> (pts[d] / scale).toFloat() }
    }

    /** Classic recipe: biggest external contour that approximates to a convex quad. */
    private fun largestQuad(contours: List<MatOfPoint>, frameArea: Double): DoubleArray? {
        val byArea = contours.sortedByDescending { Imgproc.contourArea(it) }
            .take(12)
            .filter { Imgproc.contourArea(it) > 0.015 * frameArea }
        if (byArea.isEmpty()) return null

        // One quad candidate per contour, scored by solidity: a real document
        // contour fills its approximated quad almost completely, while spiky
        // background outlines leave most of their quad empty. Full-page papers
        // and small cards both win on area x fill without any frame priors.
        var best: DoubleArray? = null
        var bestScore = 0.0
        for (contour in byArea) {
            val contourArea = Imgproc.contourArea(contour)
            val points = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(points, true)
            for (eps in intArrayOf(1, 2, 3, 5, 8)) {
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(points, approx, eps * 0.01 * peri, true)
                if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                    val q = orderCorners(approx.toArray().map { pt -> pt.x to pt.y })
                    val quadArea = polyArea(q)
                    val fill = contourArea / quadArea
                    val score = quadArea * fill * fill * fill * fill
                    if (score > bestScore) { bestScore = score; best = q }
                    break
                }
                approx.release()
            }
            points.release()
        }
        best?.let { return it }

        // Fallback: rotated bounding rect for documents with curved or
        // cluttered edges that never reduce to 4 points — restricted to
        // contours comparable to the dominant one so an inner detail (the
        // face photo on an ID card) is never mistaken for the document.
        val dominant = Imgproc.contourArea(byArea.first())
        for (contour in byArea) {
            if (Imgproc.contourArea(contour) < 0.3 * dominant) continue
            val rot = Imgproc.minAreaRect(MatOfPoint2f(*contour.toArray()))
            if (rot.size.width < 1 || rot.size.height < 1) continue
            val pts = Array(4) { org.opencv.core.Point() }
            rot.points(pts)
            return orderCorners(pts.map { it.x to it.y })
        }
        return null
    }

    private fun polyArea(q: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
        }
        return kotlin.math.abs(s) / 2
    }

    /** Rotates the quad so it starts at the TL corner (min x+y), keeping cyclic order. */
    private fun orderCorners(pts: List<Pair<Double, Double>>): DoubleArray {
        val cx = pts.map { it.first }.average()
        val cy = pts.map { it.second }.average()
        val sorted = pts.sortedBy { atan2(it.second - cy, it.first - cx) }
        val startIdx = sorted.indices.minBy { sorted[it].first + sorted[it].second }
        val ordered = List(4) { k -> sorted[(startIdx + k) % 4] }
        return DoubleArray(8) { i -> if (i % 2 == 0) ordered[i / 2].first else ordered[i / 2].second }
    }
}

/** Four-point perspective correction: rect destination ← quad source, bilinear sampling. */
private object Perspective {

    fun warpToRect(src: Bitmap, quad: FloatArray, maxEdge: Int = 1600): Bitmap {
        val top = dist(quad[0], quad[1], quad[2], quad[3])
        val bottom = dist(quad[6], quad[7], quad[4], quad[5])
        val left = dist(quad[0], quad[1], quad[6], quad[7])
        val right = dist(quad[2], quad[3], quad[4], quad[5])
        val outW = max(32, min(maxEdge, max(top, bottom).roundToInt()))
        val outH = max(32, min(maxEdge, max(left, right).roundToInt()))

        // Solve the homography mapping destination rect corners → source quad.
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val hgt = homography(dst, quad) ?: return fallbackStretch(src, quad, outW, outH)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(outW * outH)
        val srcPixels = IntArray(src.width * src.height)
        src.getPixels(srcPixels, 0, src.width, 0, 0, src.width, src.height)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val denom = hgt[6] * x + hgt[7] * y + 1f
                val sx = (hgt[0] * x + hgt[1] * y + hgt[2]) / denom
                val sy = (hgt[3] * x + hgt[4] * y + hgt[5]) / denom
                pixels[y * outW + x] = sampleBilinear(srcPixels, src.width, src.height, sx, sy)
            }
        }
        out.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return out
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat()

    private fun fallbackStretch(src: Bitmap, quad: FloatArray, outW: Int, outH: Int): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = android.graphics.Path().apply {
            moveTo(quad[0], quad[1]); lineTo(quad[2], quad[3]); lineTo(quad[4], quad[5]); lineTo(quad[6], quad[7]); close()
        }
        canvas.clipPath(path)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    /** Exact 8-parameter solve for 4 correspondences (Gauss with partial pivot). */
    private fun homography(src: FloatArray, dst: FloatArray): FloatArray? {
        val a = FloatArray(8 * 9)
        for (i in 0 until 4) {
            val xs = src[2 * i]; val ys = src[2 * i + 1]
            val xd = dst[2 * i]; val yd = dst[2 * i + 1]
            val r1 = i * 18
            // Two rows per correspondence.
            val rows = listOf(
                floatArrayOf(xs, ys, 1f, 0f, 0f, 0f, -xd * xs, -xd * ys, xd),
                floatArrayOf(0f, 0f, 0f, xs, ys, 1f, -yd * xs, -yd * ys, yd),
            )
            rows.forEachIndexed { k, row -> System.arraycopy(row, 0, a, r1 + k * 9, 9) }
        }
        // Gaussian elimination.
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) if (abs(a[r * 9 + col]) > abs(a[pivot * 9 + col])) pivot = r
            if (abs(a[pivot * 9 + col]) < 1e-6f) return null
            if (pivot != col) for (c in 0 until 9) { val t = a[col * 9 + c]; a[col * 9 + c] = a[pivot * 9 + c]; a[pivot * 9 + c] = t }
            for (r in 0 until 8) {
                if (r == col) continue
                val f = a[r * 9 + col] / a[col * 9 + col]
                if (f == 0f) continue
                for (c in col until 9) a[r * 9 + c] -= f * a[col * 9 + c]
            }
        }
        return FloatArray(8) { i -> a[i * 9 + 8] / a[i * 9 + i] }
    }

    private fun sampleBilinear(pixels: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xf = x.coerceIn(0f, (w - 1).toFloat())
        val yf = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = xf - x0; val fy = yf - y0
        val c00 = pixels[y0 * w + x0]; val c10 = pixels[y0 * w + x1]
        val c01 = pixels[y1 * w + x0]; val c11 = pixels[y1 * w + x1]
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) { // R, G, B — alpha assumed opaque
            val v = (c00 shr shift and 0xFF) * (1 - fx) * (1 - fy) +
                (c10 shr shift and 0xFF) * fx * (1 - fy) +
                (c01 shr shift and 0xFF) * (1 - fx) * fy +
                (c11 shr shift and 0xFF) * fx * fy
            out = out or (v.roundToInt().coerceIn(0, 255) shl shift)
        }
        return out or 0xFF000000.toInt()
    }
}
