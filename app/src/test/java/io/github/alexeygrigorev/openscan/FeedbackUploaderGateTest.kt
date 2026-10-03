package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.data.SettingsRepository
import io.github.alexeygrigorev.openscan.scan.FeedbackUploader
import java.io.File
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "upload only when enabled" gate, tested against a fake repository so no
 * network or Android framework is involved. The HTTP PUT itself is replaced
 * by a recording lambda.
 */
class FeedbackUploaderGateTest {

    private class FakeSettings(initial: Boolean) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val feedbackUploadEnabled: Flow<Boolean> = state
        override suspend fun setFeedbackUploadEnabled(enabled: Boolean) {
            state.value = enabled
        }
    }

    private class Recorder {
        var uploads: List<Pair<URL, File>> = emptyList()
        fun record(url: URL, file: File) {
            uploads = uploads + (url to file)
        }
    }

    private fun uploader(settings: FakeSettings, recorder: Recorder, endpoint: String) =
        FeedbackUploader(
            settings = settings,
            appVersion = "1.0.0",
            endpoint = endpoint,
            doUpload = recorder::record,
        )

    @Test
    fun `disabled by default - nothing is uploaded`() = runBlocking {
        val settings = FakeSettings(initial = false)
        val recorder = Recorder()
        uploader(settings, recorder, endpoint = "https://example.com/uploads/")
            .upload(File("/tmp/never-touched.jpg"), detected = true)
        assertEquals(0, recorder.uploads.size)
    }

    @Test
    fun `enabled - uploads page jpeg with detected marker`() = runBlocking {
        val settings = FakeSettings(initial = true)
        val recorder = Recorder()
        uploader(settings, recorder, endpoint = "https://example.com/uploads/")
            .upload(File("/tmp/page.jpg"), detected = true)
        assertEquals(1, recorder.uploads.size)
        val (url, file) = recorder.uploads.first()
        assertEquals("/tmp/page.jpg", file.path)
        assertTrue(url.path.endsWith(".jpg"))
        // the file name must be a UUID (UUID.fromString throws otherwise)
        val name = url.path.substringAfterLast('/').removeSuffix(".jpg")
        assertEquals(name, UUID.fromString(name).toString())
        assertEquals("v=1.0.0&d=detected", url.query)
    }

    @Test
    fun `enabled - gallery pages carry the manual marker`() = runBlocking {
        val settings = FakeSettings(initial = true)
        val recorder = Recorder()
        uploader(settings, recorder, endpoint = "https://example.com/uploads/")
            .upload(File("/tmp/page.jpg"), detected = false)
        assertEquals("v=1.0.0&d=manual", recorder.uploads.single().first.query)
    }

    @Test
    fun `turning the toggle off again stops uploads`() = runBlocking {
        val settings = FakeSettings(initial = true)
        val recorder = Recorder()
        val uploader = uploader(settings, recorder, endpoint = "https://example.com/uploads/")
        uploader.upload(File("/tmp/a.jpg"), detected = false)
        settings.setFeedbackUploadEnabled(false)
        uploader.upload(File("/tmp/b.jpg"), detected = false)
        assertEquals(1, recorder.uploads.size)
    }

    @Test
    fun `malformed endpoint never throws and uploads nothing`() = runBlocking {
        val settings = FakeSettings(initial = true)
        val recorder = Recorder()
        assertNull(
            FeedbackUploader.buildUploadUrl("not a url", "u", "1.0", detected = true)
        )
        uploader(settings, recorder, endpoint = "not a url")
            .upload(File("/tmp/page.jpg"), detected = true)
        assertEquals(0, recorder.uploads.size)
    }
}
