package io.github.alexeygrigorev.openscan.data

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
abstract class OpenScanDatabase : RoomDatabase() {
    abstract fun openscanDao(): OpenScanDao
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
        File(exportsDir, "${io.github.alexeygrigorev.openscan.util.safeFileName(title)}.pdf")

    /** Single-page JPEG export. Pages are stored as JPEGs, so this is a plain copy. */
    fun sharedImageFile(title: String): File =
        File(exportsDir, "${io.github.alexeygrigorev.openscan.util.safeFileName(title)}.jpg")

    /** Multi-page JPEG export: a zip of page-001.jpg, page-002.jpg, … */
    fun sharedZipFile(title: String): File =
        File(exportsDir, "${io.github.alexeygrigorev.openscan.util.safeFileName(title)}.zip")

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

    val database: OpenScanDatabase =
        Room.databaseBuilder(appContext, OpenScanDatabase::class.java, "openscan.db").build()

    val files = DocumentFiles(appContext)
    val importer = io.github.alexeygrigorev.openscan.scan.PageImporter(appContext)
    val settings: SettingsRepository =
        DataStoreSettingsRepository(DataStoreSettingsRepository.createDefaultDataStore(appContext))
    val uploader = io.github.alexeygrigorev.openscan.scan.FeedbackUploader(settings, appVersionName())
    val repository = DocumentsRepository(database.openscanDao(), files, importer, uploader)

    private fun appVersionName(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }
}
