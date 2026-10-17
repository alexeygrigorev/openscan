package io.github.alexeygrigorev.openscan.data

import android.net.Uri
import io.github.alexeygrigorev.openscan.scan.FeedbackUploader
import io.github.alexeygrigorev.openscan.scan.PageImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All document/page state changes go through here: the database rows and the
 * private-storage files always move together.
 */
class DocumentsRepository(
    private val dao: OpenScanDao,
    private val files: DocumentFiles,
    private val importer: PageImporter,
    private val feedbackUploader: FeedbackUploader,
) {

    fun observeDocuments(): Flow<List<DocumentSummary>> = dao.observeDocuments()
    fun observeDocument(id: Long): Flow<DocumentEntity?> = dao.observeDocument(id)
    fun observePages(documentId: Long): Flow<List<PageEntity>> = dao.observePages(documentId)

    suspend fun createDocument(title: String = defaultTitle()): Long =
        dao.insertDocument(
            DocumentEntity(title = title, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        )

    /**
     * Imports one page. [detected] marks scanner captures whose document
     * bounds were detected on-device (gallery imports are "manual"); it only
     * annotates the opt-in telemetry upload, never the import itself.
     * [autoCorners]/[refinedCorners]/[userCorners] are the training/eval
     * corner triple in the [imageWidth]×[imageHeight] coordinate space
     * (nulls when unknown); dims are 0 when unknown.
     */
    suspend fun importPage(
        documentId: Long,
        source: Uri,
        detected: Boolean = false,
        autoCorners: FloatArray? = null,
        refinedCorners: FloatArray? = null,
        userCorners: FloatArray? = null,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
    ): PageEntity = withContext(Dispatchers.IO) {
        val position = dao.getPages(documentId).size
        val file = files.pageFile(documentId)
        importer.import(source, file)
        val page = PageEntity(
            documentId = documentId,
            position = position,
            filePath = file.absolutePath,
            createdAt = System.currentTimeMillis(),
            autoCorners = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(autoCorners),
            refinedCorners = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(refinedCorners),
            userCorners = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(userCorners),
            cornerSchema = cornerSchemaFor(autoCorners, refinedCorners, userCorners),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
        val id = dao.insertPage(page)
        dao.touchDocument(documentId, System.currentTimeMillis())
        // Opt-in telemetry: durable enqueue, best-effort; never blocks or
        // fails this import. The uploader no-ops unless the toggle is on.
        feedbackUploader.enqueuePage(
            pageJpeg = file,
            detected = detected,
            autoCorners = autoCorners,
            refinedCorners = refinedCorners,
            userCorners = userCorners,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            filterId = null,
            pageId = id,
            documentId = documentId,
        )
        page.copy(id = id)
    }

    suspend fun getDocument(id: Long): DocumentEntity? = dao.getDocument(id)
    suspend fun getPages(documentId: Long): List<PageEntity> = dao.getPages(documentId)
    suspend fun getPage(id: Long): PageEntity? = dao.getPage(id)

    /**
     * Imports an already-processed page (the foss batch capture saves the
     * perspective-warped bitmap directly, skipping a decode/re-encode
     * round trip). Same contract as [importPage]: [detected] only annotates
     * the opt-in telemetry upload. [original], when given, is the unprocessed
     * camera frame the bitmap was cropped from; it is kept next to the page
     * (keyed by page id) so the crop can be redone or the capture shared for
     * debugging later. Keeping it is best-effort: a failed copy never fails
     * the import.
     */
    suspend fun importPageBitmap(
        documentId: Long,
        bitmap: android.graphics.Bitmap,
        detected: Boolean = false,
        original: java.io.File? = null,
        autoCorners: FloatArray? = null,
        refinedCorners: FloatArray? = null,
        userCorners: FloatArray? = null,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
    ): PageEntity =
        withContext(Dispatchers.IO) {
            val position = dao.getPages(documentId).size
            val file = files.pageFile(documentId)
            io.github.alexeygrigorev.openscan.scan.Images.saveJpeg(bitmap, file)
            val page = PageEntity(
                documentId = documentId,
                position = position,
                filePath = file.absolutePath,
                createdAt = System.currentTimeMillis(),
                autoCorners = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(autoCorners),
                refinedCorners = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(refinedCorners),
                userCorners = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(userCorners),
                cornerSchema = cornerSchemaFor(autoCorners, refinedCorners, userCorners),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )
            val id = dao.insertPage(page)
            original?.takeIf { it.exists() }?.let { source ->
                runCatching { source.copyTo(files.originalFile(documentId, id), overwrite = false) }
            }
            dao.touchDocument(documentId, System.currentTimeMillis())
            // Opt-in telemetry: durable enqueue, best-effort; never blocks or
            // fails this import. The uploader no-ops unless the toggle is on.
            feedbackUploader.enqueuePage(
                pageJpeg = file,
                detected = detected,
                autoCorners = autoCorners,
                refinedCorners = refinedCorners,
                userCorners = userCorners,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                filterId = null,
                pageId = id,
                documentId = documentId,
            )
            page.copy(id = id)
        }

    /** Updates the stored corner triple for a page (re-crop / re-edit). */
    suspend fun setPageCorners(
        pageId: Long,
        autoCorners: FloatArray?,
        refinedCorners: FloatArray?,
        userCorners: FloatArray?,
        imageWidth: Int,
        imageHeight: Int,
    ) {
        dao.setPageCorners(
            id = pageId,
            auto = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(autoCorners),
            refined = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(refinedCorners),
            user = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(userCorners),
            schema = cornerSchemaFor(autoCorners, refinedCorners, userCorners),
            width = imageWidth,
            height = imageHeight,
        )
    }

    suspend fun setPageFilter(pageId: Long, filterId: String?) {
        dao.setPageFilter(pageId, filterId)
    }

    suspend fun setDocumentFilter(documentId: Long, filterId: String?) {
        dao.setDocumentFilter(documentId, filterId)
    }

    /** Visible upload status for Settings (item 0). */
    fun observeUploads(): Flow<List<UploadEntry>> = dao.observeUploads()

    fun observeUploadStats(): Flow<io.github.alexeygrigorev.openscan.scan.UploadStats> =
        dao.observeUploads().map { io.github.alexeygrigorev.openscan.scan.UploadStats.from(it) }

    /** Manual retry of failed/queued uploads from Settings. */
    fun retryUploads() {
        feedbackUploader.retryNow()
    }

    /** Debug-archive upload into the developer-share queue (item 1). */
    suspend fun enqueueArchive(archiveZip: java.io.File, documentId: Long?) {
        feedbackUploader.enqueueArchive(archiveZip, documentId)
    }

    /** Flushes due uploads (app start, toggle re-enabled). */
    fun flushUploads() {
        feedbackUploader.drain()
    }

    suspend fun renameDocument(id: Long, title: String) {
        dao.renameDocument(id, title.trim().ifEmpty { defaultTitle() }, System.currentTimeMillis())
    }

    suspend fun deleteDocument(id: Long) {
        dao.deleteDocument(id) // pages cascade
        files.deleteDocumentFiles(id)
    }

    suspend fun deletePage(page: PageEntity) {
        dao.deletePage(page.id)
        files.deletePageFile(page.filePath)
        files.deleteOriginalFile(page.documentId, page.id)
        dao.touchDocument(page.documentId, System.currentTimeMillis())
    }

    /**
     * Moves a page [delta] slots in the document (−1 = up, +1 = down) and
     * persists the full re-indexed order.
     */
    suspend fun movePage(page: PageEntity, delta: Int) {
        val current = dao.getPages(page.documentId)
        val updated = PageOrdering.move(current, page.id, delta)
        if (updated == current) return
        dao.persistPageOrder(updated)
        dao.touchDocument(page.documentId, System.currentTimeMillis())
    }

    /** Bakes a 90° clockwise rotation into the stored page JPEG. */
    suspend fun rotatePage(page: PageEntity) = withContext(Dispatchers.IO) {
        val source = files.pageFile(page.filePath)
        val bitmap = io.github.alexeygrigorev.openscan.scan.Images.decodeScaled(source, maxDim = 2600)
            ?: return@withContext
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val dest = files.pageFile(page.documentId)
        io.github.alexeygrigorev.openscan.scan.Images.saveJpeg(rotated, dest)
        // A baked rotation invalidates the recorded corner geometry (quads
        // live in pre-rotation frame coordinates), so the pair is cleared
        // rather than left silently stale; dims track the new stored page.
        val rotatedWidth = rotated.width
        val rotatedHeight = rotated.height
        if (rotated !== bitmap) bitmap.recycle()
        rotated.recycle()
        dao.setPageFile(page.id, dest.absolutePath)
        dao.setPageCorners(page.id, null, null, null, 0, rotatedWidth, rotatedHeight)
        dao.setNeedsCheck(page.id, false)
        files.deletePageFile(page.filePath)
        dao.touchDocument(page.documentId, System.currentTimeMillis())
    }

    companion object {
        fun defaultTitle(): String =
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())

        /** Schema marker for the stored corner triple: 0 when all three are null. */
        fun cornerSchemaFor(vararg quads: FloatArray?): Int =
            if (quads.any { it != null }) {
                io.github.alexeygrigorev.openscan.scan.PageCorners.SCHEMA_VERSION
            } else {
                0
            }
    }

    /** Outcome of a single-page re-detect (item 11). */
    data class RedetectOutcome(
        val cropChanged: Boolean,
        val needsCheck: Boolean,
        val usedStoredPage: Boolean,
    )

    /** Outcome of a whole-document re-detect (item 11). */
    data class RedetectSummary(
        val attempted: Int,
        val cropsChanged: Int,
        val flagged: Int,
        val failed: Int,
        val usedStoredPage: Int,
    )

    /**
     * Re-runs the current detection + refinement over a saved page (item 11):
     * over the kept original frame when present, otherwise over the stored
     * page itself (older versions, play flavor, gallery imports and
     * keep-originals-off rows have no originals). Re-warps on a genuine
     * plausible crop, otherwise keeps the crop and records the fresh corners.
     * Never throws: failures surface as [RedetectOutcome] via exception —
     * callers show them, nothing is half-written.
     */
    suspend fun redetectPage(pageId: Long): RedetectOutcome = withContext(Dispatchers.IO) {
        val page = dao.getPage(pageId) ?: error("Page not found")
        val original = files.originalFile(page.documentId, pageId).takeIf { it.exists() }
        val sourceFile = original ?: java.io.File(page.filePath)
        val source = io.github.alexeygrigorev.openscan.scan.Images.decodeScaled(
            sourceFile, io.github.alexeygrigorev.openscan.scan.PageRedetect.REDETECT_MAX_DIM
        ) ?: error("Cannot decode page image")
        try {
            val result = io.github.alexeygrigorev.openscan.scan.PageRedetect.redetect(source)
            val warped = result.bitmap
            if (warped != null) {
                val dest = files.pageFile(page.documentId)
                io.github.alexeygrigorev.openscan.scan.Images.saveJpeg(warped, dest)
                if (warped !== source) warped.recycle()
                source.recycle()
                dao.setPageFile(pageId, dest.absolutePath)
                files.deletePageFile(page.filePath)
            } else {
                source.recycle()
            }
            dao.setPageCorners(
                id = pageId,
                auto = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(result.auto),
                refined = io.github.alexeygrigorev.openscan.scan.PageCorners.encode(result.refined),
                user = null,
                schema = cornerSchemaFor(result.auto, result.refined, null),
                width = result.width,
                height = result.height,
            )
            dao.setNeedsCheck(pageId, result.needsCheck)
            RedetectOutcome(
                cropChanged = result.cropChanged,
                needsCheck = result.needsCheck,
                usedStoredPage = original == null,
            )
        } catch (t: Throwable) {
            runCatching { source.recycle() }
            throw t
        }
    }

    /**
     * Re-detects every page of a document (item 11). Pages decode or fail
     * independently: one bad file never aborts the rest.
     */
    suspend fun redetectDocument(documentId: Long): RedetectSummary = withContext(Dispatchers.IO) {
        val pages = dao.getPages(documentId)
        var changed = 0
        var flagged = 0
        var failed = 0
        var stored = 0
        for (page in pages) {
            try {
                val outcome = redetectPage(page.id)
                if (outcome.cropChanged) changed++
                if (outcome.needsCheck) flagged++
                if (outcome.usedStoredPage) stored++
            } catch (_: Throwable) {
                failed++
            }
        }
        dao.touchDocument(documentId, System.currentTimeMillis())
        RedetectSummary(
            attempted = pages.size,
            cropsChanged = changed,
            flagged = flagged,
            failed = failed,
            usedStoredPage = stored,
        )
    }
}
