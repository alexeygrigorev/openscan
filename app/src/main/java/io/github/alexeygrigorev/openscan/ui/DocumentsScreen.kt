package io.github.alexeygrigorev.openscan.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexeygrigorev.openscan.data.DocumentSummary
import io.github.alexeygrigorev.openscan.data.DocumentsRepository
import io.github.alexeygrigorev.openscan.util.formatDeleteDocumentMessage
import io.github.alexeygrigorev.openscan.util.formatPageCount
import io.github.alexeygrigorev.openscan.update.ReleaseChecker
import io.github.alexeygrigorev.openscan.update.UpdateMonitor
import io.github.alexeygrigorev.openscan.update.UpdateStatus
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
    updateMonitor: UpdateMonitor,
    onOpenDocument: (Long) -> Unit,
    onScan: () -> Unit,
    onImportImages: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val documents by viewModel.documents.collectAsState()
    val updateState by updateMonitor.state.collectAsState()
    val context = LocalContext.current

    var renaming by remember { mutableStateOf<DocumentSummary?>(null) }
    var deleting by remember { mutableStateOf<DocumentSummary?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenScan") },
                actions = {
                    IconButton(onClick = onImportImages) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Import images")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                // labeled for TalkBack and for UiAutomator: the merged FAB
                // node does not expose its Text() to accessibility queries.
                icon = { Icon(Icons.Filled.Scanner, contentDescription = "Scan") },
                text = { Text("Scan") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val update = updateState.available
            if (updateState.status == UpdateStatus.AVAILABLE && !updateState.dismissed && update != null) {
                UpdateBanner(
                    available = update,
                    onDownload = { ReleaseChecker.openReleaseUrl(context, update.downloadUrl) },
                    onNotes = { ReleaseChecker.openReleaseUrl(context, update.notesUrl) },
                    onDismiss = { updateMonitor.dismiss() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                if (documents.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No documents yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Tap Scan to create your first one.\nNo account, no watermark, nothing leaves this phone.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
            text = { Text(formatDeleteDocumentMessage(summary.document.title, summary.pageCount)) },
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
            Text("${formatPageCount(summary.pageCount)} · ${listDateFormat.format(Date(summary.document.updatedAt))}")
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Actions for ${summary.document.title}",
                    )
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
    val isValid = title.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename document") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        val trimmed = title.trim()
                        if (trimmed.isNotEmpty()) {
                            onConfirm(trimmed)
                        }
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title.trim()) },
                enabled = isValid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
