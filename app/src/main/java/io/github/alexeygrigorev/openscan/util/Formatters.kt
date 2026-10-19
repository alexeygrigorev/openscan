package io.github.alexeygrigorev.openscan.util

/**
 * Formats a count of pages with proper singular/plural grammar,
 * e.g. "1 page", "3 pages", or with an optional qualifier,
 * e.g. "1 captured page", "3 captured pages".
 */
fun formatPageCount(count: Int, qualifier: String? = null): String {
    val noun = if (count == 1) "page" else "pages"
    return if (qualifier.isNullOrBlank()) {
        "$count $noun"
    } else {
        "$count $qualifier $noun"
    }
}
