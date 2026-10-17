package io.github.alexeygrigorev.openscan.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OpenScanDao {

    @Insert
    suspend fun insertDocument(document: DocumentEntity): Long

    @Insert
    suspend fun insertPage(page: PageEntity): Long

    @Transaction
    @Query(
        """
        SELECT d.*, (
            SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id
        ) AS pageCount
        FROM documents d
        ORDER BY d.updatedAt DESC
        """
    )
    fun observeDocuments(): Flow<List<DocumentSummary>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getDocument(id: Long): DocumentEntity?

    @Query(
        """
        UPDATE documents
        SET title = :title, updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun renameDocument(id: Long, title: String, now: Long)

    @Query("UPDATE documents SET updatedAt = :now WHERE id = :id")
    suspend fun touchDocument(id: Long, now: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    fun observePages(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY position")
    suspend fun getPages(documentId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun getPage(id: Long): PageEntity?

    @Query("UPDATE pages SET filePath = :filePath WHERE id = :id")
    suspend fun setPageFile(id: Long, filePath: String)

    /** Persists the training/eval corner triple for a page (items 5+8). */
    @Query(
        """
        UPDATE pages
        SET autoCorners = :auto, refinedCorners = :refined, userCorners = :user,
            cornerSchema = :schema, imageWidth = :width, imageHeight = :height
        WHERE id = :id
        """
    )
    suspend fun setPageCorners(
        id: Long,
        auto: String?,
        refined: String?,
        user: String?,
        schema: Int,
        width: Int,
        height: Int,
    )

    @Query("UPDATE pages SET scanFilter = :filter WHERE id = :id")
    suspend fun setPageFilter(id: Long, filter: String?)

    @Query("UPDATE documents SET scanFilter = :filter WHERE id = :id")
    suspend fun setDocumentFilter(id: Long, filter: String?)

    @Insert
    suspend fun insertUpload(entry: UploadEntry): Long

    @Query("SELECT * FROM uploads ORDER BY createdAt ASC")
    fun observeUploads(): Flow<List<UploadEntry>>

    @Query("SELECT * FROM uploads WHERE state IN ('queued', 'failed') ORDER BY createdAt ASC")
    suspend fun pendingUploads(): List<UploadEntry>

    @Query("SELECT * FROM uploads WHERE state = :state ORDER BY createdAt ASC")
    suspend fun uploadsInState(state: String): List<UploadEntry>

    @Query(
        """
        UPDATE uploads
        SET state = :state, attempts = :attempts, lastError = :lastError,
            updatedAt = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun setUploadState(
        id: Long,
        state: String,
        attempts: Int,
        lastError: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM uploads WHERE id = :id")
    suspend fun deleteUpload(id: Long)

    /** Drops old successful records so the queue table stays small. */
    @Query("DELETE FROM uploads WHERE state = 'uploaded' AND updatedAt < :before")
    suspend fun pruneUploaded(before: Long)

    @Update
    suspend fun updatePages(pages: List<PageEntity>)

    /** Replaces the persisted page order in one transaction. */
    @Transaction
    suspend fun persistPageOrder(pages: List<PageEntity>) {
        updatePages(pages)
    }

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: Long)
}
