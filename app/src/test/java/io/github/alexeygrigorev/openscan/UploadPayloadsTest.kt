package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.UploadPayloads
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadPayloadsTest {

    @Test
    fun `sidecar carries the corner pair and context`() {
        val auto = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        val user = floatArrayOf(2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
        val json = JSONObject(
            UploadPayloads.sidecarJson(
                appVersion = "0.2.4-foss",
                autoCorners = auto,
                refinedCorners = auto,
                userCorners = user,
                imageWidth = 1200,
                imageHeight = 1600,
                filterId = "document",
                deviceModel = "Pixel 8",
            )
        )
        assertEquals(1, json.getInt("schema"))
        assertEquals(1, json.getInt("cornerSchema"))
        assertEquals("0.2.4-foss", json.getString("appVersion"))
        assertEquals("Pixel 8", json.getString("deviceModel"))
        assertEquals(1200, json.getInt("imageWidth"))
        assertEquals(1600, json.getInt("imageHeight"))
        assertEquals("document", json.getString("filter"))
        assertEquals(8, json.getJSONArray("autoCorners").length())
        assertEquals(1.0, json.getJSONArray("autoCorners").getDouble(0), 0.001)
        assertEquals(8, json.getJSONArray("refinedCorners").length())
        assertEquals(8, json.getJSONArray("userCorners").length())
    }

    @Test
    fun `sidecar tolerates missing corners`() {
        val json = JSONObject(
            UploadPayloads.sidecarJson(
                appVersion = "0.2.4-foss",
                autoCorners = null,
                refinedCorners = null,
                userCorners = null,
                imageWidth = 0,
                imageHeight = 0,
                filterId = null,
                deviceModel = "unknown",
            )
        )
        assertTrue(json.isNull("autoCorners"))
        assertTrue(json.isNull("userCorners"))
        assertEquals("document", json.getString("filter"))
    }

    @Test
    fun `remote names are stable per kind`() {
        assertEquals("abc.jpg", UploadPayloads.pageJpegName("abc"))
        assertEquals("abc.json", UploadPayloads.pageJsonName("abc"))
        assertEquals("abc-debug.zip", UploadPayloads.archiveName("abc"))
    }
}
