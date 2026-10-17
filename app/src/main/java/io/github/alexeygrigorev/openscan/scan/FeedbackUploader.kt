package io.github.alexeygrigorev.openscan.scan

import android.os.Build
import android.util.Log
import io.github.alexeygrigorev.openscan.data.OpenScanDao
import io.github.alexeygrigorev.openscan.data.SettingsRepository
import io.github.alexeygrigorev.openscan.data.UploadEntry
import io.github.alexeygrigorev.openscan.data.UploadKind
import io.github.alexeygrigorev.openscan.data.UploadState
import java.io.File
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.min

/** Aggregate upload counts for the Settings status row. */
data class UploadStats(
    val queued: Int = 0,
    val uploading: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
) {
    companion object {
        fun from(entries: List<UploadEntry>): UploadStats = UploadStats(
            queued = entries.count { it.state == UploadState.QUEUED },
            uploading = entries.count { it.state == UploadState.UPLOADING },
            uploaded = entries.count { it.state == UploadState.UPLOADED },
            failed = entries.count { it.state == UploadState.FAILED },
        )
    }
}

/**
 * Opt-in telemetry (item 0): uploads the captured page photo plus a JSON
 * sidecar (item 5: auto/user corners and context) so the document detector
 * can be improved. The contract:
 *
 *  - uploads happen only while the user keeps the toggle on in Settings
 *    (read through [SettingsRepository]; off by default). Turning it off
 *    pauses everything immediately: the queue is left untouched and nothing
 *    is sent until the toggle comes back on;
 *  - every upload is a durable queue row first: failures survive process
 *    death, retry with backoff automatically, and stay visible in Settings
 *    (queued / uploading / uploaded / failed) with a manual retry;
 *  - capture and import are never blocked or crashed by this class.
 */
