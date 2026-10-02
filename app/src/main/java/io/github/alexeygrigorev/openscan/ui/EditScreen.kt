package io.github.alexeygrigorev.openscan.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.github.alexeygrigorev.openscan.scan.Images
import io.github.alexeygrigorev.openscan.scan.PageOcr
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditViewModel(
    val pageId: Long,
    private val repository: DocumentsRepository,
    private val appContext: Context,
) : ViewModel() {

    val page = MutableStateFlow<PageEntity?>(null)
    val busy = MutableStateFlow(false)
    val ocrText = MutableStateFlow<String?>(null)

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch { page.value = repository.getPage(pageId) }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(viewModel: EditViewModel, onBack: () -> Unit) {
    val page by viewModel.page.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val ocrText by viewModel.ocrText.collectAsState()
    var confirmingDelete by remember { mutableStateOf(false) }

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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (current == null) {
                CircularProgressIndicator()
            } else {
                PageCanvas(filePath = current.filePath, modifier = Modifier.fillMaxSize())
            }
        }
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
private fun PageCanvas(filePath: String, modifier: Modifier = Modifier) {
    // Re-decodes whenever the file path changes (e.g. after a rotation).
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            Images.decodeScaled(File(filePath), maxDim = 2048)
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
