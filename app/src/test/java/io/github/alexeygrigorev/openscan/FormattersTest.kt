package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.util.formatCount
import io.github.alexeygrigorev.openscan.util.formatFileSize
import io.github.alexeygrigorev.openscan.util.formatPageCount
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FormattersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `formats singular page count`() {
        assertEquals("1 page", formatPageCount(1))
    }

    @Test
    fun `formats plural page count`() {
        assertEquals("0 pages", formatPageCount(0))
        assertEquals("2 pages", formatPageCount(2))
        assertEquals("42 pages", formatPageCount(42))
    }

    @Test
    fun `formats page count with qualifier`() {
        assertEquals("1 captured page", formatPageCount(1, "captured"))
        assertEquals("5 captured pages", formatPageCount(5, "captured"))
        assertEquals("1 new page", formatPageCount(1, "new"))
        assertEquals("3 new pages", formatPageCount(3, "new"))
    }

    @Test
    fun `formats generic count with custom singular and plural nouns`() {
        assertEquals("1 crop", formatCount(1, "crop"))
        assertEquals("0 crops", formatCount(0, "crop"))
        assertEquals("3 crops", formatCount(3, "crop"))
        assertEquals("1 child", formatCount(1, "child", "children"))
        assertEquals("2 children", formatCount(2, "child", "children"))
    }

    @Test
    fun `formats generic count with qualifier`() {
        assertEquals("1 new crop", formatCount(1, "crop", qualifier = "new"))
        assertEquals("4 new crops", formatCount(4, "crop", qualifier = "new"))
        assertEquals("1 flagged crop", formatCount(1, "crop", qualifier = " flagged "))
    }

    @Test
    fun `formats zero and negative file sizes`() {
        assertEquals("0 B", formatFileSize(0L))
        assertEquals("0 B", formatFileSize(-1L))
        assertEquals("0 B", formatFileSize(-1024L))
    }

    @Test
    fun `formats byte sizes below 1 KB`() {
        assertEquals("1 B", formatFileSize(1L))
        assertEquals("512 B", formatFileSize(512L))
        assertEquals("1023 B", formatFileSize(1023L))
    }

    @Test
    fun `formats exact integer units without trailing zero`() {
        assertEquals("1 KB", formatFileSize(1024L))
        assertEquals("2 KB", formatFileSize(2048L))
        assertEquals("10 KB", formatFileSize(10240L))
        assertEquals("1 MB", formatFileSize(1048576L))
        assertEquals("5 MB", formatFileSize(5242880L))
        assertEquals("1 GB", formatFileSize(1073741824L))
        assertEquals("1 TB", formatFileSize(1099511627776L))
    }

    @Test
    fun `formats fractional sizes with one decimal place`() {
        assertEquals("1.5 KB", formatFileSize(1536L))
        assertEquals("2.5 KB", formatFileSize(2560L))
        assertEquals("2.5 MB", formatFileSize(2621440L))
        assertEquals("3.5 GB", formatFileSize(3758096384L))
    }

    @Test
    fun `rolls over boundary sizes near unit ceiling to next unit`() {
        // 1048570 bytes / 1024 = 1023.994 KB -> rounds to 1024.0 KB -> rolls over to 1 MB
        assertEquals("1 MB", formatFileSize(1048570L))
        // 1073741000 bytes / (1024*1024) = 1023.999 MB -> rolls over to 1 GB
        assertEquals("1 GB", formatFileSize(1073741000L))
    }

    @Test
    fun `formats file size from File object`() {
        val sample = tmp.newFile("test.pdf").apply { writeBytes(ByteArray(2048)) }
        assertEquals("2 KB", formatFileSize(sample))

        val nonexistent = File(tmp.root, "ghost.pdf")
        assertEquals("0 B", formatFileSize(nonexistent))
    }
}
