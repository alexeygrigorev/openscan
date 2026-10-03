package io.github.alexeygrigorev.openscan.scan

import android.util.Log
import io.github.alexeygrigorev.openscan.data.SettingsRepository
import java.io.File
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Opt-in telemetry: uploads the original captured page photo so the document
 * detector can be improved. The contract is silence:
 *
 *  - uploads happen only when the user turned the toggle on in Settings
 *    (read through [SettingsRepository]; off by default),
 *  - everything is best-effort: any failure is logged and dropped; capture
 *    and import are never blocked or crashed by this class.
 */
class FeedbackUploader(
    private val settings: SettingsRepository,
    private val appVersion: String,
    private val endpoint: String = ENDPOINT,
    private val token: String = "",
    private val doUpload: (URL, File) -> Unit = { url, file -> httpPut(url, file, token) },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            doUpload(url, pageJpeg)
        } catch (t: Throwable) {
            Log.w(TAG, "feedback upload dropped", t)
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

        private const val TIMEOUT_MS = 10_000

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
        private fun httpPut(url: URL, file: File, token: String) {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "PUT"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(file.length())
                connection.setRequestProperty("Content-Type", "image/jpeg")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.connect()
                file.inputStream().use { input ->
                    connection.outputStream.use { output -> input.copyTo(output) }
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "feedback upload got HTTP $code")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
