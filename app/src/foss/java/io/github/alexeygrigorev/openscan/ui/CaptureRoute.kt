package io.github.alexeygrigorev.openscan.ui

import androidx.compose.runtime.Composable

/**
 * The foss flavor's capture flow: our own CameraX batch capture
 * ([BatchScanRoute]) — continuous auto-capture until the user stops, then a
 * review/correct grid. The gallery import entry point is shared main code.
 */
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
    } else {
        BatchScanRoute(documentId, onDone, onCancel)
    }
}
