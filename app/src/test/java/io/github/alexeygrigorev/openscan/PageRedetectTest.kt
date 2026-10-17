package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.PageRedetect
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRedetectTest {

    @Test
    fun `full frame is detected`() {
        val w = 1000
        val h = 1400
        assertTrue(
            PageRedetect.isFullFrame(
                floatArrayOf(0f, 0f, 999f, 0f, 999f, 1399f, 0f, 1399f), w, h
            )
        )
        assertTrue(
            PageRedetect.isFullFrame(
                floatArrayOf(5f, 5f, 995f, 5f, 995f, 1395f, 5f, 1395f), w, h
            )
        )
    }

    @Test
    fun `real crops are not full frame`() {
        assertFalse(
            PageRedetect.isFullFrame(
                floatArrayOf(120f, 150f, 880f, 120f, 900f, 1250f, 100f, 1280f),
                1000, 1400,
            )
        )
        assertFalse(PageRedetect.isFullFrame(floatArrayOf(1f, 2f), 100, 100))
        assertFalse(
            PageRedetect.isFullFrame(
                floatArrayOf(0f, 0f, 99f, 0f, 99f, 99f, 0f, 99f), 0, 100
            )
        )
    }
}
