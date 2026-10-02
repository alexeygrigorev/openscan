package io.github.alexeygrigorev.scanlet.scan

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bridge from GMS Task to coroutines. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value ->
        if (continuation.isActive) continuation.resume(value)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
}

/**
 * On-device OCR via ML Kit Text Recognition v2 (bundled Latin model — fully
 * offline, unlimited, free; CamScanner paywalls exactly this).
 */
object PageOcr {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Recognizes text on a stored page JPEG. Empty string when no text found. */
    suspend fun recognize(context: Context, pageFile: File): String {
        val image = InputImage.fromFilePath(context, android.net.Uri.fromFile(pageFile))
        return recognizer.process(image).await().text
    }
}
