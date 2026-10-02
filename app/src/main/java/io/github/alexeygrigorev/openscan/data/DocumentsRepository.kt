package io.github.alexeygrigorev.openscan.data

import android.net.Uri
import io.github.alexeygrigorev.openscan.scan.PageImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
) {

    fun observeDocuments(): Flow<List<DocumentSummary>> = dao.observeDocuments()
    fun observeDocument(id: Long): Flow<DocumentEntity?> = dao.observeDocument(id)
    fun observePages(documentId: Long): Flow<List<PageEntity>> = dao.observePages(documentId)

    suspend fun createDocument(title: String = defaultTitle()): Long =
        dao.insertDocument(
            DocumentEntity(title = title, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        )

    suspend fun importPage(documentId: Long, source: Uri): PageEntity = withContext(Dispatchers.IO) {
        val position = dao.getPages(documentId).size
        val file = files.pageFile(documentId)
        importer.import(source, file)
        val page = PageEntity(
            documentId = documentId,
            position = position,
            filePath = file.absolutePath,
            createdAt = System.currentTimeMillis(),
        )
        val id = dao.insertPage(page)
        dao.touchDocument(documentId, System.currentTimeMillis())
        page.copy(id = id)
    }

    suspend fun getDocument(id: Long): DocumentEntity? = dao.getDocument(id)
    suspend fun getPages(documentId: Long): List<PageEntity> = dao.getPages(documentId)
    suspend fun getPage(id: Long): PageEntity? = dao.getPage(id)

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
        if (rotated !== bitmap) bitmap.recycle()
        rotated.recycle()
        dao.setPageFile(page.id, dest.absolutePath)
        files.deletePageFile(page.filePath)
        dao.touchDocument(page.documentId, System.currentTimeMillis())
    }

    companion object {
        fun defaultTitle(): String =
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date())
    }
}
