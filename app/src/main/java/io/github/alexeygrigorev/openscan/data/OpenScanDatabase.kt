package io.github.alexeygrigorev.openscan.data

import android.content.Context
import android.content.pm.ApplicationInfo
import io.github.alexeygrigorev.openscan.BuildConfig
import io.github.alexeygrigorev.openscan.update.ReleaseChecker
import io.github.alexeygrigorev.openscan.update.UpdateMonitor
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

    /**
     * The unprocessed camera frame a page was cropped from (foss batch
     * capture, behind the keep-originals setting), keyed by page id. Lives
     * inside the document dir so [deleteDocumentFiles] cleans it up with
     * everything else.
     */
    fun originalFile(documentId: Long, pageId: Long): File =
        File(File(documentDir(documentId), "originals").apply { mkdirs() }, "${pageId}.jpg")

    fun deleteOriginalFile(documentId: Long, pageId: Long) {
        File(documentDir(documentId), "originals/${pageId}.jpg").delete()
    }

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

    // Endpoint/token come in at build time (OPENSCAN_TELEMETRY_UPLOAD_URL /
    // OPENSCAN_TELEMETRY_UPLOAD_TOKEN gradle properties or env vars); when
    // absent the uploader falls back to an inert ".invalid" endpoint.
    val uploader = io.github.alexeygrigorev.openscan.scan.FeedbackUploader(
        settings = settings,
        appVersion = appVersionName(),
        endpoint = BuildConfig.TELEMETRY_UPLOAD_URL.ifBlank {
            io.github.alexeygrigorev.openscan.scan.FeedbackUploader.ENDPOINT
        },
        token = BuildConfig.TELEMETRY_TOKEN,
    )
    val repository = DocumentsRepository(database.openscanDao(), files, importer, uploader)

    // GitHub-release update check (the "Check for updates" seam). The flavour
    // of this install decides which published APK an available update offers:
    // release installs get openscan-<v>-release.apk, debug installs the
    // -debug.apk published alongside it.
    val updateMonitor = UpdateMonitor(
        ReleaseChecker(
            currentVersion = appVersionName(),
            preferReleaseApk = !isDebuggableInstall(),
        ),
    )

    private fun appVersionName(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun isDebuggableInstall(): Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
