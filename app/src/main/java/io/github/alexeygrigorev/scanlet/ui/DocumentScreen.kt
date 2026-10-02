package io.github.alexeygrigorev.scanlet.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexeygrigorev.scanlet.data.DocumentEntity
import io.github.alexeygrigorev.scanlet.data.DocumentFiles
import io.github.alexeygrigorev.scanlet.data.DocumentsRepository
import io.github.alexeygrigorev.scanlet.data.PageEntity
import io.github.alexeygrigorev.scanlet.scan.Images
import io.github.alexeygrigorev.scanlet.scan.PageOcr
import io.github.alexeygrigorev.scanlet.scan.PdfExporter
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentViewModel(
    val documentId: Long,
    private val repository: DocumentsRepository,
    private val files: DocumentFiles,
    private val appContext: Context,
) : ViewModel() {

    val document: StateFlow<DocumentEntity?> = repository.observeDocument(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pages: StateFlow<List<PageEntity>> = repository.observePages(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val exporting = MutableStateFlow(false)
    val exportError = MutableStateFlow<String?>(null)
    val shareFile = MutableStateFlow<File?>(null)
    val ocrText = MutableStateFlow<String?>(null)

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

    /** Renders every page into one PDF in the share-ready cache directory. */
    fun exportPdf() {
        if (exporting.value) return
        exporting.value = true
        exportError.value = null
        viewModelScope.launch {
            try {
                val title = document.value?.title ?: DocumentsRepository.defaultTitle()
                val pageFiles = pages.value
                    .ifEmpty { repository.getPages(documentId) }
                    .map { File(it.filePath) }
                    .filter { it.exists() }
                if (pageFiles.isEmpty()) error("No pages to export")
                val out = files.sharedPdfFile(title)
                withContext(Dispatchers.IO) {
                    FileOutputStream(out).use { stream -> PdfExporter.exportPdf(pageFiles, stream) }
                }
                shareFile.value = out
            } catch (t: Throwable) {
                exportError.value = t.message ?: "PDF export failed"
            } finally {
                exporting.value = false
            }
        }
    }

    fun consumeShareFile() {
        shareFile.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    viewModel: DocumentViewModel,
    onEditPage: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val document by viewModel.document.collectAsState()
    val pages by viewModel.pages.collectAsState()
    val exporting by viewModel.exporting.collectAsState()
    val exportError by viewModel.exportError.collectAsState()
    val shareFile by viewModel.shareFile.collectAsState()
    val ocrText by viewModel.ocrText.collectAsState()

    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var deletingDocument by remember { mutableStateOf(false) }
    var deletingPage by remember { mutableStateOf<PageEntity?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.exportPdf() },
                        enabled = !exporting && pages.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = "Export PDF")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Document actions")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = { menuOpen = false; renaming = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete document") },
                                onClick = { menuOpen = false; deletingDocument = true },
                            )
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
                    PageRow(
                        page = page,
                        onClick = { onEditPage(page.id) },
                        onOcr = { viewModel.recognizeText(page) },
                        onRotate = { viewModel.rotatePage(page) },
                        onDelete = { deletingPage = page },
                    )
                }
            }
        }
    }

    // Hand the freshly exported PDF to the system share sheet.
    LaunchedEffect(shareFile) {
        val file = shareFile ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(send, "Share PDF")) }
        viewModel.consumeShareFile()
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
    onClick: () -> Unit,
    onOcr: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text("Page ${page.position + 1}") },
        leadingContent = {
            PageThumbnail(
                filePath = page.filePath,
                modifier = Modifier
                    .width(72.dp)
                    .height(56.dp),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        trailingContent = {
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
        },
    )
}

@Composable
private fun PageThumbnail(filePath: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            Images.decodeScaled(File(filePath), maxDim = 512)
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
