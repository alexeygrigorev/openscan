package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.QuadPlausibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plausibility checks over synthetic quads (item 8b). No photos: everything
 * here is hand-built geometry, so no personal document can leak into git.
 */
class QuadPlausibilityTest {

    private val w = 1000
    private val h = 1400

    /** A clean, slightly perspectival A4-ish sheet. */
    private fun goodQuad() = floatArrayOf(
        120f, 150f,
        880f, 120f,
        900f, 1250f,
        100f, 1280f,
    )

    @Test
    fun `clean sheet is plausible with no reasons`() {
        val verdict = QuadPlausibility.check(goodQuad(), w, h)
        assertTrue(verdict.reasons.toString(), verdict.plausible)
        assertFalse(verdict.hardFail)
        assertTrue(verdict.reasons.isEmpty())
    }

    @Test
    fun `null and garbage are hard failures`() {
        assertTrue(QuadPlausibility.check(null, w, h).hardFail)
        assertTrue(QuadPlausibility.check(floatArrayOf(1f, 2f), w, h).hardFail)
        assertTrue(
            QuadPlausibility.check(
                floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, Float.NaN), w, h
            ).hardFail
        )
    }

    @Test
    fun `bow-tie quad is a non-convex hard failure`() {
        val bowtie = floatArrayOf(
            100f, 100f,
            900f, 100f,
            100f, 1200f,
            900f, 1200f,
        )
        val verdict = QuadPlausibility.check(bowtie, w, h)
        assertTrue(verdict.hardFail)
        assertFalse(verdict.plausible)
        assertTrue(verdict.reasons.contains("non-convex"))
    }

    @Test
    fun `sliver is a tiny-area hard failure`() {
        val sliver = floatArrayOf(
            100f, 100f,
            900f, 100f,
            900f, 110f,
            100f, 110f,
        )
        val verdict = QuadPlausibility.check(sliver, w, h)
        assertTrue(verdict.hardFail)
        assertTrue(verdict.reasons.contains("tiny-area"))
    }

    @Test
    fun `needles flag extreme angles but stay soft`() {
        // Long triangle-ish read: convex and big, but the tip is sharp.
        val needle = floatArrayOf(
            500f, 100f,
            900f, 1200f,
            500f, 1250f,
            100f, 1200f,
        )
        val verdict = QuadPlausibility.check(needle, w, h)
        assertFalse(verdict.hardFail)
        assertTrue(verdict.reasons.contains("extreme-angles"))
        assertFalse(verdict.plausible)
    }

    @Test
    fun `band across the page flags extreme aspect`() {
        val band = floatArrayOf(
            50f, 600f,
            950f, 600f,
            950f, 800f,
            50f, 800f,
        )
        val verdict = QuadPlausibility.check(band, w, h)
        assertFalse(verdict.hardFail)
        assertTrue(verdict.reasons.contains("extreme-aspect"))
    }

    @Test
    fun `full-frame grab flags on-border`() {
        val frame = floatArrayOf(
            0f, 0f,
            (w - 1).toFloat(), 0f,
            (w - 1).toFloat(), (h - 1).toFloat(),
            0f, (h - 1).toFloat(),
        )
        // A perfect full-bleed read is the right crop, not a flag (item 12).
        val verdict = QuadPlausibility.check(frame, w, h)
        assertTrue(verdict.reasons.toString(), verdict.plausible)
    }

    @Test
    fun `full-bleed classification follows area`() {
        assertTrue(
            QuadPlausibility.isFullBleed(
                floatArrayOf(0f, 0f, 999f, 0f, 999f, 1399f, 0f, 1399f), w, h
            )
        )
        assertFalse(QuadPlausibility.isFullBleed(goodQuad(), w, h))
    }

    @Test
    fun `small border-touching quad still flags on-border`() {        val small = floatArrayOf(
            0f, 0f,
            400f, 0f,
            400f, 500f,
            0f, 500f,
        )
        val verdict = QuadPlausibility.check(small, w, h)
        assertTrue(verdict.reasons.contains("on-border"))
        assertFalse(verdict.plausible)
    }

    @Test
    fun `a4 aspect reads sane`() {
        // 1000x1400 frame filled by an A4-proportioned quad.
        val aspect = QuadPlausibility.aspectOf(
            floatArrayOf(100f, 100f, 900f, 100f, 900f, 1300f, 100f, 1300f)
        )
        assertEquals(1.5, aspect, 0.01)
    }

    @Test
    fun `score ranks the clean sheet above the sliver`() {
        val good = QuadPlausibility.check(goodQuad(), w, h)
        val sliver = QuadPlausibility.check(
            floatArrayOf(100f, 100f, 900f, 100f, 900f, 110f, 100f, 110f), w, h
        )
        assertTrue(good.score > sliver.score)
    }

    @Test
    fun `quadsEqual tolerates sub-pixel jitter only`() {
        val a = goodQuad()
        val b = a.copyOf().also { it[0] += 1f }
        val eps = QuadPlausibility.matchEps(w, h)
        assertTrue(QuadPlausibility.quadsEqual(a, b, eps))
        b[0] += eps * 2
        assertFalse(QuadPlausibility.quadsEqual(a, b, eps))
        assertFalse(QuadPlausibility.quadsEqual(a, null, eps))
    }
}
