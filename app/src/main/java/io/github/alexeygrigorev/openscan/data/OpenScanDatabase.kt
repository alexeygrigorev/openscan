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
    entities = [DocumentEntity::class, PageEntity::class, UploadEntry::class],
    version = 4,
    exportSchema = false,
)
abstract class OpenScanDatabase : RoomDatabase() {
    abstract fun openscanDao(): OpenScanDao

    companion object {
        /**
         * v1 → v2 (feedback v0.2.4): nullable corner/filter columns plus the
         * durable upload queue. Every addition is nullable or has a default,
         * so updating from v0.2.3 keeps all rows: legacy pages read back with
         * cornerSchema 0 ("no corner data") and null filters (app default).
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN scanFilter TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN autoCorners TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN userCorners TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN cornerSchema INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN imageWidth INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN imageHeight INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN scanFilter TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS uploads (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        kind TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        remoteName TEXT NOT NULL,
                        contentType TEXT NOT NULL,
                        pageId INTEGER,
                        documentId INTEGER,
                        detected INTEGER NOT NULL DEFAULT 0,
                        state TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_uploads_state ON uploads(state)")
            }
        }

        /**
         * v2 → v3 (feedback v0.2.4, item 8): the refined-corner slot of the
         * training triple. Nullable, so every existing row keeps working.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN refinedCorners TEXT")
            }
        }

        /**
         * v3 → v4 (feedback v0.2.4, item 11): the needs-check flag for
         * refinement/re-detect review. NOT NULL DEFAULT 0 keeps old rows
         * unflagged.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN needsCheck INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
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
        Room.databaseBuilder(appContext, OpenScanDatabase::class.java, "openscan.db")
            .addMigrations(
                OpenScanDatabase.MIGRATION_1_2,
                OpenScanDatabase.MIGRATION_2_3,
                OpenScanDatabase.MIGRATION_3_4,
            )
            .build()

    val files = DocumentFiles(appContext)
    val importer = io.github.alexeygrigorev.openscan.scan.PageImporter(appContext)
    val settings: SettingsRepository =
        DataStoreSettingsRepository(DataStoreSettingsRepository.createDefaultDataStore(appContext))

    // Endpoint/token come in at build time (OPENSCAN_TELEMETRY_UPLOAD_URL /
    // OPENSCAN_TELEMETRY_UPLOAD_TOKEN gradle properties or env vars); when
    // absent the uploader falls back to an inert ".invalid" endpoint. The
    // queue rows live in the same database so uploads survive process death.
    val uploader = io.github.alexeygrigorev.openscan.scan.FeedbackUploader(
        settings = settings,
        appVersion = appVersionName(),
        endpoint = BuildConfig.TELEMETRY_UPLOAD_URL.ifBlank {
            io.github.alexeygrigorev.openscan.scan.FeedbackUploader.ENDPOINT
        },
        token = BuildConfig.TELEMETRY_TOKEN,
        dao = database.openscanDao(),
        workDir = File(appContext.cacheDir, "upload-queue").apply { mkdirs() },
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

    /** Version label for exports and the debug-archive markup. */
    val appVersion: String get() = appVersionName()

    private fun isDebuggableInstall(): Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
