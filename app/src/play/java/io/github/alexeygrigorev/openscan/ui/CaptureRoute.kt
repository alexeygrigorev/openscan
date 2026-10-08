package io.github.alexeygrigorev.openscan.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.alexeygrigorev.openscan.scan.DocumentScanner
import io.github.alexeygrigorev.openscan.scan.await
import kotlinx.coroutines.CancellationException

/**
 * The play flavor's capture flow: the Play services document scanner does the
 * capturing (its own multi-page UI), this screen only launches it and shows
 * the shared import progress afterwards. Capture runs inside Play services,
 * which is why the play build never carries the CAMERA permission
 * (docs/FEATURES.md product rule 3).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureRoute(
    viewModel: CaptureViewModel,
    documentId: Long?,
    galleryOnly: Boolean,
    onDone: (documentId: Long, added: Int, failed: Int) -> Unit,
    onCancel: () -> Unit,
) {
    if (galleryOnly) {
        GalleryImportRoute(viewModel, onDone, onCancel)
        return
    }

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

    // Fallback when the scanner is unavailable: straight to the gallery.
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 100)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importPages(uris, onDone, appendTo = documentId)
    }

    // Swallow back while the batch is running: popping would dispose this
    // screen's view model and cancel the import half-way through.
    BackHandler(enabled = busy) {}

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (documentId == null) "New document" else "Add pages") }) },
    ) { padding ->
        CaptureBody(
            busy = busy,
            progress = progress,
            error = error,
            waitingText = "Opening the scanner…",
            onPickGallery = {
                pickLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onCancel = onCancel,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }

    // Launch the scanner exactly once per screen instance.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
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
