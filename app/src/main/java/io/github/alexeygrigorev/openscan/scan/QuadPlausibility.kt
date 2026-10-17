package io.github.alexeygrigorev.openscan.scan

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Plausibility checks for detected document quads (item 8b): pure geometry
 * over the four corners, no image access, fully unit-tested with synthetic
 * quads. Flags garbage detections (non-convex, tiny, wild angles, extreme
 * aspect, frame grabs) so post-capture refinement can re-run and fall back
 * instead of shipping a bad crop silently.
 *
 * Bounds rationale: real pages are convex quadrilaterals with roughly
 * right-angle corners and sane proportions. Perspective skew moves corner
 * angles, so the angle window (60–120°) and the aspect ceiling (3.0, above
 * even long receipts) only *flag* — they never reject on their own. Only
 * non-convexity and tiny area are hard failures.
 */
object QuadPlausibility {

    /** Minimum corner angle in degrees before a soft flag. */
    const val MIN_ANGLE_DEG = 60.0

    /** Maximum corner angle in degrees before a soft flag. */
    const val MAX_ANGLE_DEG = 120.0

    /** Long/short edge ratio above this is a soft flag (A4 = 1.41). */
    const val MAX_ASPECT = 3.0

    /** Frame-area fraction below this is a hard failure (tiny read). */
    const val MIN_AREA_FRAC = 0.015

    /** A corner within this frame fraction of an edge counts as "on border". */
    const val BORDER_FRAC = 0.015

    data class Verdict(
        /** True when nothing is wrong with the quad. */
        val plausible: Boolean,
        /** True for non-convex or tiny quads: unusable, must fall back. */
        val hardFail: Boolean,
        /** Human-readable reasons, empty when plausible. */
        val reasons: List<String>,
        /** Higher is better; ranks fallback candidates against each other. */
        val score: Double,
    )

    fun check(quad: FloatArray?, frameWidth: Int, frameHeight: Int): Verdict {
        if (quad == null || quad.size != 8 || quad.any { !it.isFinite() } || frameWidth <= 0 || frameHeight <= 0) {
            return Verdict(plausible = false, hardFail = true, reasons = listOf("no-quad"), score = Double.NEGATIVE_INFINITY)
        }
        val reasons = ArrayList<String>()
        val pts = Array(4) { i -> quad[2 * i].toDouble() to quad[2 * i + 1].toDouble() }
        val frameArea = frameWidth.toDouble() * frameHeight
        val area = abs(shoelace(pts)) / 2.0
        val areaFrac = area / frameArea

        var hardFail = false
        if (areaFrac < MIN_AREA_FRAC) {
            hardFail = true
            reasons.add("tiny-area")
        }
        if (!isConvex(pts)) {
            hardFail = true
            reasons.add("non-convex")
        }

        val angles = cornerAngles(pts)
        val badAngles = angles.count { it < MIN_ANGLE_DEG || it > MAX_ANGLE_DEG }
        if (badAngles > 0) reasons.add("extreme-angles")

        val edges = edgeLengths(pts)
        val shortest = edges.minOrNull() ?: 0.0
        val longest = edges.maxOrNull() ?: 0.0
        val aspect = if (shortest <= 0.0) Double.POSITIVE_INFINITY else longest / shortest
        if (aspect > MAX_ASPECT) reasons.add("extreme-aspect")

        val onBorder = pts.count { (x, y) ->
            x <= BORDER_FRAC * frameWidth || x >= (1 - BORDER_FRAC) * frameWidth ||
                y <= BORDER_FRAC * frameHeight || y >= (1 - BORDER_FRAC) * frameHeight
        }
        if (onBorder >= 3) reasons.add("on-border")

        // Ranking score: big dump for hard failures, small dings per flag,
        // area as the tiebreak (a bigger plausible read beats a sliver).
        var score = areaFrac
        if (hardFail) score -= 10.0
        score -= 0.1 * badAngles
        if (aspect > MAX_ASPECT) score -= 0.1
        if (onBorder >= 3) score -= 0.1
        return Verdict(
            plausible = reasons.isEmpty(),
            hardFail = hardFail,
            reasons = reasons.toList(),
            score = score,
        )
    }

    private fun shoelace(pts: Array<Pair<Double, Double>>): Double {
        var s = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += pts[i].first * pts[j].second - pts[j].first * pts[i].second
        }
        return s
    }

    /** Convex when all consecutive edge cross products share a sign. */
    private fun isConvex(pts: Array<Pair<Double, Double>>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = pts[i].first - pts[(i + 3) % 4].first
            val ay = pts[i].second - pts[(i + 3) % 4].second
            val bx = pts[(i + 1) % 4].first - pts[i].first
            val by = pts[(i + 1) % 4].second - pts[i].second
            val cross = ax * by - ay * bx
            val s = when {
                cross > 1e-9 -> 1
                cross < -1e-9 -> -1
                else -> 0
            }
            if (s != 0) {
                if (sign == 0) sign = s else if (sign != s) return false
            }
        }
        return true
    }

    private fun cornerAngles(pts: Array<Pair<Double, Double>>): DoubleArray =
        DoubleArray(4) { i ->
            val p = pts[i]
            val a = pts[(i + 3) % 4]
            val b = pts[(i + 1) % 4]
            val v1x = a.first - p.first
            val v1y = a.second - p.second
            val v2x = b.first - p.first
            val v2y = b.second - p.second
            val dot = v1x * v2x + v1y * v2y
            val n1 = hypot(v1x, v1y)
            val n2 = hypot(v2x, v2y)
            if (n1 <= 0.0 || n2 <= 0.0) 0.0
            else Math.toDegrees(kotlin.math.acos((dot / (n1 * n2)).coerceIn(-1.0, 1.0)))
        }

    private fun edgeLengths(pts: Array<Pair<Double, Double>>): DoubleArray =
        DoubleArray(4) { i ->
            val j = (i + 1) % 4
            hypot(pts[j].first - pts[i].first, pts[j].second - pts[i].second)
        }

    /** True when two quads agree corner-wise within [epsPx]. */
    fun quadsEqual(a: FloatArray?, b: FloatArray?, epsPx: Float): Boolean {
        if (a === b) return true
        if (a == null || b == null || a.size != 8 || b.size != 8) return false
        return (0 until 8).all { abs(a[it] - b[it]) <= epsPx }
    }

    /** Tolerance for [quadsEqual]: 0.4% of the frame diagonal. */
    fun matchEps(frameWidth: Int, frameHeight: Int): Float =
        (0.004 * hypot(frameWidth.toDouble(), frameHeight.toDouble())).toFloat()

    /** Aspect range check shared by tests: longest/shortest edge. */
    fun aspectOf(quad: FloatArray): Double {
        val pts = Array(4) { i -> quad[2 * i].toDouble() to quad[2 * i + 1].toDouble() }
        val edges = edgeLengths(pts)
        val shortest = edges.minOrNull() ?: 0.0
        return if (shortest <= 0.0) Double.POSITIVE_INFINITY else (edges.maxOrNull() ?: 0.0) / shortest
    }
}
