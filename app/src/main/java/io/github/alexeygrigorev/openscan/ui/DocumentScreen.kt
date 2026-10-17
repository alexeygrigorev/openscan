package io.github.alexeygrigorev.openscan.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexeygrigorev.openscan.data.DocumentEntity
import io.github.alexeygrigorev.openscan.data.DocumentFiles
import io.github.alexeygrigorev.openscan.data.DocumentsRepository
import io.github.alexeygrigorev.openscan.data.PageEntity
import io.github.alexeygrigorev.openscan.data.SettingsRepository
import io.github.alexeygrigorev.openscan.scan.DebugArchive
import io.github.alexeygrigorev.openscan.scan.Images
import io.github.alexeygrigorev.openscan.scan.JpegExporter
import io.github.alexeygrigorev.openscan.scan.PageCorners
import io.github.alexeygrigorev.openscan.scan.PageOcr
import io.github.alexeygrigorev.openscan.scan.PdfExporter
import io.github.alexeygrigorev.openscan.scan.ScanFilter
import io.github.alexeygrigorev.openscan.scan.ScanFilters
import io.github.alexeygrigorev.openscan.util.safeFileName
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DocumentViewModel(
    val documentId: Long,
    private val repository: DocumentsRepository,
    private val files: DocumentFiles,
    private val settings: SettingsRepository,
    private val appVersion: String,
    private val appContext: Context,
) : ViewModel() {

    val document: StateFlow<DocumentEntity?> = repository.observeDocument(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pages: StateFlow<List<PageEntity>> = repository.observePages(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** App-wide enhancement default (item 6). */
    val appFilterDefault: StateFlow<String> = settings.filterDefault
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanFilter.DEFAULT.id)

    /** Developer mode unlocks the debug-archive actions (item 1). */
    val developerMode: StateFlow<Boolean> = settings.developerModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val exporting = MutableStateFlow(false)
    val exportError = MutableStateFlow<String?>(null)

    /** A finished PDF waiting for the user to pick Share/Print/Open/Save. */
    val exportReady = MutableStateFlow<File?>(null)

    /** Explicit share handoff (Share button / debug-archive export). */
    val shareRequest = MutableStateFlow<ShareRequest?>(null)

    /** One-shot snackbar notices (saved-to path, archive queued, …). */
    val notice = MutableStateFlow<String?>(null)
    val ocrText = MutableStateFlow<String?>(null)

    data class ShareRequest(val file: File, val mimeType: String, val label: String)

    /** Serializes page moves so rapid taps can't interleave read-modify-write cycles. */
    private val moveMutex = Mutex()

    fun renameDocument(title: String) {
        viewModelScope.launch { repository.renameDocument(documentId, title) }
    }

    fun deleteDocument(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.deleteDocument(documentId)
            onDeleted()
        }
    }

    fun deletePage(page: PageEntity) {
        viewModelScope.launch { repository.deletePage(page) }
    }

    fun rotatePage(page: PageEntity) {
        viewModelScope.launch { repository.rotatePage(page) }
    }

    fun movePage(page: PageEntity, delta: Int) {
        viewModelScope.launch {
            moveMutex.withLock { repository.movePage(page, delta) }
        }
    }

    fun recognizeText(page: PageEntity) {
        viewModelScope.launch {
            ocrText.value = runCatching { PageOcr.recognize(appContext, File(page.filePath)) }
                .getOrElse { "" }
        }
    }

    fun dismissOcr() {
        ocrText.value = null
    }

    fun dismissExportError() {
        exportError.value = null
    }

    fun dismissExportReady() {
        exportReady.value = null
    }

    fun consumeShareRequest() {
        shareRequest.value = null
    }

    fun consumeNotice() {
        notice.value = null
    }

    /** Effective enhancement filter for every page (item 6). */
    suspend fun effectiveFilters(): List<ScanFilter> {
        val doc = repository.getDocument(documentId)
        val default = settings.filterDefault.first()
        return repository.getPages(documentId).map { page ->
            ScanFilter.effective(page.filter, doc?.filter, default)
        }
    }

    /** Renders every page into one enhanced PDF, then offers the actions. */
    fun exportPdf() {
        if (exporting.value) return
        exporting.value = true
        exportError.value = null
        viewModelScope.launch {
            try {
                val title = document.value?.title ?: DocumentsRepository.defaultTitle()
                val pageFiles = collectPageFiles()
                val filters = effectiveFilters()
                val out = files.sharedPdfFile(title)
                withContext(Dispatchers.IO) {
                    FileOutputStream(out).use { stream -> PdfExporter.exportPdf(pageFiles, filters, stream) }
                }
                exportReady.value = out
            } catch (t: Throwable) {
                exportError.value = t.message ?: "PDF export failed"
            } finally {
                exporting.value = false
            }
        }
    }

    /** Document enhancement (item 6); also remembered as the app default. */
    fun setDocumentFilter(filterId: String?) {
        viewModelScope.launch {
            repository.setDocumentFilter(documentId, filterId)
            if (filterId != null) settings.setFilterDefault(filterId)
        }
    }

    /** Debug archive export (item 1): ZIP via the share sheet. */
    fun exportDebugArchive() {
        if (exporting.value) return
        exporting.value = true
        exportError.value = null
        viewModelScope.launch {
            try {
                val zip = buildDebugArchive()
                shareRequest.value = ShareRequest(zip, "application/zip", "Share debug archive")
            } catch (t: Throwable) {
                exportError.value = t.message ?: "Archive export failed"
            } finally {
                exporting.value = false
            }
        }
    }

    /** Debug archive upload (item 1): ZIP into the developer-share queue. */
    fun uploadDebugArchive() {
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            try {
                val enabled = settings.feedbackUploadEnabled.first()
                if (!enabled) {
                    notice.value = "Turn on “Send pictures…” in Settings first — nothing was queued."
                    return@launch
                }
                val zip = buildDebugArchive()
                repository.enqueueArchive(zip, documentId)
                notice.value = "Debug archive queued — see Settings for upload status."
            } catch (t: Throwable) {
                exportError.value = t.message ?: "Archive upload failed"
            } finally {
                exporting.value = false
            }
        }
    }

    private suspend fun buildDebugArchive(): File = withContext(Dispatchers.IO) {
        val title = document.value?.title ?: repository.getDocument(documentId)?.title
            ?: DocumentsRepository.defaultTitle()
        val doc = repository.getDocument(documentId)
        val default = settings.filterDefault.first()
        val entries = repository.getPages(documentId).map { page ->
            val raw = files.originalFile(documentId, page.id).takeIf { it.exists() }
            val stored = Images.bounds(File(page.filePath)) ?: (0 to 0)
            DebugArchive.Entry(
                pageId = page.id,
                position = page.position,
                pageFile = File(page.filePath),
                rawFile = raw,
                imageWidth = page.imageWidth,
                imageHeight = page.imageHeight,
                storedWidth = stored.first,
                storedHeight = stored.second,
                autoCorners = PageCorners.decode(page.autoCorners),
                refinedCorners = PageCorners.decode(page.refinedCorners),
                userCorners = PageCorners.decode(page.userCorners),
                cornerSchema = page.cornerSchema,
                filterId = ScanFilter.effective(page.filter, doc?.filter, default).id,
            )
        }
        val out = File(
            files.exportsDir,
            "${io.github.alexeygrigorev.openscan.util.safeFileName(title)}-debug.zip",
        )
        FileOutputStream(out).use { stream ->
            DebugArchive.build(
                entries = entries.ifEmpty { error("No pages to export") },
                documentTitle = title,
                appVersion = appVersion,
                deviceModel = android.os.Build.MODEL ?: "unknown",
                exportedAt = System.currentTimeMillis(),
                out = stream,
            )
        }
        return@withContext out
    }

    /** Copies the ready PDF to the user's SAF destination; confirms inline. */
    fun saveExportTo(destination: android.net.Uri) {
        val source = exportReady.value ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(destination)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not open destination")
                }
                notice.value = "PDF saved."
            } catch (t: Throwable) {
                exportError.value = t.message ?: "Save failed"
            }
        }
    }

    /**
     * Exports pages as JPEG images: the stored page file copied as-is for a
     * single page, a zip of JPEGs for several. No re-encoding, no watermark.
     */
    fun exportJpegs() {
        if (exporting.value) return
        exporting.value = true
        exportError.value = null
        viewModelScope.launch {
            try {
                val title = document.value?.title ?: DocumentsRepository.defaultTitle()
                val pageFiles = collectPageFiles()
                if (pageFiles.size == 1) {
                    val image = files.sharedImageFile(title)
                    withContext(Dispatchers.IO) {
                        JpegExporter.exportSingle(pageFiles.first(), FileOutputStream(image))
                    }
                    shareRequest.value = ShareRequest(image, "image/jpeg", "Share image")
                } else {
                    val zip = files.sharedZipFile(title)
                    withContext(Dispatchers.IO) {
                        JpegExporter.exportZip(pageFiles, FileOutputStream(zip))
                    }
                    shareRequest.value = ShareRequest(zip, "application/zip", "Share images")
                }
            } catch (t: Throwable) {
                exportError.value = t.message ?: "JPEG export failed"
            } finally {
                exporting.value = false
            }
        }
    }

    private suspend fun collectPageFiles(): List<File> =
        pages.value
            .ifEmpty { repository.getPages(documentId) }
            .map { File(it.filePath) }
            .filter { it.exists() }
            .ifEmpty { error("No pages to export") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    viewModel: DocumentViewModel,
    onEditPage: (Long) -> Unit,
    onAddPages: () -> Unit,
    onBack: () -> Unit,
) {
    val document by viewModel.document.collectAsState()
    val pages by viewModel.pages.collectAsState()
    val exporting by viewModel.exporting.collectAsState()
    val exportError by viewModel.exportError.collectAsState()
    val exportReady by viewModel.exportReady.collectAsState()
    val shareRequest by viewModel.shareRequest.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val ocrText by viewModel.ocrText.collectAsState()
    val appFilterDefault by viewModel.appFilterDefault.collectAsState()
    val developerMode by viewModel.developerMode.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf(false) }
    var deletingDocument by remember { mutableStateOf(false) }
    var deletingPage by remember { mutableStateOf<PageEntity?>(null) }
    var reordering by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var filterPickerOpen by remember { mutableStateOf(false) }

    // Storage-access save-as for the ready PDF (item 4).
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) viewModel.saveExportTo(uri)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                // Item 7: the title is the rename affordance.
                title = {
                    Text(
                        document?.title ?: "",
                        maxLines = 1,
                        modifier = Modifier.clickable(
                            onClick = { renaming = true },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (reordering) {
                        IconButton(onClick = { reordering = false }) {
                            Icon(Icons.Filled.Check, contentDescription = "Done reordering")
                        }
                    } else {
                        IconButton(onClick = onAddPages) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add pages")
                        }
                        IconButton(
                            onClick = { viewModel.exportPdf() },
                            enabled = !exporting && pages.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.PictureAsPdf, contentDescription = "Export PDF")
                        }
                        IconButton(
                            onClick = { viewModel.exportJpegs() },
                            enabled = !exporting && pages.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = "Export images")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Document actions")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Reorder pages") },
                                    onClick = { menuOpen = false; reordering = true },
                                    enabled = pages.size > 1,
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Enhancement: ${
                                            ScanFilter.fromId(document?.filter).let {
                                                if (document?.filter == null) {
                                                    "Default (${ScanFilter.fromId(appFilterDefault).title})"
                                                } else {
                                                    it.title
                                                }
                                            }
                                            }"
                                        )
                                    },
                                    onClick = { menuOpen = false; filterPickerOpen = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = { menuOpen = false; renaming = true },
                                )
                                if (developerMode) {
                                    DropdownMenuItem(
                                        text = { Text("Export debug archive") },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.exportDebugArchive()
                                        },
                                        enabled = !exporting && pages.isNotEmpty(),
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Upload debug archive") },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.uploadDebugArchive()
                                        },
                                        enabled = !exporting && pages.isNotEmpty(),
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete document") },
                                    onClick = { menuOpen = false; deletingDocument = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (pages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("No pages", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(pages, key = { it.id }) { page ->
                    val index = pages.indexOf(page)
                    PageRow(
                        page = page,
                        filter = ScanFilter.effective(page.filter, document?.filter, appFilterDefault),
                        reordering = reordering,
                        canMoveUp = index > 0,
                        canMoveDown = index < pages.lastIndex,
                        onClick = { onEditPage(page.id) },
                        onMoveUp = { viewModel.movePage(page, -1) },
                        onMoveDown = { viewModel.movePage(page, +1) },
                        onOcr = { viewModel.recognizeText(page) },
                        onRotate = { viewModel.rotatePage(page) },
                        onDelete = { deletingPage = page },
                    )
                }
            }
        }
    }

    // Confirm a finished capture batch (set by the capture flow just before
    // it navigates here); consumed once so rotation doesn't replay it.
    LaunchedEffect(Unit) {
        BatchImport.summary.value?.let { (added, failed) ->
            BatchImport.summary.value = null
            snackbarHostState.showSnackbar(
                when {
                    added == 0 -> "No pages could be imported"
                    failed == 0 -> "Added $added page${if (added == 1) "" else "s"}"
                    else -> "Added $added page${if (added == 1) "" else "s"}, " +
                        "$failed failed to import"
                }
            )
        }
    }

    // Hand an explicit share request to the system share sheet.
    LaunchedEffect(shareRequest) {
        val request = shareRequest ?: return@LaunchedEffect
        runCatching { PdfActions.share(context, request.file, request.mimeType, request.label) }
        viewModel.consumeShareRequest()
    }

    // One-shot notices (saved-to confirmation, archive queued, …).
    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    // The finished PDF and its actions (item 4): Share, Print, Open, Save.
    exportReady?.let { pdf ->
        val title = document?.title ?: DocumentsRepository.defaultTitle()
        AlertDialog(
            onDismissRequest = { viewModel.dismissExportReady() },
            title = { Text("PDF ready") },
            text = { Text("${safeFileName(title)}.pdf — ${pages.size} page${if (pages.size == 1) "" else "s"}.") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        viewModel.shareRequest.value =
                            DocumentViewModel.ShareRequest(pdf, "application/pdf", "Share PDF")
                    }) { Text("Share") }
                    TextButton(onClick = {
                        runCatching {
                            PdfActions.print(context, pdf, safeFileName(title))
                        }
                    }) { Text("Print") }
                    TextButton(onClick = {
                        runCatching {
                            PdfActions.openWith(context, pdf)
                        }
                    }) { Text("Open with…") }
                    TextButton(onClick = {
                        saveLauncher.launch("${safeFileName(title)}.pdf")
                    }) { Text("Save") }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissExportReady() }) { Text("Close") }
            },
        )
    }

    if (filterPickerOpen) {
        FilterPickerDialog(
            title = "Document enhancement",
            selectedId = document?.filter,
            defaultLabel = ScanFilter.fromId(appFilterDefault).title,
            onPick = { id -> viewModel.setDocumentFilter(id) },
            onDismiss = { filterPickerOpen = false },
        )
    }

    val currentDocument = document
    if (renaming && currentDocument != null) {
        RenameDialog(
            initialTitle = currentDocument.title,
            onDismiss = { renaming = false },
            onConfirm = { title ->
                viewModel.renameDocument(title)
                renaming = false
            },
        )
    }

    if (deletingDocument && currentDocument != null) {
        AlertDialog(
            onDismissRequest = { deletingDocument = false },
            title = { Text("Delete document?") },
            text = {
                Text(
                    "\u201C${currentDocument.title}\u201D and its ${pages.size} " +
                        "page${if (pages.size == 1) "" else "s"} will be removed from this device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deletingDocument = false
                    viewModel.deleteDocument(onDeleted = onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingDocument = false }) { Text("Cancel") } },
        )
    }

    deletingPage?.let { page ->
        AlertDialog(
            onDismissRequest = { deletingPage = null },
            title = { Text("Delete page?") },
            text = { Text("Page ${page.position + 1} will be removed from this document.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePage(page)
                    deletingPage = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deletingPage = null }) { Text("Cancel") } },
        )
    }

    exportError?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissExportError() },
            title = { Text("Export failed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissExportError() }) { Text("OK") }
            },
        )
    }

    ocrText?.let { text ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { viewModel.dismissOcr() },
            title = { Text("Recognized text") },
            text = { Text(text.ifBlank { "No text found on this page." }) },
            confirmButton = {
                if (text.isNotBlank()) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        viewModel.dismissOcr()
                    }) { Text("Copy") }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissOcr() }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun PageRow(
    page: PageEntity,
    filter: ScanFilter,
    reordering: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onOcr: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text("Page ${page.position + 1}") },
        leadingContent = {
            PageThumbnail(
                filePath = page.filePath,
                filter = filter,
                modifier = Modifier
                    .width(72.dp)
                    .height(56.dp),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        trailingContent = {
            if (reordering) {
                Row {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move page up")
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move page down")
                    }
                }
            } else {
                Row {
                    IconButton(onClick = onOcr) {
                        Icon(Icons.Filled.TextFields, contentDescription = "Recognize text")
                    }
                    IconButton(onClick = onRotate) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete page")
                    }
                }
            }
        },
    )
}

@Composable
private fun PageThumbnail(filePath: String, filter: ScanFilter, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = filePath, key2 = filter) {
        value = withContext(Dispatchers.IO) {
            val decoded = Images.decodeScaled(File(filePath), maxDim = 512) ?: return@withContext null
            if (filter == ScanFilter.ORIGINAL) {
                decoded
            } else {
                ScanFilters.apply(decoded, filter)?.also { decoded.recycle() } ?: decoded
            }
        }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}
