package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.FeedbackUploader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the telemetry upload URL builder (no network, no Android). */
class FeedbackUploaderUrlTest {

    @Test
    fun `builds endpoint uuid jpg with version and detected params`() {
        val url = FeedbackUploader.buildUploadUrl(
            endpoint = "https://example.com/uploads/",
            uuid = "0f3a2b1c-0000-4000-8000-000000000001",
            version = "1.2.3",
            detected = true,
        )
        assertEquals(
            "https://example.com/uploads/0f3a2b1c-0000-4000-8000-000000000001.jpg?v=1.2.3&d=detected",
            url.toString(),
        )
    }

    @Test
    fun `gallery imports are marked manual`() {
        val url = FeedbackUploader.buildUploadUrl("https://example.com/", "abc", "1.0", detected = false)
        assertEquals("https://example.com/abc.jpg?v=1.0&d=manual", url.toString())
    }

    @Test
    fun `endpoint without trailing slash gets exactly one slash`() {
        val url = FeedbackUploader.buildUploadUrl("https://example.com/uploads", "abc", "1.0", detected = true)
        assertEquals("https://example.com/uploads/abc.jpg?v=1.0&d=detected", url.toString())
    }

    @Test
    fun `version is url-encoded`() {
        val url = FeedbackUploader.buildUploadUrl("https://example.com/", "abc", "1.0 beta+2", detected = false)
        assertEquals("https://example.com/abc.jpg?v=1.0+beta%2B2&d=manual", url.toString())
    }

    @Test
    fun `malformed endpoint yields null instead of throwing`() {
        assertNull(FeedbackUploader.buildUploadUrl("not a url", "abc", "1.0", detected = true))
        assertNull(FeedbackUploader.buildUploadUrl("", "abc", "1.0", detected = true))
        assertNull(FeedbackUploader.buildUploadUrl("http://[invalid", "abc", "1.0", detected = true))
    }

    @Test
    fun `well-formed endpoint yields a url whose query carries both params`() {
        val url = FeedbackUploader.buildUploadUrl("https://e.com/x/", "u", "9", detected = true)
        assertNotNull(url)
        assertTrue(url!!.query!!.startsWith("v="))
        assertTrue(url.query!!.endsWith("&d=detected"))
    }
}
