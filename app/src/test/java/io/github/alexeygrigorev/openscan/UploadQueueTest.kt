package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.data.DocumentEntity
import io.github.alexeygrigorev.openscan.data.DocumentSummary
import io.github.alexeygrigorev.openscan.data.OpenScanDao
import io.github.alexeygrigorev.openscan.data.PageEntity
import io.github.alexeygrigorev.openscan.data.SettingsRepository
import io.github.alexeygrigorev.openscan.data.UploadEntry
import io.github.alexeygrigorev.openscan.data.UploadState
import io.github.alexeygrigorev.openscan.scan.FeedbackUploader
import java.io.File
import java.net.URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The durable upload queue (item 0): enqueue writes rows, drain sends them,
 * failures stay visible and retryable. No network, no Android framework.
 */
class UploadQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSettings(var enabled: Boolean) : SettingsRepository {
        private val feedback = MutableStateFlow(enabled)
        override val feedbackUploadEnabled: Flow<Boolean> = feedback
        override suspend fun setFeedbackUploadEnabled(enabled: Boolean) {
            this.enabled = enabled
            feedback.value = enabled
        }
        override val keepOriginalsEnabled: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setKeepOriginalsEnabled(enabled: Boolean) = Unit
        override val developerModeEnabled: Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setDeveloperModeEnabled(enabled: Boolean) = Unit
        override val filterDefault: Flow<String> = MutableStateFlow("document")
        override suspend fun setFilterDefault(filterId: String) = Unit
        override val autoCaptureEnabled = MutableStateFlow(false)
        override suspend fun setAutoCaptureEnabled(enabled: Boolean) {}
    }

    private class FakeDao : OpenScanDao {
        val uploads = mutableListOf<UploadEntry>()
        private var nextId = 1L

        override suspend fun insertDocument(document: DocumentEntity): Long = 0
        override suspend fun insertPage(page: PageEntity): Long = 0
        override fun observeDocuments(): Flow<List<DocumentSummary>> = flowOf(emptyList())
        override fun observeDocument(id: Long): Flow<DocumentEntity?> = flowOf(null)
        override suspend fun getDocument(id: Long): DocumentEntity? = null
        override suspend fun renameDocument(id: Long, title: String, now: Long) = Unit
        override suspend fun touchDocument(id: Long, now: Long) = Unit
        override suspend fun deleteDocument(id: Long) = Unit
        override fun observePages(documentId: Long): Flow<List<PageEntity>> = flowOf(emptyList())
        override suspend fun getPages(documentId: Long): List<PageEntity> = emptyList()
        override suspend fun getPage(id: Long): PageEntity? = null
        override suspend fun setPageFile(id: Long, filePath: String) = Unit
        override suspend fun setPageCorners(id: Long, auto: String?, refined: String?, user: String?, schema: Int?, width: Int?, height: Int?) = Unit
        override suspend fun setPageFilter(id: Long, filter: String?) = Unit
        override suspend fun setNeedsCheck(id: Long, flag: Boolean) = Unit
        override suspend fun setDocumentFilter(id: Long, filter: String?) = Unit
        override suspend fun updatePages(pages: List<PageEntity>) = Unit
        override suspend fun deletePage(id: Long) = Unit

        override suspend fun insertUpload(entry: UploadEntry): Long {
            val id = nextId++
            uploads.add(entry.copy(id = id))
            return id
        }

        override fun observeUploads(): Flow<List<UploadEntry>> = flowOf(uploads.toList())

        override suspend fun pendingUploads(): List<UploadEntry> =
            uploads.filter { it.state == UploadState.QUEUED || it.state == UploadState.FAILED }

        override suspend fun uploadsInState(state: String): List<UploadEntry> =
            uploads.filter { it.state == state }

        override suspend fun setUploadState(id: Long, state: String, attempts: Int, lastError: String?, updatedAt: Long) {
            val index = uploads.indexOfFirst { it.id == id }
            if (index >= 0) {
                uploads[index] = uploads[index].copy(
                    state = state, attempts = attempts, lastError = lastError, updatedAt = updatedAt
                )
            }
        }

        override suspend fun deleteUpload(id: Long) {
            uploads.removeAll { it.id == id }
        }

        override suspend fun pruneUploaded(before: Long) {
            uploads.removeAll { it.state == UploadState.UPLOADED && it.updatedAt < before }
        }
    }

    private data class Harness(
        val dao: FakeDao,
        val settings: FakeSettings,
        val puts: MutableList<Triple<URL, File, String>>,
        val failWith: MutableList<Throwable?>,
        val uploader: FeedbackUploader,
    )

    private fun harness(enabled: Boolean = true): Harness {
        val dao = FakeDao()
        val settings = FakeSettings(enabled)
        val puts = mutableListOf<Triple<URL, File, String>>()
        val failWith = mutableListOf<Throwable?>()
        val uploader = FeedbackUploader(
            settings = settings,
            appVersion = "0.2.4-test",
            deviceModel = "TestDevice",
            endpoint = "https://example.com/uploads/",
            token = "t",
            dao = dao,
            workDir = tmp.root,
            log = { _, _ -> },
            doUpload = { url, file, contentType ->
                failWith.removeFirstOrNull()?.let { throw it }
                puts.add(Triple(url, file, contentType))
            },
        )
        return Harness(dao, settings, puts, failWith, uploader)
    }

    private fun pageFile(): File = tmp.newFile("page.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test
    fun `enqueue uploads jpeg and sidecar, then marks both uploaded`() = runBlocking {
        val h = harness()
        h.uploader.enqueuePage(
            pageJpeg = pageFile(), detected = true,
            autoCorners = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f),
            refinedCorners = null,
            userCorners = null, imageWidth = 100, imageHeight = 200, filterId = null,
            autoDrain = false,
        )
        // drain() launches async; drive it deterministically instead.
        h.uploader.drainNow(ignoreBackoff = true)
        assertEquals(2, h.puts.size)
        val byType = h.puts.map { it.third }.sorted()
        assertEquals(listOf("application/json", "image/jpeg"), byType)
        assertTrue(h.dao.uploads.all { it.state == UploadState.UPLOADED })
        // The sidecar carries the corner pair.
        val sidecar = h.puts.first { it.third == "application/json" }.second.readText()
        assertTrue(sidecar.contains("autoCorners"))
    }

    @Test
    fun `toggle off pauses the queue without losing rows`() = runBlocking {
        val h = harness(enabled = false)
        h.uploader.enqueuePage(
            pageJpeg = pageFile(), detected = true,
            autoCorners = null, refinedCorners = null, userCorners = null, imageWidth = 0, imageHeight = 0, filterId = null,
            autoDrain = false,
        )
        h.uploader.drainNow(ignoreBackoff = true)
        assertEquals(0, h.puts.size)
        assertEquals(2, h.dao.uploads.size)
        assertTrue(h.dao.uploads.all { it.state == UploadState.QUEUED })
    }

    @Test
    fun `failed puts stay visible and retry`() = runBlocking {
        val h = harness()
        h.failWith.add(java.io.IOException("boom"))
        h.failWith.add(java.io.IOException("boom"))
        val file = pageFile()
        h.uploader.enqueuePage(
            pageJpeg = file, detected = false,
            autoCorners = null, refinedCorners = null, userCorners = null, imageWidth = 0, imageHeight = 0, filterId = null,
            autoDrain = false,
        )
        h.uploader.drainNow(ignoreBackoff = true)
        assertEquals(0, h.puts.size)
        assertTrue(h.dao.uploads.all { it.state == UploadState.FAILED })
        assertTrue(h.dao.uploads.all { it.attempts == 1 })
        assertTrue(h.dao.uploads.all { it.lastError?.contains("boom") == true })

        // Manual retry succeeds once the network is back.
        h.uploader.drainNow(ignoreBackoff = true)
        assertEquals(2, h.puts.size)
        assertTrue(h.dao.uploads.all { it.state == UploadState.UPLOADED })
    }

    @Test
    fun `automatic drain respects backoff`() = runBlocking {
        val h = harness()
        h.failWith.add(java.io.IOException("boom"))
        h.failWith.add(java.io.IOException("boom"))
        h.uploader.enqueuePage(
            pageJpeg = pageFile(), detected = true,
            autoCorners = null, refinedCorners = null, userCorners = null, imageWidth = 0, imageHeight = 0, filterId = null,
            autoDrain = false,
        )
        h.uploader.drainNow(ignoreBackoff = true)
        assertTrue(h.dao.uploads.all { it.state == UploadState.FAILED })
        // Too soon: automatic drain skips, manual retry goes through.
        h.uploader.drainNow(ignoreBackoff = false)
        assertEquals(0, h.puts.size)
        h.uploader.drainNow(ignoreBackoff = true)
        assertEquals(2, h.puts.size)
    }

    @Test
    fun `missing files are dropped from the queue`() = runBlocking {
        val h = harness()
        val gone = File(tmp.root, "gone.jpg")
        h.uploader.enqueuePage(
            pageJpeg = gone, detected = true,
            autoCorners = null, refinedCorners = null, userCorners = null, imageWidth = 0, imageHeight = 0, filterId = null,
        )
        assertTrue(h.dao.uploads.isEmpty())
        assertEquals(0, h.puts.size)
    }

    @Test
    fun `backoff grows and caps`() {
        assertEquals(15_000L, FeedbackUploader.backoffMs(1))
        assertEquals(30_000L, FeedbackUploader.backoffMs(2))
        assertEquals(6L * 60 * 60 * 1000, FeedbackUploader.backoffMs(30))
    }
}
