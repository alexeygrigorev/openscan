package io.github.alexeygrigorev.openscan.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexeygrigorev.openscan.data.DocumentSummary
import io.github.alexeygrigorev.openscan.data.DocumentsRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DocumentsViewModel(private val repository: DocumentsRepository) : ViewModel() {

    val documents: StateFlow<List<DocumentSummary>> = repository.observeDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Imports gallery images as a brand-new document; [onDone] receives its id. */
    fun importIntoNewDocument(uris: List<Uri>, onDone: (Long) -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val documentId = repository.createDocument()
            uris.forEach { uri -> runCatching { repository.importPage(documentId, uri) } }
            onDone(documentId)
        }
    }

    fun renameDocument(id: Long, title: String) {
        viewModelScope.launch { repository.renameDocument(id, title) }
    }

    fun deleteDocument(id: Long) {
        viewModelScope.launch { repository.deleteDocument(id) }
    }
}

private val listDateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    viewModel: DocumentsViewModel,
    onOpenDocument: (Long) -> Unit,
    onScan: () -> Unit,
) {
    val documents by viewModel.documents.collectAsState()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        viewModel.importIntoNewDocument(uris, onOpenDocument)
    }

    var renaming by remember { mutableStateOf<DocumentSummary?>(null) }
    var deleting by remember { mutableStateOf<DocumentSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenScan") },
                actions = {
                    IconButton(onClick = {
                        importLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Import images")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Filled.Scanner, contentDescription = null) },
                text = { Text("Scan") },
            )
        },
    ) { padding ->
        if (documents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No documents yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap Scan to create your first one.\nNo account, no watermark, nothing leaves this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(documents, key = { it.document.id }) { summary ->
                    DocumentRow(
                        summary = summary,
                        onClick = { onOpenDocument(summary.document.id) },
                        onRename = { renaming = summary },
                        onDelete = { deleting = summary },
                    )
                }
            }
        }
    }

    renaming?.let { summary ->
        RenameDialog(
            initialTitle = summary.document.title,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                viewModel.renameDocument(summary.document.id, title)
                renaming = null
            },
        )
    }

    deleting?.let { summary ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete document?") },
            text = { Text("\u201C${summary.document.title}\u201D and its ${summary.pageCount} pages will be removed from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDocument(summary.document.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DocumentRow(
    summary: DocumentSummary,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(summary.document.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text("${summary.pageCount} page${if (summary.pageCount == 1) "" else "s"} · ${listDateFormat.format(Date(summary.document.updatedAt))}")
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Document actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        },
    )
}

@Composable
fun RenameDialog(initialTitle: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename document") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(title) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
