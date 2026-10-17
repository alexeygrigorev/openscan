package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.FilterPixels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enhancement kernel tests (items 6+10) on synthetic pixel fields: gradients
 * and shaded pages, nothing photographic.
 */
class FilterPixelsTest {

    private fun solid(w: Int, h: Int, argb: Int): IntArray = IntArray(w * h) { argb }

    private fun gray(v: Int): Int = FilterPixels.argb(255, v, v, v)

    /** Left half dark text-ish, right half bright paper-ish, with a shading ramp. */
    private fun shadedPage(w: Int = 60, h: Int = 40): IntArray =
        IntArray(w * h) { i ->
            val x = i % w
            val shade = 120 + (x * 100 / w) // 120..220 illumination ramp
            if (x % 10 < 2) gray((shade - 90).coerceAtLeast(0)) else gray(shade)
        }

    @Test
    fun `grayscale equalizes channels`() {
        val red = FilterPixels.argb(255, 200, 30, 30)
        val out = FilterPixels.applyGrayscale(intArrayOf(red))
        assertEquals(FilterPixels.red(out[0]), FilterPixels.green(out[0]))
        assertEquals(FilterPixels.green(out[0]), FilterPixels.blue(out[0]))
    }

    @Test
    fun `document mode whitens the background and keeps text dark`() {
        val w = 60
        val h = 40
        val out = FilterPixels.applyDocument(shadedPage(w, h), w, h)
        // Paper sample (middle of a bright stripe): near white.
        val paper = FilterPixels.luminance(out[5 * w + 5])
        assertTrue("paper $paper should whiten past 200", paper > 200)
        // Text sample (dark stripe): stays clearly darker than the paper.
        val text = FilterPixels.luminance(out[5 * w + 0])
        assertTrue("text $text should stay dark (<150)", text < 150)
        assertTrue("contrast ${paper - text} should exceed 80", paper - text > 80)
    }

    @Test
    fun `document mode preserves hue`() {
        // Coloured seal on mid-gray paper: hue must survive normalization.
        val w = 20
        val h = 20
        val pixels = IntArray(w * h) { gray(150) }
        pixels[10 * w + 10] = FilterPixels.argb(255, 200, 40, 40)
        val out = FilterPixels.applyDocument(pixels, w, h)
        val seal = out[10 * w + 10]
        assertTrue("red stays dominant: $seal", FilterPixels.red(seal) > FilterPixels.green(seal) + 30)
        assertTrue("red stays dominant over blue", FilterPixels.red(seal) > FilterPixels.blue(seal) + 30)
    }

    @Test
    fun `black and white outputs only pure black or white`() {
        val w = 60
        val h = 40
        val out = FilterPixels.applyBlackWhite(shadedPage(w, h), w, h)
        assertTrue(out.all { it == -1 || it == -0x1000000 })
        // Both colors appear: text and paper both present.
        assertTrue(out.any { it == -1 })
        assertTrue(out.any { it == -0x1000000 })
    }

    @Test
    fun `uniform field does not explode`() {
        val flat = solid(30, 30, gray(180))
        val doc = FilterPixels.applyDocument(flat, 30, 30)
        assertTrue(doc.all { FilterPixels.luminance(it) in 0..255 })
        val bw = FilterPixels.applyBlackWhite(flat, 30, 30)
        assertTrue(bw.all { it == -1 || it == -0x1000000 })
    }

    @Test
    fun `box blur averages`() {
        val sharp = intArrayOf(0, 0, 0, 0, 255, 0, 0, 0, 0)
        val blurred = FilterPixels.boxBlur(sharp, 3, 3, 1)
        assertEquals(255 / 9, blurred[4])
    }

    @Test
    fun `unsharp with zero amount is a copy`() {
        val pixels = shadedPage()
        val out = FilterPixels.unsharp(pixels, 60, 40, 0f)
        assertTrue(out.contentEquals(pixels))
    }

    @Test
    fun `unsharp boosts an isolated edge`() {
        // Step edge: dark left, bright right; the pixel just left of the
        // edge should darken after sharpening (overshoot).
        val w = 9
        val h = 9
        val pixels = IntArray(w * h) { i -> gray(if (i % w < 4) 100 else 200) }
        val out = FilterPixels.unsharp(pixels, w, h, 0.5f)
        val before = FilterPixels.luminance(pixels[4 * w + 3])
        val after = FilterPixels.luminance(out[4 * w + 3])
        assertTrue("edge pixel $before -> $after should darken", after < before)
    }
}
