package io.github.alexeygrigorev.openscan.scan

import org.json.JSONArray
import org.json.JSONObject

/**
 * Payload builders for the developer-share upload (items 0, 5): the JSON
 * sidecar that travels next to every uploaded page JPEG, carrying the
 * training/eval corner pair plus the context needed to interpret it.
 * Pure string building — no Android, unit-tested.
 */
object UploadPayloads {

    /** Schema version of the sidecar document; bump with format changes. */
    const val SCHEMA_VERSION = 1

    /**
     * Builds the sidecar JSON: app version, schema versions, the auto and
     * user corner quads (8-number arrays or null), the stored image size, the
     * effective filter id, and the device model. Never throws: falls back to
     * a minimal document carrying the error message.
     */
    fun sidecarJson(
        appVersion: String,
        autoCorners: FloatArray?,
        userCorners: FloatArray?,
        imageWidth: Int,
        imageHeight: Int,
        filterId: String?,
        deviceModel: String,
    ): String {
        return try {
            JSONObject()
                .put("schema", SCHEMA_VERSION)
                .put("cornerSchema", PageCorners.SCHEMA_VERSION)
                .put("appVersion", appVersion)
                .put("deviceModel", deviceModel)
                .put("autoCorners", quadOrNull(autoCorners))
                .put("userCorners", quadOrNull(userCorners))
                .put("imageWidth", imageWidth)
                .put("imageHeight", imageHeight)
                .put("filter", filterId ?: ScanFilter.DEFAULT.id)
                .toString()
        } catch (_: Exception) {
            "{\"schema\":$SCHEMA_VERSION,\"error\":\"encode-failed\"}"
        }
    }

    private fun quadOrNull(quad: FloatArray?): Any =
        if (quad != null && quad.size == 8 && quad.all { it.isFinite() }) {
            JSONArray().apply { for (v in quad) put(v.toDouble()) }
        } else {
            JSONObject.NULL
        }

    /** Remote object name for a page JPEG upload. */
    fun pageJpegName(uuid: String): String = "$uuid.jpg"

    /** Remote object name for a page sidecar upload (same stem as the JPEG). */
    fun pageJsonName(uuid: String): String = "$uuid.json"

    /** Remote object name for a debug-archive upload. */
    fun archiveName(uuid: String): String = "$uuid-debug.zip"
}
