package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.util.safeFileName
import org.junit.Assert.assertEquals
import org.junit.Test

class SafeFileNameTest {

    @Test
    fun `keeps normal titles untouched`() {
        assertEquals("Rent receipt 2026", safeFileName("Rent receipt 2026"))
    }

    @Test
    fun `strips path separators and filesystem-hostile characters`() {
        assertEquals("contract draft v2", safeFileName("contract: draft? v2*"))
        assertEquals("invoice_04", safeFileName("invoice_04"))
        assertEquals("a b", safeFileName("a/b"))
    }

    @Test
    fun `collapses whitespace`() {
        assertEquals("two words", safeFileName("two\n\t words"))
    }

    @Test
    fun `caps length at 80 characters`() {
        val long = "x".repeat(200)
        assertEquals(80, safeFileName(long).length)
    }

    @Test
    fun `strips leading and trailing dots to avoid hidden or malformed files`() {
        assertEquals("hidden", safeFileName(".hidden"))
        assertEquals("report", safeFileName("report."))
        assertEquals("report", safeFileName("...report..."))
        assertEquals("document v1.0 draft", safeFileName("..document v1.0 draft.."))
    }

    @Test
    fun `strips leading and trailing hyphens to avoid cli option collisions`() {
        assertEquals("rf", safeFileName("-rf"))
        assertEquals("help", safeFileName("--help"))
        assertEquals("draft", safeFileName("-draft-"))
        assertEquals("invoice-2026", safeFileName("invoice-2026"))
    }

    @Test
    fun `collapses multiple consecutive dots`() {
        assertEquals("draft.v2", safeFileName("draft..v2"))
        assertEquals("report.2026", safeFileName("report....2026"))
    }

    @Test
    fun `escapes Windows and FAT reserved device names`() {
        assertEquals("CON_", safeFileName("CON"))
        assertEquals("con_", safeFileName("con"))
        assertEquals("prn_", safeFileName("prn"))
        assertEquals("PRN_", safeFileName("PRN"))
        assertEquals("aux_", safeFileName("aux"))
        assertEquals("AUX_", safeFileName("AUX"))
        assertEquals("nul_", safeFileName("nul"))
        assertEquals("NUL_", safeFileName("NUL"))
        assertEquals("com1_", safeFileName("com1"))
        assertEquals("COM9_", safeFileName("COM9"))
        assertEquals("lpt1_", safeFileName("lpt1"))
        assertEquals("LPT9_", safeFileName("LPT9"))
        assertEquals("con_.draft", safeFileName("con.draft"))
        assertEquals("aux_.txt", safeFileName("aux.txt"))
        assertEquals("Constitution", safeFileName("Constitution"))
        assertEquals("auxiliary", safeFileName("auxiliary"))
        assertEquals("commercial", safeFileName("commercial"))
    }

    @Test
    fun `strips redundant trailing pdf extensions to prevent double extension`() {
        assertEquals("Contract", safeFileName("Contract.pdf"))
        assertEquals("Receipt 2026", safeFileName("Receipt 2026.PDF"))
        assertEquals("document", safeFileName("document.pdf.pdf"))
        assertEquals("notes.pdf.txt", safeFileName("notes.pdf.txt"))
        assertEquals("pdf", safeFileName("pdf"))
        assertEquals("CON_", safeFileName("CON.pdf"))
    }

    @Test
    fun `strips redundant trailing image and archive extensions to prevent double extension`() {
        assertEquals("Invoice", safeFileName("Invoice.jpg"))
        assertEquals("Invoice", safeFileName("Invoice.JPG"))
        assertEquals("Receipt", safeFileName("Receipt.jpeg"))
        assertEquals("Receipt", safeFileName("Receipt.JPEG"))
        assertEquals("Photo", safeFileName("Photo.png"))
        assertEquals("Photo", safeFileName("Photo.PNG"))
        assertEquals("Archive", safeFileName("Archive.zip"))
        assertEquals("Archive", safeFileName("Archive.ZIP"))
        assertEquals("scan", safeFileName("scan.jpg.pdf"))
        assertEquals("scan", safeFileName("scan.pdf.jpg"))
        assertEquals("doc", safeFileName("doc.zip.zip"))
        assertEquals("jpg", safeFileName("jpg"))
        assertEquals("png", safeFileName("png"))
        assertEquals("zip", safeFileName("zip"))
        assertEquals("CON_", safeFileName("CON.jpg"))
        assertEquals("CON_", safeFileName("CON.zip"))
    }

    @Test
    fun `normalizes decomposed unicode diacritics into precomposed canonical letters`() {
        // NFD inputs (letter + combining mark) must compose to canonical NFC letters.
        assertEquals("café", safeFileName("cafe\u0301"))
        assertEquals("über", safeFileName("u\u0308ber"))
        assertEquals("año", safeFileName("an\u0303o"))
        assertEquals("résumé", safeFileName("re\u0301sume\u0301"))
        assertEquals("façade", safeFileName("fac\u0327ade"))
        assertEquals("naïve", safeFileName("nai\u0308ve"))
        assertEquals("Český", safeFileName("C\u030Cesky\u0301"))
        assertEquals("Документ_май", safeFileName("Документ_маи\u0306"))
    }

    @Test
    fun `cleans whitespace surrounding dots to prevent invalid filenames on Windows and FAT`() {
        assertEquals("draft.v2", safeFileName("draft .v2"))
        assertEquals("report.2026", safeFileName("report. 2026"))
        assertEquals("notes.txt", safeFileName("notes . txt"))
    }

    @Test
    fun `never returns empty`() {
        assertEquals("openscan", safeFileName("///"))
        assertEquals("openscan", safeFileName("   "))
        assertEquals("openscan", safeFileName(""))
        assertEquals("openscan", safeFileName("..."))
        assertEquals("openscan", safeFileName(" . "))
        assertEquals("openscan", safeFileName("-"))
        assertEquals("openscan", safeFileName("---"))
        assertEquals("openscan", safeFileName(" -.- "))
        assertEquals("openscan", safeFileName(".pdf"))
        assertEquals("openscan", safeFileName(".jpg"))
        assertEquals("openscan", safeFileName(".jpeg"))
        assertEquals("openscan", safeFileName(".png"))
        assertEquals("openscan", safeFileName(".zip"))
        assertEquals("openscan", safeFileName(" .jpg.pdf "))
    }
}
