package io.github.alexeygrigorev.scanlet.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

@Database(
    entities = [DocumentEntity::class, PageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ScanletDatabase : RoomDatabase() {
    abstract fun scanletDao(): ScanletDao
}

/**
 * File layout for scan content. The directories are deliberately the ones
 * declared in res/xml/file_paths.xml so every stored file is shareable via
 * the FileProvider:
 *
 *   filesDir/documents/<documentId>/<uuid>.jpg   (pages)
 *   cacheDir/exports/<safe-name>.pdf             (share-ready PDFs)
 */
class DocumentFiles(context: Context) {

    private val documentsDir: File = File(context.filesDir, "documents").apply { mkdirs() }
    val exportsDir: File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun pageFile(documentId: Long): File =
        File(File(documentsDir, documentId.toString()).apply { mkdirs() }, "${java.util.UUID.randomUUID()}.jpg")

    fun pageFile(path: String): File = File(path)

    fun documentDir(documentId: Long): File = File(documentsDir, documentId.toString())

    fun sharedPdfFile(title: String): File =
        File(exportsDir, "${io.github.alexeygrigorev.scanlet.util.safeFileName(title)}.pdf")

    fun deleteDocumentFiles(documentId: Long) {
        documentDir(documentId).deleteRecursively()
    }

    fun deletePageFile(filePath: String) {
        File(filePath).delete()
    }
}

/** Manual dependency graph — small enough to not need a DI framework. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: ScanletDatabase =
        Room.databaseBuilder(appContext, ScanletDatabase::class.java, "scanlet.db").build()

    val files = DocumentFiles(appContext)
    val importer = io.github.alexeygrigorev.scanlet.scan.PageImporter(appContext)
    val repository = DocumentsRepository(database.scanletDao(), files, importer)
}
