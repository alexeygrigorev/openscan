package io.github.alexeygrigorev.openscan.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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

class CaptureViewModel(private val repository: DocumentsRepository) : ViewModel() {

    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    fun reportScannerUnavailable() {
        error.value =
            "The document scanner is unavailable. Install or update Google Play " +
                "services, or import images from the gallery instead."
    }

    /**
     * Imports the given page images into a document, in order: into [appendTo]
     * when set (the user is adding pages to an existing document), otherwise
     * into a newly created one. Pages that fail to decode are skipped; the
     * document still opens with what made it. [detected] marks pages captured
     * through the document scanner (gallery imports are manual); it only
     * annotates the opt-in telemetry upload.
     */
    fun importPages(
        uris: List<Uri>,
        onDone: (Long) -> Unit,
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
                uris.forEach { uri ->
                    runCatching { repository.importPage(documentId, uri, detected) }
                        .onFailure { error.value = "A page could not be imported: ${it.message}" }
                }
                busy.value = false
                onDone(documentId)
            } catch (t: Throwable) {
                busy.value = false
                error.value = t.message ?: "Could not create the document"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    viewModel: CaptureViewModel,
    onDone: (Long) -> Unit,
    onCancel: () -> Unit,
    documentId: Long? = null,
) {
    val busy by viewModel.busy.collectAsState()
    val error by viewModel.error.collectAsState()
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

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isEmpty()) onCancel() else viewModel.importPages(uris, onDone, appendTo = documentId)
    }

    // Launch the scanner exactly once per screen instance.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
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
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (documentId == null) "New document" else "Add pages") }) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator()
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
