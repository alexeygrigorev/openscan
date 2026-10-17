package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.CaptureRefine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Candidate selection for post-capture refinement (item 8b), with synthetic
 * quads — no photos, nothing personal.
 */
class CaptureRefinePickTest {

    private val w = 1000
    private val h = 1400

    private fun good() = floatArrayOf(120f, 150f, 880f, 120f, 900f, 1250f, 100f, 1280f)

    private fun sliver() = floatArrayOf(100f, 100f, 900f, 100f, 900f, 110f, 100f, 110f)

    private fun frameGrab() = floatArrayOf(0f, 0f, 999f, 0f, 999f, 1399f, 0f, 1399f)

    @Test
    fun `plausible fresh wins over everything`() {
        val live = good().copyOf().also { it[0] += 30f }
        val picked = CaptureRefine.pickBest(live, good(), good(), w, h)
        assertEquals(good().toList(), picked?.toList())
    }

    @Test
    fun `implausible fresh falls through to the plausible second attempt`() {
        val second = good()
        val picked = CaptureRefine.pickBest(good(), sliver(), second, w, h)
        assertEquals(second.toList(), picked?.toList())
    }

    @Test
    fun `implausible fresh and second fall back to the live quad`() {
        val live = good()
        val picked = CaptureRefine.pickBest(live, sliver(), frameGrab(), w, h)
        assertEquals(live.toList(), picked?.toList())
    }

    @Test
    fun `all flagged falls back to the highest-scoring candidate`() {
        // Sliver fresh, frame-grab second, no live: the frame grab outranks
        // the sliver on area, so it wins as the flagged fallback.
        val picked = CaptureRefine.pickBest(null, sliver(), frameGrab(), w, h)
        assertEquals(frameGrab().toList(), picked?.toList())
    }

    @Test
    fun `nothing yields null`() {
        assertNull(CaptureRefine.pickBest(null, null, null, w, h))
    }
}
