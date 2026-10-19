package io.github.alexeygrigorev.openscan.util

import java.io.File
import java.util.Locale

/**
 * Formats an item count with proper singular/plural grammar,
 * e.g. "1 page", "3 pages", "1 new crop", "2 new crops".
 */
fun formatCount(
    count: Int,
    singular: String,
    plural: String = "${singular}s",
    qualifier: String? = null,
): String {
    val noun = if (count == 1) singular else plural
    return if (qualifier.isNullOrBlank()) {
        "$count $noun"
    } else {
        "$count ${qualifier.trim()} $noun"
    }
}

/**
 * Formats a count of pages with proper singular/plural grammar,
 * e.g. "1 page", "3 pages", or with an optional qualifier,
 * e.g. "1 captured page", "3 captured pages".
 */
fun formatPageCount(count: Int, qualifier: String? = null): String =
    formatCount(count, "page", "pages", qualifier)

/**
 * Formats a byte count into a human-readable file size string using binary units
 * (B, KB, MB, GB, TB, PB), e.g. "0 B", "512 B", "1 KB", "1.5 KB", "2.4 MB".
 *
 * Non-positive byte counts format as "0 B". Values under 1 KB format as whole bytes.
 * Values >= 1 KB round to at most one decimal place, omitting redundant ".0" for
 * exact integer units (e.g. "1 MB" instead of "1.0 MB") and gracefully rolling over
 * boundary values near 1024.0 (e.g. 1048570 B formats as "1 MB" rather than "1024 KB").
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unitIndex = 0

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    var display = String.format(Locale.ROOT, "%.1f", value)
    if (display == "1024.0" && unitIndex < units.lastIndex) {
        unitIndex++
        value = 1.0
        display = "1.0"
    }

    val result = if (display.endsWith(".0")) display.dropLast(2) else display
    return "$result ${units[unitIndex]}"
}

/** Formats the size of [file] into a human-readable string. */
fun formatFileSize(file: File): String = formatFileSize(file.length())

/**
 * Formats a 0-indexed page position and total count for display,
 * e.g. "Page 1 of 5", "Page 3 of 5", or "Page 1" when [totalPages] <= 1.
 */
fun formatPagePosition(position: Int, totalPages: Int = 0): String {
    val pageNumber = maxOf(0, position) + 1
    return if (totalPages > 1) {
        "Page $pageNumber of $totalPages"
    } else {
        "Page $pageNumber"
    }
}

/**
 * Formats an accessible action description for screen readers (TalkBack),
 * e.g. "Delete page 2 of 5", "Move page 2 of 5 up", "Recognize text on page 2 of 5",
 * or "Delete page 2" when [totalPages] <= 1.
 */
fun formatPageActionDescription(
    action: String,
    position: Int,
    totalPages: Int = 0,
): String {
    val pageNumber = maxOf(0, position) + 1
    val pageRef = if (totalPages > 1) "page $pageNumber of $totalPages" else "page $pageNumber"
    val trimmedAction = action.trim()
    return when {
        trimmedAction.endsWith("up", ignoreCase = true) -> {
            val prefix = trimmedAction.dropLast(2).trimEnd()
            if (prefix.isEmpty()) "$pageRef up" else "$prefix $pageRef up"
        }
        trimmedAction.endsWith("down", ignoreCase = true) -> {
            val prefix = trimmedAction.dropLast(4).trimEnd()
            if (prefix.isEmpty()) "$pageRef down" else "$prefix $pageRef down"
        }
        trimmedAction.endsWith("on", ignoreCase = true) ||
            trimmedAction.endsWith("for", ignoreCase = true) -> {
            "$trimmedAction $pageRef"
        }
        else -> "$trimmedAction $pageRef"
    }
}

/**
 * Formats the confirmation message when deleting a document, cleanly handling
 * documents with 0 pages, 1 page, or multiple pages.
 */
fun formatDeleteDocumentMessage(title: String, pageCount: Int): String {
    val cleanTitle = title.trim()
    return if (pageCount > 0) {
        "\u201C$cleanTitle\u201D and its ${formatPageCount(pageCount)} will be removed from this device."
    } else {
        "\u201C$cleanTitle\u201D will be removed from this device."
    }
}

/**
 * Formats the confirmation message for a finished batch import.
 */
fun formatBatchImportSummary(added: Int, failed: Int): String = when {
    added <= 0 && failed <= 0 -> "No pages were imported"
    added <= 0 -> "${formatCount(failed, "page")} could not be imported"
    failed <= 0 -> "Added ${formatPageCount(added)}"
    else -> "Added ${formatPageCount(added)}, ${formatCount(failed, "page")} failed to import"
}
