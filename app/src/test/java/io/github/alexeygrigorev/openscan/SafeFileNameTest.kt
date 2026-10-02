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
    fun `never returns empty`() {
        assertEquals("openscan", safeFileName("///"))
        assertEquals("openscan", safeFileName("   "))
        assertEquals("openscan", safeFileName(""))
    }
}
