package io.github.alexeygrigorev.openscan.util

/**
 * A document title must survive as a file name in share intents (PDF export),
 * so replace path separators and filesystem-hostile characters with spaces,
 * collapse whitespace, and strip leading/trailing dots so hidden files or
 * trailing dot issues do not occur on Android filesystems. Never returns an empty name.
 */
fun safeFileName(title: String): String {
    val cleaned = title
        .map { if (it.isLetterOrDigit() || it in " ._-") it else ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '.')
        .take(80)
        .trim(' ', '.')
    return cleaned.ifEmpty { "openscan" }
}
