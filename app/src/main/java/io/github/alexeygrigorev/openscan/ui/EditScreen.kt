package io.github.alexeygrigorev.openscan.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import io.github.alexeygrigorev.openscan.data.DocumentsRepository
import io.github.alexeygrigorev.openscan.data.PageEntity
import io.github.alexeygrigorev.openscan.data.SettingsRepository
import io.github.alexeygrigorev.openscan.scan.Images
import io.github.alexeygrigorev.openscan.scan.PageOcr
import io.github.alexeygrigorev.openscan.scan.ScanFilter
import io.github.alexeygrigorev.openscan.scan.ScanFilters
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditViewModel(
    val pageId: Long,
    private val repository: DocumentsRepository,
    private val settings: SettingsRepository,
    private val appContext: Context,
) : ViewModel() {

    val page = MutableStateFlow<PageEntity?>(null)
    val busy = MutableStateFlow(false)
    val ocrText = MutableStateFlow<String?>(null)

    /** Re-detect in flight / last outcome (item 11). */
    val redetecting = MutableStateFlow(false)
    val redetectResult = MutableStateFlow<DocumentsRepository.RedetectOutcome?>(null)
    val redetectError = MutableStateFlow<String?>(null)

    /** Owning document's filter (page default when the page has no override). */
    val documentFilter: StateFlow<String?> = MutableStateFlow(null)

    /** App-wide enhancement default. */
    val appFilterDefault: StateFlow<String> = settings.filterDefault
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ScanFilter.DEFAULT.id,
        )

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val loaded = repository.getPage(pageId)
            page.value = loaded
            (documentFilter as MutableStateFlow).value =
                loaded?.let { repository.getDocument(it.documentId)?.filter }
        }
    }

    /** Bakes a 90° clockwise rotation into the stored JPEG, then reloads. */
    fun rotate() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            page.value?.let { repository.rotatePage(it) }
            page.value = repository.getPage(pageId)
            busy.value = false
        }
    }

    fun recognizeText() {
        val current = page.value ?: return
        viewModelScope.launch {
            ocrText.value = runCatching { PageOcr.recognize(appContext, File(current.filePath)) }
                .getOrElse { "" }
        }
    }

    fun dismissOcr() {
        ocrText.value = null
    }

    fun deletePage(onDeleted: () -> Unit) {
        viewModelScope.launch {
            page.value?.let { repository.deletePage(it) }
            onDeleted()
        }
    }

    /** Per-page enhancement override (item 6); also remembered as default. */
    fun setPageFilter(filterId: String?) {
        viewModelScope.launch {
            repository.setPageFilter(pageId, filterId)
            if (filterId != null) settings.setFilterDefault(filterId)
            page.value = repository.getPage(pageId)
        }
    }

    /** Re-runs detection + refinement over the kept original (item 11). */
    fun redetect() {
        if (busy.value || redetecting.value) return
        redetecting.value = true
        redetectError.value = null
        viewModelScope.launch {
            try {
                redetectResult.value = repository.redetectPage(pageId)
                page.value = repository.getPage(pageId)
            } catch (t: Throwable) {
                redetectError.value = t.message ?: "Re-detect failed"
            } finally {
                redetecting.value = false
            }
        }
    }

    fun dismissRedetect() {
        redetectResult.value = null
        redetectError.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(viewModel: EditViewModel, onBack: () -> Unit) {
    val page by viewModel.page.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val ocrText by viewModel.ocrText.collectAsState()
    val docFilter by viewModel.documentFilter.collectAsState()
    val appDefault by viewModel.appFilterDefault.collectAsState()
    val redetecting by viewModel.redetecting.collectAsState()
    val redetectResult by viewModel.redetectResult.collectAsState()
    val redetectError by viewModel.redetectError.collectAsState()
    var confirmingDelete by remember { mutableStateOf(false) }
    var filterPickerOpen by remember { mutableStateOf(false) }
    val effective = ScanFilter.effective(page?.filter, docFilter, appDefault)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit page") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { filterPickerOpen = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = "Enhancement: ${effective.title}")
                    }
                    IconButton(onClick = { viewModel.redetect() }, enabled = !busy && !redetecting) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-detect corners")
                    }
                    IconButton(onClick = { viewModel.rotate() }, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate 90 degrees")
                    }
                    IconButton(onClick = { viewModel.recognizeText() }) {
                        Icon(Icons.Filled.TextFields, contentDescription = "Recognize text")
                    }
                    IconButton(onClick = { confirmingDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete page")
                    }
                },
            )
        },
    ) { padding ->
        val current = page
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Flagged crops (item 8b/11) ask for review right here.
            if (current?.needsCheck == true) {
                Text(
                    "Detection flagged this crop — review it, adjust, or re-detect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (redetecting) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Re-detecting…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (current == null) {
                    CircularProgressIndicator()
                } else {
                    PageCanvas(
                        filePath = current.filePath,
                        filter = effective,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (filterPickerOpen) {
        FilterPickerDialog(
            title = "Page enhancement",
            selectedId = page?.filter,
            defaultLabel = ScanFilter.effective(null, docFilter, appDefault).title,
            onPick = { id -> viewModel.setPageFilter(id) },
            onDismiss = { filterPickerOpen = false },
        )
    }

    redetectResult?.let { outcome ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissRedetect() },
            title = { Text("Corners re-detected") },
            text = {
                Text(
                    buildString {
                        append(
                            if (outcome.cropChanged) "Crop updated from the kept photo."
                            else "No better crop found — kept as is."
                        )
                        if (outcome.needsCheck) append(" Review the crop above.")
                        if (outcome.usedStoredPage) {
                            append(" No original was kept for this page, so detection ran on the stored page.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRedetect() }) { Text("OK") }
            },
        )
    }

    redetectError?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissRedetect() },
            title = { Text("Re-detect failed") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissRedetect() }) { Text("OK") }
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete page?") },
            text = { Text("This page will be removed from its document.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    viewModel.deletePage(onDeleted = onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
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
private fun PageCanvas(filePath: String, filter: ScanFilter, modifier: Modifier = Modifier) {
    // Re-decodes whenever the file path or filter changes.
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = filePath, key2 = filter) {
        value = withContext(Dispatchers.IO) {
            val decoded = Images.decodeScaled(File(filePath), maxDim = 2048) ?: return@withContext null
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
            contentDescription = "Scanned page",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
