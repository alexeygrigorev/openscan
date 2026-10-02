package io.github.alexeygrigorev.scanlet.util

/**
 * A document title must survive as a file name in share intents (PDF export),
 * so replace path separators and filesystem-hostile characters with spaces
 * and collapse the whitespace. Never returns an empty name.
 */
fun safeFileName(title: String): String {
    val cleaned = title
        .map { if (it.isLetterOrDigit() || it in " ._-") it else ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
    return cleaned.ifEmpty { "scanlet" }
}
