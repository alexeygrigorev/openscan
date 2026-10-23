package io.github.alexeygrigorev.openscan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.alexeygrigorev.openscan.scan.CaptureRefine
import io.github.alexeygrigorev.openscan.scan.EvalScore
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
 * Feedback eval (item 12): detection + post-capture refinement over a set of
 * real uploaded photos, scored against annotated ground-truth corners.
 *
 * Input (pushed by scripts/feedback-eval/run.sh, all outside git):
 * /data/local/tmp/openscan-eval/index.json entries —
 * {"file": "<name>.jpg", "doc": "<batch>", "truth": [8 numbers]?}.
 * "truth" is optional: corner annotations in source-image coordinates,
 * added by hand after verifying overlays (or, for v0.2.4+ uploads, taken
 * from the sidecar's user/refined corners).
 *
 * Output in filesDir/eval for the host to pull: results.json records
 * {file, srcW/H, detectMs, refineMs, ok, initial, refined, needsCheck,
 * meanError?, withinTolerance?} plus overlays/ (green refined quad, red
 * dots). Photos and crops never leave the private host folder.
 */
@RunWith(AndroidJUnit4::class)
class FeedbackEvalBenchmark {

    private val indexFile = "/data/local/tmp/openscan-eval/index.json"
    private val maxImages = 200

    @Test
    fun runFeedbackEval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val index = JSONObject(File(indexFile).readText())
        val images = index.getJSONArray("images")

        val outDir = File(context.filesDir, "eval").apply { deleteRecursively() }
        val overlaysDir = File(outDir, "overlays").apply { mkdirs() }

        val results = JSONArray()
        val tAll = System.currentTimeMillis()

        val n = min(images.length(), maxImages)
        for (i in 0 until n) {
            val entry = images.getJSONObject(i)
            val name = entry.getString("file")
            val doc = entry.optString("doc", "single")
            val truth = entry.optJSONArray("truth")?.let { array ->
                if (array.length() != 8) null
                else FloatArray(8) { k -> array.getDouble(k).toFloat() }
            }
            val record = JSONObject().put("file", name).put("doc", doc)
            try {
                val imageFile = File("/data/local/tmp/openscan-eval/images", name)
                val full = fullSize(imageFile)
                val src = decode(imageFile, 2200)
                    ?: error("undecodable image")
                record.put("srcW", src.width).put("srcH", src.height)
                val t0 = System.currentTimeMillis()
                val live = ScanPipeline.detectQuad(src)
                record.put("detectMs", System.currentTimeMillis() - t0)
                val t1 = System.currentTimeMillis()
                val refined = CaptureRefine.refine(src, live)
                record.put("refineMs", System.currentTimeMillis() - t1)
                val quad = refined.quad
                if (quad == null) {
                    record.put("ok", false).put("error", "no quad found")
                } else {
                    record.put("ok", true)
                    record.put("initial", live?.let { JSONArray(it.map { v -> v.roundToInt() }) } ?: JSONObject.NULL)
                    record.put("refined", JSONArray(quad.map { it.roundToInt() }))
                    record.put("needsCheck", refined.needsCheck)
                    if (truth != null) {
                        // Truth annotations are in full-res source coordinates;
                        // detection ran on the decoded (possibly downsampled)
                        // bitmap, so scale truth down to compare like-for-like.
                        val scaledTruth = if (full != null && full.first > 0 && full.first != src.width) {
                            val s = src.width.toFloat() / full.first
                            FloatArray(8) { k -> truth[k] * s }
                        } else {
                            truth
                        }
                        val error = EvalScore.meanError(quad, scaledTruth, src.width, src.height)
                        if (error != null) record.put("meanError", error)
                        record.put("withinTolerance", EvalScore.withinTolerance(quad, scaledTruth, src.width, src.height))
                    }
                    saveJpeg(drawOverlay(src, quad), File(overlaysDir, name))
                }
                src.recycle()
            } catch (e: Exception) {
                record.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            results.put(record)
        }

        File(outDir, "results.json").writeText(
            JSONObject()
                .put("images", results)
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

    private fun fullSize(source: File): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return bounds.outWidth to bounds.outHeight
    }

    private fun saveJpeg(bitmap: Bitmap, dest: File) {
        FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    }

    private fun drawOverlay(src: Bitmap, quad: FloatArray): Bitmap {
        val scale = 1024f / maxOf(src.width, src.height)
        val small = Bitmap.createScaledBitmap(
            src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true
        ).let { scaled ->
            // A same-size resize returns the immutable decoded source.
            if (scaled === src) scaled.copy(Bitmap.Config.ARGB_8888, true) else scaled
        }
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