class FeedbackUploader(
    private val settings: SettingsRepository,
    private val appVersion: String,
    private val deviceModel: String = (Build.MODEL ?: "unknown"),
    private val endpoint: String = ENDPOINT,
    private val token: String = "",
    private val dao: OpenScanDao? = null,
    private val workDir: File? = null,
    private val log: (String, Throwable?) -> Unit =
        { message, cause -> Log.w(TAG, message, cause) },
    private val doUpload: (URL, File, String) -> Unit =
        { url, file, contentType -> httpPut(url, file, token, contentType) },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val draining = AtomicBoolean(false)

    /**
     * Fire-and-forget hook for the page-import path: returns immediately and
     * runs entirely on a background dispatcher.
     */
    fun maybeUpload(pageJpeg: File, detected: Boolean) {
        scope.launch { upload(pageJpeg, detected) }
    }

    /** Suspend core of [maybeUpload]; never throws. Exposed for tests. */
    suspend fun upload(pageJpeg: File, detected: Boolean) {
        try {
            if (!settings.feedbackUploadEnabled.first()) return
            val uuid = UUID.randomUUID().toString()
            val url = buildUploadUrl(endpoint, uuid, appVersion, detected) ?: return
            doUpload(url, pageJpeg, JPEG_TYPE)
        } catch (t: Throwable) {
            log("feedback upload dropped", t)
        }
    }

    /**
     * Durable page upload: records the JPEG and its JSON sidecar as queue
     * rows, then drains. Suspends only for the local enqueue (DB + sidecar
     * file) — the network never blocks the caller. No-ops when the queue is
     * unavailable (null dao/workDir) by falling back to [maybeUpload].
     */
    suspend fun enqueuePage(
        pageJpeg: File,
        detected: Boolean,
        autoCorners: FloatArray?,
        userCorners: FloatArray?,
        imageWidth: Int,
        imageHeight: Int,
        filterId: String?,
        pageId: Long? = null,
        documentId: Long? = null,
        autoDrain: Boolean = true,
    ) {
        val queue = dao
        val dir = workDir
        if (queue == null || dir == null) {
            maybeUpload(pageJpeg, detected)
            return
        }
        try {
            if (!pageJpeg.exists()) return
            val uuid = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            queue.insertUpload(
                UploadEntry(
                    kind = UploadKind.PAGE_JPEG,
                    filePath = pageJpeg.absolutePath,
                    remoteName = UploadPayloads.pageJpegName(uuid),
                    contentType = JPEG_TYPE,
                    pageId = pageId,
                    documentId = documentId,
                    detected = detected,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            val sidecar = File(dir, "$uuid.json")
            sidecar.writeText(
                UploadPayloads.sidecarJson(
                    appVersion = appVersion,
                    autoCorners = autoCorners,
                    userCorners = userCorners,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    filterId = filterId,
                    deviceModel = deviceModel,
                )
            )
            queue.insertUpload(
                UploadEntry(
                    kind = UploadKind.PAGE_JSON,
                    filePath = sidecar.absolutePath,
                    remoteName = UploadPayloads.pageJsonName(uuid),
                    contentType = JSON_TYPE,
                    pageId = pageId,
                    documentId = documentId,
                    detected = detected,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            if (autoDrain) drain()
        } catch (t: Throwable) {
            log("feedback enqueue dropped", t)
        }
    }

    /**
     * Durable debug-archive upload (item 1): one queue row for the ZIP.
     * Best-effort like everything else here.
     */
    suspend fun enqueueArchive(archiveZip: File, documentId: Long? = null) {
        val queue = dao ?: return
        try {
            if (!archiveZip.exists()) return
            val now = System.currentTimeMillis()
            queue.insertUpload(
                UploadEntry(
                    kind = UploadKind.ARCHIVE,
                    filePath = archiveZip.absolutePath,
                    remoteName = UploadPayloads.archiveName(UUID.randomUUID().toString()),
                    contentType = ZIP_TYPE,
                    documentId = documentId,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            drain()
        } catch (t: Throwable) {
            log("archive enqueue dropped", t)
        }
    }

    /**
     * Works through the pending queue: uploads due rows, marks results.
     * Respects the opt-in toggle (paused while off) and per-row backoff.
     * Never throws.
     */
    fun drain() {
        scope.launch { drainNow(ignoreBackoff = false) }
    }

    /** Manual retry from Settings: drains immediately, ignoring backoff. */
    fun retryNow() {
        scope.launch { drainNow(ignoreBackoff = true) }
    }

    /** Suspend core of [drain]/[retryNow]; exposed for tests. */
    suspend fun drainNow(ignoreBackoff: Boolean) {
        val queue = dao ?: return
        if (!draining.compareAndSet(false, true)) return
        try {
            if (!settings.feedbackUploadEnabled.first()) return
            runCatching {
                queue.pruneUploaded(System.currentTimeMillis() - PRUNE_AFTER_MS)
            }
            // A row left "uploading" by a dead process goes back to queued.
            val pending = queue.pendingUploads().toMutableList()
            val stuck = queue.uploadsInState(UploadState.UPLOADING)
            for (row in stuck) {
                queue.setUploadState(row.id, UploadState.QUEUED, row.attempts, row.lastError, System.currentTimeMillis())
                pending.add(row.copy(state = UploadState.QUEUED))
            }
            val now = System.currentTimeMillis()
            for (row in pending.sortedBy { it.createdAt }) {
                if (!settings.feedbackUploadEnabled.first()) return
                if (!ignoreBackoff && row.state == UploadState.FAILED && row.attempts >= MAX_AUTO_ATTEMPTS) continue
                if (!ignoreBackoff && row.attempts > 0 && now - row.updatedAt < backoffMs(row.attempts)) continue
                putRow(queue, row)
            }
        } catch (t: Throwable) {
            log("feedback drain dropped", t)
        } finally {
            draining.set(false)
        }
    }

    private suspend fun putRow(queue: OpenScanDao, row: UploadEntry) {
        val file = File(row.filePath)
        if (!file.exists()) {
            queue.deleteUpload(row.id)
            return
        }
        queue.setUploadState(row.id, UploadState.UPLOADING, row.attempts, null, System.currentTimeMillis())
        try {
            val url = uploadUrlFor(row) ?: throw IllegalStateException("bad endpoint")
            doUpload(url, file, row.contentType)
            queue.setUploadState(row.id, UploadState.UPLOADED, row.attempts + 1, null, System.currentTimeMillis())
        } catch (t: Throwable) {
            val message = (t.message ?: t.javaClass.simpleName).take(240)
            log("feedback upload failed", t)
            queue.setUploadState(row.id, UploadState.FAILED, row.attempts + 1, message, System.currentTimeMillis())
        }
    }

    private fun uploadUrlFor(row: UploadEntry): URL? {
        val version = try {
            URLEncoder.encode(appVersion, "UTF-8")
        } catch (_: Exception) {
            return null
        }
        return try {
            val marker = if (row.detected) "detected" else "manual"
            URL("${endpoint.trimEnd('/')}/${row.remoteName}?v=$version&d=$marker")
        } catch (_: MalformedURLException) {
            null
        }
    }

    companion object {
        private const val TAG = "FeedbackUploader"

        /**
         * Fallback upload sink when the app is built without
         * TELEMETRY_UPLOAD_URL/TELEMETRY_TOKEN (see app/build.gradle.kts):
         * ".invalid" never resolves, so uploads fail fast and are dropped —
         * the opt-in toggle stays inert rather than pointing somewhere real.
         */
        const val ENDPOINT = "https://REPLACE-ME.invalid/openscan-uploads/"

        const val JPEG_TYPE = "image/jpeg"
        const val JSON_TYPE = "application/json"
        const val ZIP_TYPE = "application/zip"

        private const val TIMEOUT_MS = 10_000

        /** Auto-retry budget per row before it waits for a manual retry. */
        const val MAX_AUTO_ATTEMPTS = 8

        /** Successful rows older than this are pruned on drain. */
        const val PRUNE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

        /** Backoff before auto attempt N (1-based): 15s, 30s, 60s, … capped at 6h. Visible for tests. */
        fun backoffMs(attempts: Int): Long {
            var backoff = 15_000L
            repeat((attempts - 1).coerceAtLeast(0)) { backoff = min(backoff * 2, 6L * 60 * 60 * 1000) }
            return backoff
        }

        /**
         * Pure URL builder: `<endpoint><uuid>.jpg?v=<version>&d=<detected|manual>`.
         * Returns null when the endpoint is malformed so callers drop the attempt.
         */
        fun buildUploadUrl(endpoint: String, uuid: String, version: String, detected: Boolean): URL? {
            return try {
                val d = if (detected) "detected" else "manual"
                val v = URLEncoder.encode(version, "UTF-8")
                URL("${endpoint.trimEnd('/')}/$uuid.jpg?v=$v&d=$d")
            } catch (_: MalformedURLException) {
                null
            }
        }

        /**
         * Blocking PUT of [file] to [url] with the shared Bearer token; the
         * server rejects anything else, so the check doubles as our
         * abuse-protection handshake. Throws are handled by the caller.
         */
        private fun httpPut(url: URL, file: File, token: String, contentType: String) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PUT"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(file.length())
                connection.setRequestProperty("Content-Type", contentType)
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.connect()
                file.inputStream().use { input ->
                    connection.outputStream.use { output -> input.copyTo(output) }
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
