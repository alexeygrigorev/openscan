package io.github.alexeygrigorev.openscan.scan

import kotlin.math.hypot

/**
 * Scoring for the feedback eval harness (item 12): corner error of a
 * detected quad against ground-truth corners, normalized by the frame
 * diagonal so scores compare across resolutions. Pure math — unit-tested.
 */
object EvalScore {

    /** Per-corner tolerance as a frame-diagonal fraction (1%). */
    const val TOLERANCE_FRAC = 0.01

    /**
     * Mean corner distance divided by the frame diagonal. Returns null when
     * either quad is missing or malformed.
     */
    fun meanError(quad: FloatArray?, truth: FloatArray?, frameWidth: Int, frameHeight: Int): Double? {
        if (quad == null || truth == null || quad.size != 8 || truth.size != 8) return null
        if (quad.any { !it.isFinite() } || truth.any { !it.isFinite() }) return null
        val diag = hypot(frameWidth.toDouble(), frameHeight.toDouble())
        if (diag <= 0.0) return null
        var sum = 0.0
        for (i in 0 until 4) {
            sum += hypot(
                (quad[2 * i] - truth[2 * i]).toDouble(),
                (quad[2 * i + 1] - truth[2 * i + 1]).toDouble(),
            )
        }
        return sum / 4.0 / diag
    }

    /** True when every corner sits within [TOLERANCE_FRAC] of the truth. */
    fun withinTolerance(quad: FloatArray?, truth: FloatArray?, frameWidth: Int, frameHeight: Int): Boolean {
        if (quad == null || truth == null || quad.size != 8 || truth.size != 8) return false
        val diag = hypot(frameWidth.toDouble(), frameHeight.toDouble())
        if (diag <= 0.0) return false
        return (0 until 4).all { i ->
            hypot(
                (quad[2 * i] - truth[2 * i]).toDouble(),
                (quad[2 * i + 1] - truth[2 * i + 1]).toDouble(),
            ) / diag <= TOLERANCE_FRAC
        }
    }

    data class Summary(
        val scored: Int,
        val meanError: Double,
        val worstError: Double,
        val worstCase: String,
        val withinTolerance: Int,
        /** Cases with no usable quad (detection failures). */
        val failures: List<String>,
    )

    /** Aggregates per-case mean errors (null = detection failure). */
    fun summarize(errors: Map<String, Double?>, tolerance: (String) -> Boolean): Summary {
        val scored = errors.filterValues { it != null }
        val failures = errors.filterValues { it == null }.keys.sorted()
        val mean = if (scored.isEmpty()) Double.NaN else scored.values.filterNotNull().average()
        val worst = scored.maxByOrNull { it.value!! }
        return Summary(
            scored = scored.size,
            meanError = mean,
            worstError = worst?.value ?: Double.NaN,
            worstCase = worst?.key ?: "",
            withinTolerance = errors.keys.count { tolerance(it) },
            failures = failures,
        )
    }
}
