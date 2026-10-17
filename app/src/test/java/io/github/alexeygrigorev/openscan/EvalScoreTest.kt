package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.EvalScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalScoreTest {

    private val w = 1000
    private val h = 1400 // diag ≈ 1720.5

    private fun truth() = floatArrayOf(100f, 100f, 900f, 100f, 900f, 1300f, 100f, 1300f)

    @Test
    fun `identical quads score zero`() {
        assertEquals(0.0, EvalScore.meanError(truth(), truth(), w, h)!!, 1e-9)
        assertTrue(EvalScore.withinTolerance(truth(), truth(), w, h))
    }

    @Test
    fun `ten-pixel shift scores accordingly`() {
        val shifted = truth().copyOf().also {
            for (i in it.indices) it[i] += 10f
        }
        // mean corner distance = sqrt(200) ≈ 14.14; /1720.5 ≈ 0.00822
        assertEquals(0.00822, EvalScore.meanError(shifted, truth(), w, h)!!, 0.0001)
        assertTrue(EvalScore.withinTolerance(shifted, truth(), w, h))
    }

    @Test
    fun `fifty-pixel shift breaks tolerance`() {
        val shifted = truth().copyOf().also {
            for (i in it.indices) it[i] += 50f
        }
        assertFalse(EvalScore.withinTolerance(shifted, truth(), w, h))
    }

    @Test
    fun `missing quads are null, never crash`() {
        assertNull(EvalScore.meanError(null, truth(), w, h))
        assertNull(EvalScore.meanError(truth(), null, w, h))
        assertNull(EvalScore.meanError(floatArrayOf(1f), truth(), w, h))
        assertFalse(EvalScore.withinTolerance(null, truth(), w, h))
    }

    @Test
    fun `summary aggregates and lists failures`() {
        val summary = EvalScore.summarize(
            mapOf("a" to 0.005, "b" to 0.02, "c" to null),
            tolerance = { it == "a" },
        )
        assertEquals(2, summary.scored)
        assertEquals(0.0125, summary.meanError, 1e-9)
        assertEquals(0.02, summary.worstError, 1e-9)
        assertEquals("b", summary.worstCase)
        assertEquals(1, summary.withinTolerance)
        assertEquals(listOf("c"), summary.failures)
    }
}
