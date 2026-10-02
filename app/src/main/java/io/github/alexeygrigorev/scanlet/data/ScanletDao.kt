package io.github.alexeygrigorev.scanlet.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanletDao {

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

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: Long)
}
