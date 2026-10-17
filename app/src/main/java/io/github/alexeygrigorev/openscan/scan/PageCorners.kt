package io.github.alexeygrigorev.openscan.scan

import org.json.JSONArray

/**
 * Versioned encoding for page corner quads (item 5): TL, TR, BR, BL as a JSON
 * array of 8 numbers. The schema version rides alongside the payload
 * ([PageEntity.cornerSchema]) so future formats stay backward compatible:
 * readers accept what they understand and treat the rest as absent.
 */
object PageCorners {

    /** Current writer version; bump when [encode]'s format changes. */
    const val SCHEMA_VERSION = 1

    /** Encodes [quad] (8 floats) as a JSON array string, or null for null. */
    fun encode(quad: FloatArray?): String? {
        if (quad == null) return null
        require(quad.size == 8) { "quad must hold 8 values (TL, TR, BR, BL), got ${quad.size}" }
        val array = JSONArray()
        for (v in quad) array.put(v.toDouble())
        return array.toString()
    }

    /**
     * Decodes an [encode] string back to 8 floats. Returns null for null,
     * blank, malformed, or non-finite input — never throws — so legacy or
     * foreign payloads degrade to "no corner data" instead of crashing.
     */
    fun decode(raw: String?): FloatArray? {
        if (raw.isNullOrBlank()) return null
        return try {
            val array = JSONArray(raw)
            if (array.length() != 8) return null
            FloatArray(8) { i ->
                val v = array.getDouble(i)
                if (!v.isFinite()) return null
                v.toFloat()
            }
        } catch (_: Exception) {
            null
        }
    }
}
