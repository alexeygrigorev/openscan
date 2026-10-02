package io.github.alexeygrigorev.openscan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexeygrigorev.openscan.scan.PdfExporter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Corner-detection + PDF-quality benchmark over a corpus of real document
 * photos pushed by the host to /data/local/tmp/openscan-eval (index.json
 * lists the images and their document grouping).
 *
 * The production scanner (ML Kit document scanner) needs a signed-in Play
 * store for its on-demand module, which emulators don't have — see
 * docs/EVALUATION.md. This harness therefore measures the full pipeline
 * geometry with a test-only OpenCV detector (Canny edges → external contours
 * → convex quad approximation → perspective crop), then the app's real
 * [PdfExporter.exportPdf] for every document group. Per-image overlays,
 * crops, PDFs and a results.json land in filesDir/eval for the host to pull.
 */
@RunWith(AndroidJUnit4::class)
class DocumentEvalBenchmark {

    private val indexFile = "/data/local/tmp/openscan-eval/index.json"
    private val maxImages = 120

    @Test
    fun runBenchmark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val index = JSONObject(File(indexFile).readText())
        val images = index.getJSONArray("images")

        val outDir = File(context.filesDir, "eval").apply { deleteRecursively() }
        val cropsDir = File(outDir, "crops").apply { mkdirs() }
        val overlaysDir = File(outDir, "overlays").apply { mkdirs() }
        val pdfsDir = File(outDir, "pdfs").apply { mkdirs() }

        val results = JSONArray()
        val pagesByDoc = LinkedHashMap<String, MutableList<File>>()
        val tAll = System.currentTimeMillis()

        val n = min(images.length(), maxImages)
        for (i in 0 until n) {
            val entry = images.getJSONObject(i)
            val name = entry.getString("file")
            val doc = entry.optString("doc", "single")
            val record = JSONObject().put("file", name).put("doc", doc)
            try {
                val src = decode(File("/data/local/tmp/openscan-eval/images", name), 2200)
                    ?: error("undecodable image")
                record.put("srcW", src.width).put("srcH", src.height)
                val t0 = System.currentTimeMillis()
                val quad = QuadDetector.detect(src)
                record.put("detectMs", System.currentTimeMillis() - t0)
                if (quad == null) {
                    record.put("ok", false).put("error", "no quad found")
                } else {
                    record.put("ok", true)
                    record.put("quad", JSONArray(quad.map { it.roundToInt() }))
                    val crop = Perspective.warpToRect(src, quad)
                    val cropFile = File(cropsDir, "${name.substringBeforeLast('.')}_crop.jpg")
                    saveJpeg(crop, cropFile)
                    pagesByDoc.getOrPut(doc) { mutableListOf() }.add(cropFile)
                    saveJpeg(drawOverlay(src, quad), File(overlaysDir, name))
                    crop.recycle()
                }
                src.recycle()
            } catch (e: Exception) {
                record.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            results.put(record)
        }

        // One PDF per document group through the app's real exporter.
        val pdfs = JSONArray()
        pagesByDoc.forEach { (doc, pages) ->
            try {
                val pdfFile = File(pdfsDir, "$doc.pdf")
                FileOutputStream(pdfFile).use { PdfExporter.exportPdf(pages, it) }
                pdfs.put(JSONObject().put("doc", doc).put("pages", pages.size)
                    .put("file", pdfFile.name).put("bytes", pdfFile.length()))
            } catch (e: Exception) {
                pdfs.put(JSONObject().put("doc", doc).put("error", e.message))
            }
        }

        File(outDir, "results.json").writeText(
            JSONObject()
                .put("images", results)
                .put("pdfs", pdfs)
                .put("totalMs", System.currentTimeMillis() - tAll)
                .toString()
        )
    }

    private fun decode(source: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / 2 >= maxDim) { sample *= 2; longest /= 2 }
        return BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun saveJpeg(bitmap: Bitmap, dest: File) {
        FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    private fun drawOverlay(src: Bitmap, quad: FloatArray): Bitmap {
        val scale = 1024f / maxOf(src.width, src.height)
        val small = Bitmap.createScaledBitmap(
            src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true
        )
        val canvas = Canvas(small)
        val line = Paint().apply { color = Color.GREEN; strokeWidth = 5f; style = Paint.Style.STROKE }
        val dot = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
        val ordered = quad.toList().chunked(2).map { (x, y) -> x * scale to y * scale }
        ordered.zip(ordered.drop(1) + ordered.take(1)).forEach { (a, b) ->
            canvas.drawLine(a.first, a.second, b.first, b.second, line)
        }
        ordered.forEach { (x, y) -> canvas.drawCircle(x, y, 9f, dot) }
        return small
    }
}

/** Paper-mask quad detection, self-contained so the benchmark needs no GMS and no native libs. */
internal object QuadDetector {

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
internal object Perspective {

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

