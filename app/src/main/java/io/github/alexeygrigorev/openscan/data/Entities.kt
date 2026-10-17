package io.github.alexeygrigorev.openscan.data

import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * Enhancement filter applied to every page without its own override
     * ([ScanFilter.id]), or null to use the app default from settings.
     * Null by default so pre-v2 rows inherit the default automatically.
     * Stored in the scanFilter column (plain `filter` would collide with
     * SQLite's FILTER keyword).
     */
    @ColumnInfo(name = "scanFilter") val filter: String? = null,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val position: Int,
    /** Local JPEG file inside the app's private storage. */
    val filePath: String,
    val createdAt: Long,
    /**
     * Training/eval corner pairs (item 5): the detector's corners at capture
     * time ([autoCorners]) and the final user-corrected corners
     * ([userCorners]), each a JSON array of 8 numbers (TL, TR, BR, BL) or
     * null when unknown (gallery imports, legacy rows). [cornerSchema] is the
     * [PageCorners.SCHEMA_VERSION] that wrote them; 0 means "no corner data"
     * (every pre-v2 row). [imageWidth]/[imageHeight] are the stored page's
     * pixel dimensions so corners stay interpretable without decoding.
     */
    val autoCorners: String? = null,
    val userCorners: String? = null,
    val cornerSchema: Int = 0,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /**
     * Per-page enhancement filter override ([ScanFilter.id]), or null to use
     * the document filter / app default.
     */
    @ColumnInfo(name = "scanFilter") val filter: String? = null,
)

/** Library row: a document plus its page count. */
data class DocumentSummary(
    @Embedded val document: DocumentEntity,
    val pageCount: Int,
)

/**
 * One pending developer-share upload (item 0): a durable record of a PUT the
 * app still owes the telemetry backend, so failures survive process death and
 * can be retried instead of silently dropped. [kind] is one of
 * [UploadKind] ids; [filePath] is the local file to PUT; [remoteName] is the
 * server-side object name (fixed at enqueue time so retries don't duplicate);
 * [contentType] goes on the PUT; [state] is one of [UploadState] ids.
 */
@Entity(tableName = "uploads")
data class UploadEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val filePath: String,
    val remoteName: String,
    val contentType: String,
    val pageId: Long? = null,
    val documentId: Long? = null,
    val detected: Boolean = false,
    val state: String = UploadState.QUEUED,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** Stable [UploadEntry.kind] values. */
object UploadKind {
    const val PAGE_JPEG = "page-jpeg"
    const val PAGE_JSON = "page-json"
    const val ARCHIVE = "archive"
}

/** Stable [UploadEntry.state] values. */
object UploadState {
    const val QUEUED = "queued"
    const val UPLOADING = "uploading"
    const val UPLOADED = "uploaded"
    const val FAILED = "failed"
}
