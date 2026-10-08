package io.github.alexeygrigorev.openscan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexeygrigorev.openscan.data.DocumentsRepository
import io.github.alexeygrigorev.openscan.data.SettingsRepository
import io.github.alexeygrigorev.openscan.scan.Images
import io.github.alexeygrigorev.openscan.scan.ScanPipeline
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One page of an in-progress batch capture: the full camera frame on disk
 * (cache dir) plus the document quad detected on it, in frame coordinates
 * (TL, TR, BR, BL). [quad] is null when detection failed — the page still
 * ships as the raw frame and the user corrects it in the review editor.
 * [version] bumps on every quad/rotation edit so preview thumbnails can
 * invalidate their cache.
 */
data class CapturedPage(
    val id: Long,
    val file: File,
    val frameWidth: Int,
    val frameHeight: Int,
    val quad: FloatArray?,
    val rotation: Int = 0,
    val version: Int = 0,
) {
    override fun equals(other: Any?) =
        other is CapturedPage && other.id == id && other.version == version

    override fun hashCode() = (id * 31 + version).toInt()
}

/** Latest viewfinder detection, used for the overlay and the stability UI. */
data class FrameDetection(
    val quad: FloatArray?,
    val frameWidth: Int,
    val frameHeight: Int,
    val stable: Boolean,
)

/**
 * State machine behind the foss batch capture (src/foss CaptureRoute):
 * the camera streams analysis frames in via [onAnalysisFrame], pages are
 * captured automatically once the detector holds a stable document quad
 * (manual shutter always available), and the user keeps flipping pages until
 * Stop. Stop moves to the review phase, where every page can be corrected —
 * drag the quad corners, re-detect, rotate, delete — before [save] warps
 * the survivors through their final quads into a document.
 */
