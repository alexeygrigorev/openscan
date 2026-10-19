package io.github.alexeygrigorev.openscan.util

import java.util.Locale

private val WINDOWS_RESERVED_NAMES = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
)

/**
 * A document title must survive as a file name in share intents (PDF export),
 * so replace path separators and filesystem-hostile characters with spaces,
 * collapse whitespace, strip leading/trailing dots and hyphens, strip redundant
 * .pdf extensions, and escape Windows/FAT/MTP reserved device names (CON, PRN,
 * AUX, NUL, COM0-9, LPT0-9). Never returns an empty name.
 */
fun safeFileName(title: String): String {
    var stripped = title.trim()
    while (stripped.endsWith(".pdf", ignoreCase = true)) {
        stripped = stripped.dropLast(4).trimEnd(' ', '.')
    }

    var cleaned = stripped
        .map { if (it.isLetterOrDigit() || it in " ._-") it else ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\.{2,}"), ".")
        .trim(' ', '.', '-')
        .take(80)
        .trim(' ', '.', '-')

    val baseName = cleaned.substringBefore('.')
    if (baseName.uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES) {
        val extension = cleaned.substringAfter('.', "")
        cleaned = if (extension.isEmpty()) {
            "${baseName}_"
        } else {
            "${baseName}_.$extension"
        }
    }

    return cleaned.ifEmpty { "openscan" }
}

