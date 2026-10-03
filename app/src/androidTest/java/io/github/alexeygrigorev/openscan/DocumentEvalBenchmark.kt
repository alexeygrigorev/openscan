package io.github.alexeygrigorev.openscan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexeygrigorev.openscan.scan.PdfExporter
import io.github.alexeygrigorev.openscan.scan.ScanPipeline
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
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
        val poolsDir = File(outDir, "pools").apply { mkdirs() }

        val results = JSONArray()
        val pagesByDoc = LinkedHashMap<String, MutableList<File>>()
        val tAll = System.currentTimeMillis()

        val n = min(images.length(), maxImages)
        for (i in 0 until n) {
            val entry = images.getJSONObject(i)
            val name = entry.getString("file")
            val doc = entry.optString("doc", "single")
            val record = JSONObject().put("file", name).put("doc", doc)
            ScanPipeline.quadDebugSink = { pool ->
                File(poolsDir, "$name.pool.txt").writeText(pool.joinToString("\n") { it.toString() })
            }
            try {
                val src = decode(File("/data/local/tmp/openscan-eval/images", name), 2200)
                    ?: error("undecodable image")
                record.put("srcW", src.width).put("srcH", src.height)
                val t0 = System.currentTimeMillis()
                val quad = ScanPipeline.detectQuad(src)
                record.put("detectMs", System.currentTimeMillis() - t0)
                if (quad == null) {
                    record.put("ok", false).put("error", "no quad found")
                } else {
                    record.put("ok", true)
                    record.put("quad", JSONArray(quad.map { it.roundToInt() }))
                    val crop = ScanPipeline.crop(src, quad)
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
        ScanPipeline.quadDebugSink = null
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

