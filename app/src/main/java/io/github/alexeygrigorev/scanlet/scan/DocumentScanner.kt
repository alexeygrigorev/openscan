package io.github.alexeygrigorev.scanlet.scan

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * Wrapper around the ML Kit document scanner (Play services module). The
 * scanner provides its own capture UI with auto edge detection, cropping and
 * cleanup filters — which is why Scanlet itself needs no CAMERA permission.
 *
 * Launch with [androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult];
 * the result Intent goes through [pageUris].
 */
object DocumentScanner {

    /** Scan request bound to [activity]; the Task resolves to the launch IntentSender. */
    fun startScanIntent(activity: Activity): Task<IntentSender> {
        val client = GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                .setPageLimit(MAX_PAGES)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build()
        )
        return client.getStartScanIntent(activity)
    }

    /** Page image URIs from a scanner result, in scan order. */
    fun pageUris(data: Intent?): List<Uri> {
        val result = GmsDocumentScanningResult.fromActivityResultIntent(data) ?: return emptyList()
        return result.pages.orEmpty().mapNotNull { page -> page.imageUri }
    }

    const val MAX_PAGES = 50
}
