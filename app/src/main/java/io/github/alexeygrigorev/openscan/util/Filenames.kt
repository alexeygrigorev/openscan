package io.github.alexeygrigorev.openscan.util

import java.text.Normalizer
import java.util.Locale

private val WINDOWS_RESERVED_NAMES = setOf(
    "CON", "PRN", "AUX", "NUL",
    "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
    "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
)

private val REDUNDANT_EXTENSIONS = listOf(
    ".pdf", ".jpg", ".jpeg", ".png", ".zip",
    ".webp", ".heic", ".heif", ".bmp", ".tiff", ".tif",
)

private val COMBINING_MARK_TYPES = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
)

/**
 * A document title must survive as a file name across share intents, exports
 * (PDF, JPEG, ZIP), and cross-platform filesystems (Linux, Windows, FAT32/exFAT, MTP).
 *
 * Normalizes Unicode to NFC (so decomposed diacritics compose into valid letters),
 * drops orphan combining marks, strips redundant image/export extensions (.pdf, .jpg,
 * .jpeg, .png, .zip, .webp, .heic, .heif, .bmp, .tiff, .tif), replaces filesystem-hostile
 * characters with spaces, collapses whitespace and dots (including space-dot combinations
 * invalid on Windows), trims leading/trailing dots and hyphens, caps length at 80 characters
 * without splitting surrogate pairs, and escapes Windows/FAT/MTP reserved device names
 * (CON, PRN, AUX, NUL, COM0-9, LPT0-9). Falls back to "openscan" if the result contains
 * no alphanumeric characters. Never returns an empty name.
 */
fun safeFileName(title: String): String {
    var stripped = Normalizer.normalize(title.trim(), Normalizer.Form.NFC)
    var changed = true
    while (changed) {
        changed = false
        for (ext in REDUNDANT_EXTENSIONS) {
            if (stripped.endsWith(ext, ignoreCase = true)) {
                stripped = stripped.dropLast(ext.length).trimEnd(' ', '.')
                changed = true
                break
            }
        }
    }

    var cleaned = stripped
        .filterNot { Character.getType(it.code) in COMBINING_MARK_TYPES }
        .map { if (it.isLetterOrDigit() || it in " ._-") it else ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+\\."), ".")
        .replace(Regex("\\.\\s+"), ".")
        .replace(Regex("\\.{2,}"), ".")
        .trim(' ', '.', '-')
        .take(80)
        .let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }
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

    return if (cleaned.none { it.isLetterOrDigit() }) "openscan" else cleaned
}

