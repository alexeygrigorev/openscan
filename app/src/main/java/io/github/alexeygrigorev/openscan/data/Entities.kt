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
     * Training/eval corner triple (items 5+8): the live detector's corners at
     * capture time ([autoCorners]), the post-capture refinement ([refinedCorners]),
     * and the final user-corrected corners ([userCorners], null when the user
     * never touched the quad), each a JSON array of 8 numbers (TL, TR, BR, BL)
     * or null when unknown (gallery imports, legacy rows, rotated pages).
     * [cornerSchema] is the [PageCorners.SCHEMA_VERSION] that wrote them, or
     * null for "no corner data" (every pre-v2 row). [imageWidth]/[imageHeight]
     * are the dimensions of the coordinate space the corners live in — the
     * capture frame when quads are present, the stored page otherwise — or
     * null when unknown. All nullable so the v1→v2 migration adds plain
     * columns with no defaults (Room validates migrated schemas exactly).
     */
    val autoCorners: String? = null,
    val refinedCorners: String? = null,
    val userCorners: String? = null,
    val cornerSchema: Int? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    /**
     * Per-page enhancement filter override ([ScanFilter.id]), or null to use
     * the document filter / app default.
     */
    @ColumnInfo(name = "scanFilter") val filter: String? = null,
    /**
     * Detection flagged this page's crop (item 8b/11): the user should review
     * it. Set by post-capture refinement and re-detect, cleared by manual
     * edits, rotation and plausible re-detects. Null (all pre-v4 rows) reads
     * as unflagged. Nullable so the migration adds a plain column.
     */
    val needsCheck: Boolean? = null,
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
@Entity(
    tableName = "uploads",
    indices = [Index(value = ["state"], name = "index_uploads_state")],
)
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
