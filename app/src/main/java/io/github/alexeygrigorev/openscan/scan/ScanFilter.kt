package io.github.alexeygrigorev.openscan.scan

/**
 * Post-processing enhancement filters (item 6). Stored as stable string ids
 * on pages, documents and settings so persisted values survive renames.
 */
enum class ScanFilter(val id: String, val title: String) {
    DOCUMENT("document", "Document"),
    BLACK_WHITE("bw", "Black & white"),
    GRAYSCALE("gray", "Grayscale"),
    ORIGINAL("original", "Original");

    companion object {
        /** Alexey always picks this: the default everywhere. */
        val DEFAULT = DOCUMENT

        /** Resolves a stored id, falling back to [DEFAULT] for null/unknown. */
        fun fromId(id: String?): ScanFilter =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * Effective filter for a page: the page override wins, then the
         * document filter, then the app default from settings.
         */
        fun effective(pageFilter: String?, documentFilter: String?, appDefault: String?): ScanFilter =
            when {
                !pageFilter.isNullOrBlank() && entries.any { it.id == pageFilter } ->
                    fromId(pageFilter)
                !documentFilter.isNullOrBlank() && entries.any { it.id == documentFilter } ->
                    fromId(documentFilter)
                else -> fromId(appDefault)
            }
    }
}
