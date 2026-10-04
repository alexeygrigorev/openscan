package io.github.alexeygrigorev.openscan.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.alexeygrigorev.openscan.data.DocumentsRepository
import io.github.alexeygrigorev.openscan.scan.DocumentScanner
import io.github.alexeygrigorev.openscan.scan.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * One-shot result of a capture batch: (pages added, pages failed). [DocumentScreen]
 * consumes it once and confirms the batch with a snackbar — nav arguments can't
 * carry it, because the append flow returns to an existing entry via popBackStack.
 */
object BatchImport {
    val summary = MutableStateFlow<Pair<Int, Int>?>(null)
}

class CaptureViewModel(private val repository: DocumentsRepository) : ViewModel() {

    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** (next page index, total) while a batch import runs; null when idle. */
    val progress = MutableStateFlow<Pair<Int, Int>?>(null)

    fun reportScannerUnavailable() {
        error.value =
            "The document scanner is unavailable. Install or update Google Play " +
                "services, or import images from the gallery instead."
    }

    /**
     * Imports the given page images into a document, in order: into [appendTo]
     * when set (the user is adding pages to an existing document), otherwise
     * into a newly created one. Pages that fail to decode are skipped and
     * counted; the document still opens with what made it. [detected] marks
     * pages captured through the document scanner (gallery imports are
     * manual); it only annotates the opt-in telemetry upload.
     */
    fun importPages(
        uris: List<Uri>,
        onDone: (documentId: Long, added: Int, failed: Int) -> Unit,
        detected: Boolean = false,
        appendTo: Long? = null,
    ) {
        if (uris.isEmpty() || busy.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                // The target may have been deleted while the scanner was open;
                // fall back to a fresh document rather than orphan the pages.
                val documentId = appendTo
                    ?.takeIf { repository.getDocument(it) != null }
                    ?: repository.createDocument()
                var failed = 0
                uris.forEachIndexed { index, uri ->
                    progress.value = index to uris.size
                    runCatching { repository.importPage(documentId, uri, detected) }
                        .onFailure { failed++ }
                }
                busy.value = false
                progress.value = null
                onDone(documentId, uris.size - failed, failed)
            } catch (t: Throwable) {
                busy.value = false
                progress.value = null
                error.value = t.message ?: "Could not create the document"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    onDone: (documentId: Long, added: Int, failed: Int) -> Unit,
    onCancel: () -> Unit,
    documentId: Long? = null,
    galleryOnly: Boolean = false,
) {
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val activity = LocalContext.current

    // The scan result arrives here when the Play services scanner activity
    // finishes; an empty result means the user backed out of the scanner.
    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = DocumentScanner.pageUris(result.data)
        if (uris.isEmpty()) onCancel()
        else viewModel.importPages(uris, onDone, detected = true, appendTo = documentId)
    }

    // A batch can be large; the picker allows a generous multi-select.
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isEmpty()) onCancel() else viewModel.importPages(uris, onDone, appendTo = documentId)
    }

    // Swallow back while the batch is running: popping would dispose this
    // screen's view model and cancel the import half-way through.
    BackHandler(enabled = busy) {}

    // Launch one importer exactly once per screen instance: the photo picker
    // straight away in gallery-only mode (the library's Import images entry),
    // otherwise the Play services document scanner.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        if (galleryOnly) {
            pickLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
            return@LaunchedEffect
        }
        val componentActivity = activity as? ComponentActivity
        if (componentActivity == null) {
            viewModel.reportScannerUnavailable()
        } else {
            try {
                val sender = DocumentScanner.startScanIntent(componentActivity).await()
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The Task failed — Play services missing, outdated, or
                // the scanner module not yet downloaded on this device.
                viewModel.reportScannerUnavailable()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            galleryOnly -> "Import images"
                            documentId == null -> "New document"
                            else -> "Add pages"
                        }
                    )
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                val batch = progress
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(48.dp),
                ) {
                    if (batch == null) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            "Importing page ${batch.first + 1} of ${batch.second}…",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        LinearProgressIndicator(
                            progress = {
                                if (batch.second == 0) 0f
                                else (batch.first + 1f) / batch.second
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Text(
                        error ?: "Opening the scanner…",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = {
                        pickLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Text("Import images from the gallery")
                    }
                    Button(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