class BatchScanViewModel(
    private val appendTo: Long?,
    private val repository: DocumentsRepository,
    private val settings: SettingsRepository,
    context: Context,
) : ViewModel() {

    enum class Phase { CAPTURE, REVIEW }

    val phase = MutableStateFlow(Phase.CAPTURE)
    val pages = MutableStateFlow<List<CapturedPage>>(emptyList())
    val pageCount = MutableStateFlow(0)
    val detection = MutableStateFlow<FrameDetection?>(null)

    /** Bumped once per captured page: the UI flashes and buzzes on change. */
    val snapTick = MutableStateFlow(0)

    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** (page index, total) while saving; null when idle. */
    val saveProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    fun dismissError() {
        error.value = null
    }

    /** Set by the UI: fires the camera's ImageCapture use case. */
    var captureShutter: (() -> Unit)? = null

    private val batchDir: File = File(context.cacheDir, "batch-capture").apply { mkdirs() }
    private var nextId = 1L

    // --- capture trigger state (detect thread + main) ---

    private val detectExecutor = Executors.newSingleThreadExecutor()
    private val detectContext: CoroutineContext = detectExecutor.asCoroutineDispatcher()

    private val analyzing = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    private var lastAnalysisAt = 0L
    private var lastQuad: FloatArray? = null
    private var lastQuadFrame: Pair<Int, Int>? = null
    private var stableCount = 0
    private var lostSinceLastCapture = true
    private var lastCaptureAt = 0L
    private var lastCapturedQuad: FloatArray? = null
    private var lastCapturedFrame: Pair<Int, Int>? = null

    private val thumbCache = object : LruCache<Long, ImageBitmap>(64) {}

    // Cheap gate the analyzer consults before decoding a frame at all.
    fun wantsFrame(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean =
        phase.value == Phase.CAPTURE && !capturing.get() &&
            nowElapsed - lastAnalysisAt >= ANALYSIS_INTERVAL_MS

    /**
     * Upright analysis frame from the camera. Runs detection on the detect
     * thread; when the same quad has held long enough since the last capture,
     * fires the shutter again — the user just keeps flipping pages.
     */
    fun onAnalysisFrame(bitmap: Bitmap, nowElapsed: Long = SystemClock.elapsedRealtime()) {
        lastAnalysisAt = nowElapsed
        if (!analyzing.compareAndSet(false, true)) return
        viewModelScope.launch(detectContext) {
            try {
                val quad = runCatching { ScanPipeline.detectQuad(bitmap) }.getOrNull()
                val stable = quad != null && isStable(quad, lastQuad, bitmap.width, bitmap.height)
                lastQuad = quad
                lastQuadFrame = bitmap.width to bitmap.height
                if (quad == null) lostSinceLastCapture = true
                stableCount = if (stable) stableCount + 1 else 0
                val stableEnough = stableCount >= STABLE_FRAMES
                detection.value = FrameDetection(quad, bitmap.width, bitmap.height, stableEnough)
                val cooldownOver = nowElapsed - lastCaptureAt >= CAPTURE_COOLDOWN_MS
                val sceneChanged = lostSinceLastCapture ||
                    movedEnough(
                        quad, lastQuadFrame,
                        lastCapturedQuad, lastCapturedFrame,
                    )
                if (stableEnough && cooldownOver && sceneChanged &&
                    pages.value.size < AUTO_CAPTURE_MAX_PAGES
                ) {
                    requestCapture()
                }
            } finally {
                bitmap.recycle()
                analyzing.set(false)
            }
        }
    }

    /** Manual shutter or auto trigger: one full-res still through the pipeline. */
    fun requestCapture() {
        if (phase.value != Phase.CAPTURE || busy.value) return
        if (!capturing.compareAndSet(false, true)) return
        if (pages.value.size >= HARD_MAX_PAGES) {
            capturing.set(false)
            error.value = "Page limit reached (${HARD_MAX_PAGES}). Finish the batch to continue."
            return
        }
        lastCaptureAt = SystemClock.elapsedRealtime()
        captureShutter?.invoke() ?: capturing.set(false)
    }

    /** Full-res upright still from ImageCapture; detection runs on it again. */
    fun onStillCaptured(stillFull: Bitmap) {
        viewModelScope.launch(detectContext) {
            try {
                // Bound the stored frame like every other page import.
                val still = downscaleMax(stillFull, SAVE_MAX_DIM)
                val quad = runCatching { ScanPipeline.detectQuad(still) }.getOrNull()
                    ?: scaleQuad(lastQuad, lastQuadFrame, still.width, still.height)
                val file = File(batchDir, "page-${nextId}.jpg")
                Images.saveJpeg(still, file)
                pages.value = pages.value + CapturedPage(
                    id = nextId++,
                    file = file,
                    frameWidth = still.width,
                    frameHeight = still.height,
                    quad = quad,
                )
                pageCount.value = pages.value.size
                lastCapturedQuad = quad
                lastCapturedFrame = still.width to still.height
                lostSinceLastCapture = false
                snapTick.value += 1
            } catch (t: Throwable) {
                error.value = t.message ?: "Could not save the page"
            } finally {
                capturing.set(false)
            }
        }
    }

    private fun downscaleMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    fun onCaptureFailed(t: Throwable) {
        error.value = t.message ?: "Capture failed"
        capturing.set(false)
    }

    // --- phase + review edits ---

    fun stop() {
        if (pages.value.isNotEmpty()) phase.value = Phase.REVIEW
    }

    fun resumeCapture() {
        phase.value = Phase.CAPTURE
        lostSinceLastCapture = true
    }

    fun deletePage(id: Long) {
        val page = pages.value.firstOrNull { it.id == id } ?: return
        page.file.delete()
        pages.value = pages.value.filterNot { it.id == id }
        pageCount.value = pages.value.size
        thumbCache.remove(id)
    }

    fun rotatePage(id: Long) = editPage(id) { it.copy(rotation = (it.rotation + 1) % 4) }

    fun setQuad(id: Long, quad: FloatArray) = editPage(id) { it.copy(quad = quad) }

    fun clearQuad(id: Long) = editPage(id) { it.copy(quad = null) }

    /** Re-runs the detector on the stored frame (review editor's Auto button). */
    fun autoDetect(id: Long) {
        val page = pages.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val decoded = Images.decodeScaled(page.file, AUTODETECT_MAX_DIM)
                ?: return@launch
            val quad = runCatching { ScanPipeline.detectQuad(decoded) }.getOrNull()
                ?.let { scaleQuad(it, decoded.width to decoded.height, page.frameWidth, page.frameHeight) }
            decoded.recycle()
            if (quad != null) setQuad(id, quad)
        }
    }

    private fun editPage(id: Long, edit: (CapturedPage) -> CapturedPage) {
        pages.value = pages.value.map {
            if (it.id == id) edit(it).let { p -> p.copy(version = p.version + 1) } else it
        }
        thumbCache.remove(id)
    }

    // --- previews ---

    /** Warped (and rotated) page preview for the review grid, cached by page version. */
    suspend fun thumbnail(page: CapturedPage): ImageBitmap? {
        thumbCache.get(page.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            renderPage(page, THUMB_MAX_DIM)?.asImageBitmap()?.also { thumbCache.put(page.id, it) }
        }
    }

    /** Full-frame bitmap for the review editor, downscaled to [maxDim]. */
    suspend fun editorFrame(page: CapturedPage, maxDim: Int = EDITOR_MAX_DIM): Bitmap? =
        withContext(Dispatchers.IO) { Images.decodeScaled(page.file, maxDim) }

    // --- save ---

    /** Warps every kept page through its final quad into the document. */
    fun save(onDone: (documentId: Long, added: Int, failed: Int) -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                // The target may have been deleted while capturing; fall back
                // to a fresh document rather than orphan the pages.
                val documentId = appendTo
                    ?.takeIf { repository.getDocument(it) != null }
                    ?: repository.createDocument()
                val list = pages.value
                var failed = 0
                // Read once: the original frames move into permanent storage
                // (keyed by page id) unless the user opted out of keeping them.
                val keepOriginals = runCatching { settings.keepOriginalsEnabled.first() }
                    .getOrDefault(true)
                list.forEachIndexed { index, page ->
                    saveProgress.value = index to list.size
                    try {
                        val source = Images.decodeScaled(page.file, SAVE_MAX_DIM)
                            ?: throw IllegalStateException("cannot decode ${page.file.name}")
                        val rendered = renderPageInto(source, page)
                        repository.importPageBitmap(
                            documentId,
                            rendered,
                            detected = true,
                            original = if (keepOriginals) page.file else null,
                        )
                        rendered.recycle()
                        if (rendered !== source) source.recycle()
                    } catch (_: Throwable) {
                        failed++
                    }
                }
                busy.value = false
                saveProgress.value = null
                batchDir.deleteRecursively()
                onDone(documentId, list.size - failed, failed)
            } catch (t: Throwable) {
                busy.value = false
                saveProgress.value = null
                error.value = t.message ?: "Could not create the document"
            }
        }
    }

    fun discard() {
        batchDir.deleteRecursively()
    }

    /** Warped/rotated page at [maxDim]; the raw frame when no quad is set. */
    private suspend fun renderPage(page: CapturedPage, maxDim: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            val source = Images.decodeScaled(page.file, maxDim) ?: return@withContext null
            renderPageInto(source, page)
        }

    private fun renderPageInto(source: Bitmap, page: CapturedPage): Bitmap {
        val warped = page.quad
            ?.let { scaleQuad(it, page.frameWidth to page.frameHeight, source.width, source.height) }
            ?.let { runCatching { ScanPipeline.crop(source, it) }.getOrNull() }
            ?: source
        if (page.rotation == 0) return warped
        val matrix = Matrix().apply { postRotate(90f * page.rotation) }
        val rotated = Bitmap.createBitmap(warped, 0, 0, warped.width, warped.height, matrix, true)
        if (rotated !== warped && warped !== source) warped.recycle()
        return rotated
    }

    override fun onCleared() {
        // Files live in cacheDir; after save() the dir is already gone and
        // this is a no-op. Abandoned batches are wiped here.
        batchDir.deleteRecursively()
        detectExecutor.shutdown()
    }

    // --- quad geometry helpers ---

    /** Same quad (cornerwise) across consecutive frames, within [STABLE_DELTA]. */
    private fun isStable(q: FloatArray?, last: FloatArray?, w: Int, h: Int): Boolean {
        if (q == null || last == null) return false
        val norm = hypot(w.toDouble(), h.toDouble())
        for (i in 0 until 4) {
            val dx = (q[i * 2] - last[i * 2]).toDouble()
            val dy = (q[i * 2 + 1] - last[i * 2 + 1]).toDouble()
            if (hypot(dx, dy) / norm > STABLE_DELTA) return false
        }
        return areaFrac(q, w, h) >= MIN_AREA_FRAC
    }

    /**
     * A new page placement: the quad visibly moved, or detection dropped out
     * in between (the hand turning the page). Small jitter alone does not
     * re-arm, so a page left under the camera is not captured twice.
     */
    private fun movedEnough(
        q: FloatArray?,
        qFrame: Pair<Int, Int>?,
        prev: FloatArray?,
        prevFrame: Pair<Int, Int>?,
    ): Boolean {
        if (q == null || qFrame == null || prev == null || prevFrame == null) return true
        val norm = hypot(qFrame.first.toDouble(), qFrame.second.toDouble())
        var maxDelta = 0.0
        for (i in 0 until 4) {
            val dx = q[i * 2].toDouble() / qFrame.first - prev[i * 2].toDouble() / prevFrame.first
            val dy = q[i * 2 + 1].toDouble() / qFrame.second - prev[i * 2 + 1].toDouble() / prevFrame.second
            maxDelta = max(maxDelta, hypot(dx, dy))
        }
        return maxDelta / norm > RESNAP_DELTA
    }

    private fun areaFrac(q: FloatArray, w: Int, h: Int): Float {
        var a = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            a += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return abs(a) / 2f / (w * h)
    }

    /** Rescales a quad from one frame size to another (null-safe). */
    fun scaleQuad(
        quad: FloatArray?,
        from: Pair<Int, Int>?,
        toW: Int,
        toH: Int,
    ): FloatArray? {
        if (quad == null || from == null || from.first == 0 || from.second == 0) return null
        val sx = toW.toFloat() / from.first
        val sy = toH.toFloat() / from.second
        return FloatArray(8) { i -> if (i % 2 == 0) quad[i] * sx else quad[i] * sy }
    }

    companion object {

        /** Detection cadence on the analysis stream. */
        const val ANALYSIS_INTERVAL_MS = 220L

        /** Consecutive agreeing frames before an auto snap. */
        const val STABLE_FRAMES = 2

        /** Max corner drift between frames that still counts as "held". */
        const val STABLE_DELTA = 0.035

        /** Document must fill at least this frame fraction to auto-snap. */
        const val MIN_AREA_FRAC = 0.08f

        /** Min quiet period between auto snaps. */
        const val CAPTURE_COOLDOWN_MS = 1_400L

        /** Corner move (frame fraction) that re-arms the trigger without a dropout. */
        const val RESNAP_DELTA = 0.015

        /** Auto capture stops here; the manual shutter keeps working a while longer. */
        const val AUTO_CAPTURE_MAX_PAGES = 50

        /** Hard cap, mirroring the gallery picker's 100-image import. */
        const val HARD_MAX_PAGES = 100

        const val THUMB_MAX_DIM = 720
        const val EDITOR_MAX_DIM = 2048
        const val AUTODETECT_MAX_DIM = 1600
        const val SAVE_MAX_DIM = 2560
    }
}
