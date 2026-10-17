package io.github.alexeygrigorev.openscan.ui

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Post-export PDF actions (item 4): print, open-with, and the share intent.
 * Saving lives in the screens (SAF needs an activity-result launcher).
 */
object PdfActions {

    /** Sends the PDF to Android's print framework (PrintManager). */
    fun print(context: Context, file: File, jobName: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(jobName, PdfPrintAdapter(file), PrintAttributes.Builder().build())
    }

    /** Opens the PDF in the user's viewer (ACTION_VIEW chooser, e.g. Acrobat). */
    fun openWith(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(view, "Open with…"))
    }

    /** Shares any exported file through the system share sheet. */
    fun share(context: Context, file: File, mimeType: String, label: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, label))
    }

    /**
     * Adapter for an already-rendered PDF file: layout reports an opaque
     * document, write copies the bytes to the spooler. Page count stays
     * unknown — the file, not the framework, owns pagination.
     */
    class PdfPrintAdapter(private val file: File) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: LayoutResultCallback?,
            extras: android.os.Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(file.nameWithoutExtension)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback?.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: android.os.CancellationSignal?,
            callback: WriteResultCallback?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onWriteCancelled()
                return
            }
            try {
                requireNotNull(destination) { "no destination" }
                file.inputStream().use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
                }
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (t: Throwable) {
                callback?.onWriteFailed(t.message)
            }
        }
    }
}
