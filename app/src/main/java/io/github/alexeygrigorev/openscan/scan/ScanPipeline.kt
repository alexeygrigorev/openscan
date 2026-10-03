package io.github.alexeygrigorev.openscan.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The app's independently testable scan component: bitmap in → document quad
 * + perspective-warped bitmap out (picture in → scanned picture out).
 * Self-contained OpenCV-only detection/warping with no GMS dependency; used
 * by the androidTest eval benchmark (DocumentEvalBenchmark) to measure the
 * full pipeline geometry over a corpus of real document photos.
 */
object ScanPipeline {

    /** One scan: the detected document quad and the warped, cropped page bitmap. */
    data class ScanResult(val quad: FloatArray, val cropped: Bitmap)

    /**
     * Optional debug sink: when set, [QuadDetector.detect] reports its scored
     * candidate pool per photo (same fields as the Python twin's debug_pool),
     * plus the rescue decision and final quad. Test/eval tooling only —
     * null in production.
     */
    var quadDebugSink: ((List<Map<String, Any?>>) -> Unit)? = null

    /** Returns TL, TR, BR, BL corner coordinates in source-bitmap space, or null. */
    fun detectQuad(src: Bitmap): FloatArray? = QuadDetector.detect(src)

    /** Perspective-warps [src] through [quad] (TL, TR, BR, BL) into a rect bitmap. */
    fun crop(src: Bitmap, quad: FloatArray): Bitmap = Perspective.warpToRect(src, quad)

    /** Convenience: detect + crop, or null if no document is found. */
    fun scan(src: Bitmap): ScanResult? {
        val quad = detectQuad(src) ?: return null
        return ScanResult(quad, crop(src, quad))
    }
}

/** Paper-mask quad detection, self-contained so the benchmark needs no GMS and no native libs. */
private object QuadDetector {

    // Tuning constants mirror the offline Python harness (improved.py) 1:1.
    private const val WORK = 1000.0
    private val CANNY_SETS = listOf(40.0 to 120.0, 20.0 to 80.0, 70.0 to 180.0)
    private val CLAHE_SET = 30.0 to 90.0 // extra Canny set on contrast-normalized gray
    private const val MIN_AREA_FRAC = 0.012
    private const val SUPPORT_TAU = 0.30
    private const val PAPER_STRONG = 0.05 // paperness floor for crisp-edge quads (saturated
    // documents with sharply bordered edges still pass)
    private const val PAPER_WEAK = 0.40   // floor for quads without edge evidence
    private const val PAPERLESS_FLOOR = 0.12 // on scenes with no papery area at all
    private const val PAPERLESS_SCENE = 0.08 // (frame paperness below this) crisp-edge
    // quads must reach the higher floor — bright bands on paperless scenes
    // (pap ~0) are reads of texture, not documents
    private const val FRAME_AF_DAMP = 0.85
    private const val BIG_AF = 0.88       // quads at least this big with weak edge support
    private const val BIG_AF_DAMP = 0.25  // are damped: bright desk merged with the document
    private const val PAP_FILL_FLOOR = 0.85 // paperish candidates (pap >= floor, sup >= 0.35)
    // get fill = max(fill, pap) — a quad full of paper is trusted on
    // containment even when its edge contour is sparse
    private const val SLIVER_AF = 0.10    // winners smaller than this on papery borders
    // are promoted to the frame
    private const val RESCUE_AF = 0.10    // winners smaller than this (or merged
    // near-full-frame quads with no boundary evidence) trigger the texture rescue
    private const val TEXTURE_WIN = 17      // local-std window (px)
    private const val TEXTURE_STD_THRESH = 3.0 // gray std-dev over the window that
    // counts as textured (ruled lines ≈ 2–6, text/crumple folds ≫; smooth desk < 1)
    private const val TEXTURE_MIN_FRAC = 0.02 // texture components below this area
    // fraction are ignored
    private const val TEXTURE_MAX_COMPS = 3
    private const val TEXTURE_MIN_FILL = 0.5 // ragged multi-object merges are not documents

    /** One scored proposal: quad + the evidence it was scored against. */
    private class Cand(
        val q: DoubleArray,
        val score: Double,
        val pap: Double,
        val af: Double,
        val sup: Double,
        val edgesIdx: Int,
        val texture: Boolean = false,
    )

    /** Returns TL, TR, BR, BL corner coordinates in source-bitmap space, or null. */
    fun detect(src: Bitmap): FloatArray? {
        if (!OpenCVLoader.initLocal()) error("OpenCV native library failed to load")
        val dbg = if (ScanPipeline.quadDebugSink != null) ArrayList<Map<String, Any?>>() else null
        val rgba = Mat()
        org.opencv.android.Utils.bitmapToMat(src, rgba)
        val scale = WORK / maxOf(src.width, src.height)
        val small = Mat()
        // round-half-even keeps the work size identical to the Python harness
        Imgproc.resize(
            rgba, small,
            Size(halfEven(src.width * scale), halfEven(src.height * scale)),
            0.0, 0.0, Imgproc.INTER_AREA,
        )
        rgba.release()
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val hsv = Mat()
        val rgb = Mat()
        Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        rgb.release()

        val sh = small.rows()
        val sw = small.cols()
        val frameArea = sw.toDouble() * sh
        val frameQ = doubleArrayOf(
            0.0, 0.0, sw - 1.0, 0.0, sw - 1.0, sh - 1.0, 0.0, sh - 1.0,
        )
        // Paperness of the whole scene: drives the paperless-scene paperness
        // floor in add() (improved.py computes the same value once per photo).
        val scenePap = paperishScore(frameQ, hsv)

        // Raw + dilated edges per Canny set: raw for refinement, dilated for
        // support sampling (band = 3 px). The last pass runs Canny on
        // contrast-normalized gray (CLAHE) so washed-out scenes still yield
        // document-boundary edges.
        val grayC = Mat()
        Imgproc.createCLAHE(3.0, Size(8.0, 8.0)).apply(gray, grayC)
        val passes = ArrayList<Triple<Double, Double, Mat>>(CANNY_SETS.size + 1)
        for (set in CANNY_SETS) passes.add(Triple(set.first, set.second, gray))
        passes.add(Triple(CLAHE_SET.first, CLAHE_SET.second, grayC))

        val rawEdges = arrayOfNulls<Mat>(passes.size)
        val dilEdges = arrayOfNulls<Mat>(passes.size)
        val cands = ArrayList<Cand>()

        for ((idx, pass) in passes.withIndex()) {
            val (lo, hi, src) = pass
            val edges = Mat()
            Imgproc.Canny(src, edges, lo, hi)
            Imgproc.dilate(edges, edges, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            rawEdges[idx] = edges
            val dil = Mat()
            Imgproc.dilate(edges, dil, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0)))
            dilEdges[idx] = edges.let { dil }

            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            for ((q, ca) in gatherCandidates(contours, frameArea)) {
                val qa = polyArea(q)
                if (qa <= 0.0) continue
                val sup = edgeSupport(q, dil)
                if (sup < 0.30) continue
                add(cands, q, min(1.0, ca / qa), sup, idx, frameQ, frameArea, scenePap, hsv, lastResort = false, dbg = dbg, src = "pass$idx")
            }
        }
        grayC.release()

        // Bright-paper mask: support is inherently weak (paper edges are exactly
        // the low-contrast case that motivated this path), so no support gate.
        paperMaskQuad(hsv, sw, sh)?.let { pm ->
            add(cands, pm, 1.0, edgeSupport(pm, dilEdges[0]!!), 0, frameQ, frameArea, scenePap, hsv, lastResort = false, dbg = dbg, src = "papermask")
        }

        // Last resort: minAreaRect of the dominant contour — only when the
        // contour actually fills its rect and looks like paper, otherwise it
        // is scattered edge junk (a person, a table-tennis player).
        rawEdges[0]?.let { bestEdges ->
            val contours0 = ArrayList<MatOfPoint>()
            Imgproc.findContours(bestEdges, contours0, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            val big = contours0.maxByOrNull { Imgproc.contourArea(it) }
            if (big != null && Imgproc.contourArea(big) > 0.015 * frameArea) {
                val rot = Imgproc.minAreaRect(MatOfPoint2f(*big.toArray()))
                if (rot.size.width >= 1 && rot.size.height >= 1) {
                    val pts = Array(4) { org.opencv.core.Point() }
                    rot.points(pts)
                    val q = orderCorners(pts.map { it.x to it.y })
                    val qa = max(polyArea(q), 1e-6)
                    val fill = min(1.0, Imgproc.contourArea(big) / qa)
                    // Loose fill is fine — side extension grows the rect back
                    // over the paper, and the frame-promotion above handles
                    // sliver rects.
                    add(cands, q, fill, edgeSupport(q, dilEdges[0]!!), 0, frameQ, frameArea, scenePap, hsv, lastResort = true, dbg = dbg, src = "lastresort")
                }
            }
        }

        // Very last resort: the frame itself, but only when the photo borders
        // look like paper (document fills / overflows the frame).
        val framePaperish = frameIsPaperish(hsv, sw, sh)
        if (cands.isEmpty() && framePaperish) {
            add(cands, frameQ, 1.0, edgeSupport(frameQ, dilEdges[0]!!), 0, frameQ, frameArea, scenePap, hsv, lastResort = true, dbg = dbg, src = "frame")
        }

        if (cands.isEmpty()) {
            gray.release(); hsv.release()
            rawEdges.forEach { it?.release() }
            dilEdges.forEach { it?.release() }
            return null
        }
        cands.sortByDescending { it.score }
        var best = cands[0]
        // Texture rescue: a sliver winner or a merged near-full-frame quad with no
        // boundary evidence means the scene starved the edge pipeline (document on
        // a smooth uniform surface — white notebook on white desk, crumpled receipt
        // on a plain table). Such documents are the only papery-textured regions in
        // the frame even when their boundaries carry almost no contrast, so add
        // local-std texture-mask candidates and re-pick.
        if (best.af < RESCUE_AF || (best.af > 0.90 && best.sup < 0.20)) {
            dbg?.add(
                mapOf(
                    "rescue" to "fired",
                    "winner_af" to Math.round(best.af * 1000.0) / 1000.0,
                    "winner_sup" to Math.round(best.sup * 100.0) / 100.0,
                )
            )
            for ((q, fill) in textureMaskQuads(small, hsv, frameArea)) {
                add(
                    cands, q, fill, edgeSupport(q, dilEdges[0]!!), 0,
                    frameQ, frameArea, scenePap, hsv, lastResort = false, texture = true,
                    dbg = dbg, src = "texture",
                )
            }
            cands.sortByDescending { it.score }
            best = cands[0]
        } else {
            dbg?.add(
                mapOf(
                    "rescue" to "not fired",
                    "winner_af" to Math.round(best.af * 1000.0) / 1000.0,
                    "winner_sup" to Math.round(best.sup * 100.0) / 100.0,
                )
            )
        }
        val bestScore0 = best.score // the sliver test keeps using the original winner's score
        // Inner switch: a well-supported candidate nested inside the winner
        // that is much smaller and brighter is the document; the winner merely
        // covers its surroundings (card on a desk/keyboard).
        for (c in cands.drop(1)) {
            if (c.sup < 0.5) continue
            if (c.af < 0.06 || c.af >= 0.85 * best.af) continue
            if (c.pap <= best.pap + 0.02) continue
            if (containment(c.q, best.q) < 0.85) continue
            // A band-like inner (bbox min-dim < 25% of the frame's) is a read of
            // ruled lines or a text block, not a nested document (3-line band on
            // a full receipt).
            var minX = c.q[0]; var maxX = c.q[0]
            var minY = c.q[1]; var maxY = c.q[1]
            for (i in 1..3) {
                val x = c.q[2 * i]; val y = c.q[2 * i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            if (minOf(maxX - minX, maxY - minY) < 0.25 * minOf(sw.toDouble(), sh.toDouble())) continue
            // A strong winner (papery, well edge-aligned) is not dethroned by a
            // barely edge-aligned inner — that inner is a feature inside the
            // document (lottery box on a full-frame receipt).
            if (best.pap >= 0.85 && best.af >= 0.30 && c.af < 0.10) continue
            best = c
            break
        }
        // A tiny low-confidence winner on papery borders means the real document
        // is the frame (paper cut off by the photo edge) — e.g. a sliver along a
        // ruler on a full-frame page.
        if (!best.q.contentEquals(frameQ) && best.af < SLIVER_AF && bestScore0 < 1.0 && framePaperish) {
            best = Cand(frameQ, bestScore0, best.pap, 1.0, edgeSupport(frameQ, dilEdges[0]!!), 0)
        }
        // A BIG winner with two or more sides cutting through open papery space
        // (no edge line along them) is a partial read of a frame-filling document
        // — e.g. a band across a full-bleed print or a ruler quad on a full page.
        // Texture-rescue winners are exempt: they only exist on scenes with no
        // usable edge evidence at all, where "the doc really fills the frame" is
        // unverifiable — and promoting to frame is the bug this path fixes.
        if (!best.q.contentEquals(frameQ) && best.af > 0.30 && !best.texture && framePaperish) {
            val sups = sideSupports(best.q, rawEdges[best.edgesIdx]!!)
            if (sups.count { it < 0.25 } >= 2) {
                best = Cand(frameQ, best.score, best.pap, 1.0, edgeSupport(frameQ, dilEdges[0]!!), 0)
            }
        }
        var quad = best.q
        if (!best.texture) {
            quad = refineQuad(quad, rawEdges[best.edgesIdx]!!)
            quad = extendSides(quad, hsv, rawEdges[best.edgesIdx]!!, sw, sh)
        }
        // (texture-rescue winners skip refinement and side extension: the mask
        // outline is already the full blob, Canny edges are unreliable on these
        // scenes, and extension grows into the smooth-but-papery table — the
        // exact failure this path rescues.)
        gray.release()
        hsv.release()
        rawEdges.forEach { it?.release() }
        dilEdges.forEach { it?.release() }
        if (dbg != null) {
            dbg.add(
                mapOf(
                    "final" to quad.map { Math.round(it * 10.0) / 10.0 },
                    "texture_winner" to best.texture,
                )
            )
            ScanPipeline.quadDebugSink?.invoke(dbg)
        }
        val pts = Array(4) { i -> (quad[2 * i] / scale) to (quad[2 * i + 1] / scale) }
        return FloatArray(8) { d -> if (d % 2 == 0) pts[d / 2].first.toFloat() else pts[d / 2].second.toFloat() }
    }

    /** Python-like add(): paperness floors, desk-merge rejection, scoring. */
    private fun add(
        cands: ArrayList<Cand>,
        q: DoubleArray,
        fill: Double,
        sup: Double,
        edgesIdx: Int,
        frameQ: DoubleArray,
        frameArea: Double,
        scenePap: Double,
        hsv: Mat,
        lastResort: Boolean,
        texture: Boolean = false,
        dbg: MutableList<Map<String, Any?>>? = null,
        src: String = "",
    ) {
        val pap = paperishScore(q, hsv)
        var floor = if (sup >= 0.55) PAPER_STRONG else PAPER_WEAK
        if (sup >= 0.55 && scenePap < PAPERLESS_SCENE) floor = max(floor, PAPERLESS_FLOOR)
        if (pap < floor) {
            dbg?.add(mapOf("rejected" to "pap $pap < floor $floor", "src" to src))
            return
        }
        val af = polyArea(q) / frameArea
        if (!lastResort && af > 0.8 && pap < 0.5 && fill >= 0.99) { // mask merged the desk
            dbg?.add(mapOf("rejected" to "desk merge", "src" to src))
            return
        }
        var fillEff = fill
        if (pap >= PAP_FILL_FLOOR && sup >= 0.35) fillEff = max(fillEff, pap)
        var damp = if (q contentEquals frameQ) FRAME_AF_DAMP else 1.0
        if (af >= BIG_AF && !(sup >= 0.50 && pap >= 0.50)) damp *= BIG_AF_DAMP
        val score = af * damp * Math.pow(fillEff, 4.0) * (SUPPORT_TAU + sup) * (1.0 + 4.0 * pap)
        cands.add(Cand(q, score, pap, af, sup, edgesIdx, texture))
        if (dbg != null) {
            var minX = q[0]; var maxX = q[0]; var minY = q[1]; var maxY = q[1]
            for (i in 1..3) {
                if (q[2 * i] < minX) minX = q[2 * i]
                if (q[2 * i] > maxX) maxX = q[2 * i]
                if (q[2 * i + 1] < minY) minY = q[2 * i + 1]
                if (q[2 * i + 1] > maxY) maxY = q[2 * i + 1]
            }
            dbg.add(
                mapOf(
                    "score" to Math.round(score * 10000.0) / 10000.0,
                    "af" to Math.round(af * 1000.0) / 1000.0,
                    "sup" to Math.round(sup * 100.0) / 100.0,
                    "pap" to Math.round(pap * 100.0) / 100.0,
                    "fill" to Math.round(fill * 100.0) / 100.0,
                    "bbox_pct" to listOf(
                        Math.round(minX / hsv.cols() * 100), Math.round(minY / hsv.rows() * 100),
                        Math.round(maxX / hsv.cols() * 100), Math.round(maxY / hsv.rows() * 100),
                    ),
                    "texture" to texture,
                    "src" to src,
                )
            )
        }
    }

    /** (quad, contour area) for every convex-quad contour plus minAreaRect boxes of big contours. */
    private fun gatherCandidates(
        contours: List<MatOfPoint>,
        frameArea: Double,
    ): List<Pair<DoubleArray, Double>> {
        val out = ArrayList<Pair<DoubleArray, Double>>()
        val big = contours.sortedByDescending { Imgproc.contourArea(it) }
        for (c in big) {
            val ca = Imgproc.contourArea(c)
            if (ca < MIN_AREA_FRAC * frameArea) break
            quadFromContour(c)?.let { out.add(it to ca) }
        }
        val seen = HashSet<String>()
        for (c in big) {
            val ca = Imgproc.contourArea(c)
            if (ca < MIN_AREA_FRAC * frameArea) break
            val rot = Imgproc.minAreaRect(MatOfPoint2f(*c.toArray()))
            if (rot.size.width < 1 || rot.size.height < 1) continue
            val pts = Array(4) { org.opencv.core.Point() }
            rot.points(pts)
            val box = orderCorners(pts.map { it.x to it.y })
            val key = box.joinToString(",") { "%.1f".format(it) }
            if (seen.add(key)) out.add(box to ca)
        }
        return out
    }

    /** First convex quad across approximation coarseness, else null. */
    private fun quadFromContour(contour: MatOfPoint): DoubleArray? {
        val pts = MatOfPoint2f(*contour.toArray())
        val peri = Imgproc.arcLength(pts, true)
        if (peri <= 0.0) return null
        for (eps in intArrayOf(1, 2, 3, 5, 8, 12)) {
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(pts, approx, eps * 0.01 * peri, true)
            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                val q = orderCorners(approx.toArray().map { it.x to it.y })
                if (polyArea(q) > 0.0) return q
            }
        }
        return null
    }

    /** Fraction of sampled points along the quad's sides that sit on an edge pixel. */
    private fun edgeSupport(q: DoubleArray, dilEdges: Mat): Double {
        val samples = DoubleArray(12) { 0.08 + it * (0.92 - 0.08) / 11 }
        var hit = 0
        var total = 0
        val eh = dilEdges.rows()
        val ew = dilEdges.cols()
        val buf = ByteArray(1)
        for (side in 0 until 4) {
            val ax = q[2 * side]
            val ay = q[2 * side + 1]
            val j = (side + 1) % 4
            val bx = q[2 * j]
            val by = q[2 * j + 1]
            for (t in samples) {
                val xi = (ax + t * (bx - ax)).roundToInt()
                val yi = (ay + t * (by - ay)).roundToInt()
                if (xi in 0 until ew && yi in 0 until eh) {
                    total++
                    dilEdges.get(yi, xi, buf)
                    if (buf[0].toInt() != 0) hit++
                }
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    /** How paper-like the quad interior is: bright and unsaturated, 0..1. */
    private fun paperishScore(q: DoubleArray, hsv: Mat): Double {
        val mask = Mat.zeros(hsv.rows(), hsv.cols(), org.opencv.core.CvType.CV_8UC1)
        val pts = MatOfPoint(
            org.opencv.core.Point(q[0], q[1]),
            org.opencv.core.Point(q[2], q[3]),
            org.opencv.core.Point(q[4], q[5]),
            org.opencv.core.Point(q[6], q[7]),
        )
        Imgproc.fillPoly(mask, listOf(pts), org.opencv.core.Scalar(255.0))
        val area = Core.countNonZero(mask)
        if (area == 0) return 0.0
        val mean = Core.mean(hsv, mask)
        mask.release()
        val vTerm = ((mean.`val`[2] - 90.0) / 90.0).coerceIn(0.0, 1.0)
        val sTerm = (1.0 - mean.`val`[1] / 110.0).coerceIn(0.0, 1.0)
        return 0.5 * vTerm + 0.5 * sTerm
    }

    /** Bright low-saturation mask -> largest component -> convex quad, or null. */
    private fun paperMaskQuad(hsv: Mat, sw: Int, sh: Int): DoubleArray? {
        val mask = Mat()
        Core.inRange(hsv, org.opencv.core.Scalar(0.0, 0.0, 120.0), org.opencv.core.Scalar(179.0, 90.0, 255.0), mask)
        val k9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k9)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k5)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        mask.release()
        if (contours.isEmpty()) return null
        val comp = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        if (Imgproc.contourArea(comp) < 0.04 * sw * sh) return null
        // convexHull yields indices into comp; map them back to points
        val hullIdx = org.opencv.core.MatOfInt()
        Imgproc.convexHull(comp, hullIdx)
        val compPts = comp.toArray()
        val hullPts = hullIdx.toArray().map { compPts[it.toInt()] }
        hullIdx.release()
        val hull = MatOfPoint(*hullPts.toTypedArray())
        val hull2f = MatOfPoint2f(*hull.toArray())
        val pts = hull.toArray().map { it.x to it.y }
        var approx = hull2f
        for (eps in intArrayOf(1, 2, 3, 5, 8)) {
            val a = MatOfPoint2f()
            Imgproc.approxPolyDP(hull2f, a, eps * 0.01 * Imgproc.arcLength(hull2f, true), true)
            approx = a
            if (a.total() <= 6L) break
        }
        if (approx.total() == 4L) return orderCorners(approx.toArray().map { it.x to it.y })
        val rot = Imgproc.minAreaRect(hull2f)
        if (rot.size.width < 1 || rot.size.height < 1) return null
        val boxPts = Array(4) { org.opencv.core.Point() }
        rot.points(boxPts)
        return orderCorners(boxPts.map { it.x to it.y })
    }

    /**
     * Local-standard-deviation texture mask ANDed with the papery HSV test ->
     * solid textured-component quads. Documents sitting on smooth surfaces
     * (white notebook on white desk, crumpled receipt on a plain table) are the
     * only papery-textured regions in the frame even when their boundaries carry
     * almost no photometric contrast. std = sqrt(blur(g²) − blur(g)²) over a
     * window; threshold at TEXTURE_STD_THRESH, drop fabric/foliage/dark keys via
     * the papery test, close to bridge text/line gaps, then convex-quad (or
     * minAreaRect) each big component. Mirrors improved_v18.texture_mask_quads 1:1.
     */
    private fun textureMaskQuads(small: Mat, hsv: Mat, frameArea: Double): List<Pair<DoubleArray, Double>> {
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        val g = Mat()
        gray.convertTo(g, org.opencv.core.CvType.CV_32F)
        gray.release()
        val mean = Mat()
        Imgproc.GaussianBlur(g, mean, Size(TEXTURE_WIN.toDouble(), TEXTURE_WIN.toDouble()), 0.0)
        val sq = Mat()
        Core.multiply(g, g, sq) // blur(g²) next; g no longer needed
        Imgproc.GaussianBlur(sq, sq, Size(TEXTURE_WIN.toDouble(), TEXTURE_WIN.toDouble()), 0.0)
        g.release()
        val meanSq = Mat()
        Core.multiply(mean, mean, meanSq)
        mean.release()
        Core.subtract(sq, meanSq, sq)
        meanSq.release()
        Core.max(sq, Scalar.all(0.0), sq)
        Core.sqrt(sq, sq)
        val texMask = Mat()
        Core.compare(sq, Scalar(TEXTURE_STD_THRESH), texMask, Core.CMP_GE)
        sq.release()

        // Papery HSV test, same thresholds as extendSides: drops fabric, foliage,
        // dark keyboard texture — keeps paper-ish texture only.
        val vMat = Mat()
        val sMat = Mat()
        Core.extractChannel(hsv, vMat, 2)
        Core.extractChannel(hsv, sMat, 1)
        val vBlur = Mat()
        val sBlur = Mat()
        Imgproc.blur(vMat, vBlur, Size(9.0, 9.0))
        Imgproc.blur(sMat, sBlur, Size(9.0, 9.0))
        vMat.release(); sMat.release()
        val papery = Mat()
        val tmp = Mat()
        Core.compare(vBlur, Scalar(120.0), papery, Core.CMP_GT)
        Core.compare(sBlur, Scalar(80.0), tmp, Core.CMP_LT)
        Core.bitwise_and(papery, tmp, papery)
        Core.compare(vBlur, Scalar(85.0), tmp, Core.CMP_GT)
        val tmp2 = Mat()
        Core.compare(sBlur, Scalar(60.0), tmp2, Core.CMP_LT)
        Core.bitwise_and(tmp, tmp2, tmp)
        Core.bitwise_or(papery, tmp, papery)
        vBlur.release(); sBlur.release(); tmp.release(); tmp2.release()
        Core.bitwise_and(texMask, papery, texMask)
        papery.release()

        val k25 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
        val k9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        Imgproc.morphologyEx(texMask, texMask, Imgproc.MORPH_CLOSE, k25)
        Imgproc.morphologyEx(texMask, texMask, Imgproc.MORPH_OPEN, k9)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(texMask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        texMask.release()

        val out = ArrayList<Pair<DoubleArray, Double>>()
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }
        for (c in sorted.take(TEXTURE_MAX_COMPS)) {
            val ca = Imgproc.contourArea(c)
            if (ca < TEXTURE_MIN_FRAC * frameArea) break
            val hullIdx = org.opencv.core.MatOfInt()
            Imgproc.convexHull(c, hullIdx)
            val cPts = c.toArray()
            val hullPts = hullIdx.toArray().map { cPts[it.toInt()] }
            hullIdx.release()
            val hull2f = MatOfPoint2f(*hullPts.toTypedArray())
            var approx = hull2f
            for (eps in intArrayOf(2, 3, 5, 8)) {
                val a = MatOfPoint2f()
                Imgproc.approxPolyDP(hull2f, a, eps * 0.01 * Imgproc.arcLength(hull2f, true), true)
                approx = a
                if (a.total() <= 6L) break
            }
            var q: DoubleArray? = null
            if (approx.total() == 4L) {
                q = orderCorners(approx.toArray().map { it.x to it.y })
            } else {
                val rot = Imgproc.minAreaRect(hull2f)
                if (rot.size.width >= 1 && rot.size.height >= 1) {
                    val boxPts = Array(4) { org.opencv.core.Point() }
                    rot.points(boxPts)
                    q = orderCorners(boxPts.map { it.x to it.y })
                }
            }
            if (q != null) {
                val qa = polyArea(q)
                if (qa > 0.0) {
                    val fill = min(1.0, ca / qa)
                    if (fill >= TEXTURE_MIN_FILL) out.add(q to fill)
                }
            }
        }
        return out
    }

    /** Borders of the photo are bright and unsaturated -> the document fills the frame. */
    private fun frameIsPaperish(hsv: Mat, sw: Int, sh: Int): Boolean {
        val t = max(1, sh / 40)
        val s = max(1, sw / 40)
        val strips = ArrayList<Mat>(4)
        strips.add(hsv.submat(0, t, 0, sw))
        strips.add(hsv.submat(sh - t, sh, 0, sw))
        strips.add(hsv.submat(0, sh, 0, s))
        strips.add(hsv.submat(0, sh, sw - s, sw))
        var sumV = 0.0
        var sumS = 0.0
        var count = 0
        var bright = 0
        for (strip in strips) {
            val mean = Core.mean(strip)
            val n = strip.rows().toDouble() * strip.cols()
            sumV += mean.`val`[2] * n
            sumS += mean.`val`[1] * n
            count += strip.rows() * strip.cols()
            // fraction of pixels with V > 110
            val vCh = Mat()
            Core.extractChannel(strip, vCh, 2)
            val brightMask = Mat()
            Core.compare(vCh, Scalar(110.0), brightMask, Core.CMP_GT)
            bright += Core.countNonZero(brightMask)
            brightMask.release()
            vCh.release()
            strip.release()
        }
        val meanV = sumV / count
        val meanS = sumS / count
        return meanV > 120.0 && meanS < 80.0 && bright.toDouble() / count > 0.55
    }

    /** Fit a line to edge pixels near each side; new corners are consecutive line intersections. */
    private fun refineQuad(q: DoubleArray, edges: Mat): DoubleArray {
        val eh = edges.rows()
        val ew = edges.cols()
        val all = ByteArray((eh * ew))
        edges.get(0, 0, all)
        val px = ArrayList<Float>()
        val py = ArrayList<Float>()
        for (y in 0 until eh) {
            for (x in 0 until ew) {
                if (all[y * ew + x].toInt() != 0) {
                    px.add(x.toFloat())
                    py.add(y.toFloat())
                }
            }
        }
        val n = px.size
        if (n < 40) return q
        val diag = kotlin.math.sqrt(ew.toDouble() * ew + eh.toDouble() * eh)

        val lines = arrayOfNulls<DoubleArray>(4)
        for (side in 0 until 4) {
            val ax = q[2 * side]
            val ay = q[2 * side + 1]
            val j = (side + 1) % 4
            val bx = q[2 * j]
            val by = q[2 * j + 1]
            val abx = bx - ax
            val aby = by - ay
            val len = kotlin.math.sqrt(abx * abx + aby * aby)
            if (len < 1e-3) continue
            val nx = -aby / len
            val ny = abx / len
            val sel = ArrayList<Float>()
            val minCount = max(12.0, 0.04 * len).toInt()
            val fx = FloatArray(n)
            val fy = FloatArray(n)
            var m = 0
            for (i in 0 until n) {
                val dx = px[i] - ax.toFloat()
                val dy = py[i] - ay.toFloat()
                val d = dx * nx + dy * ny
                val t = (dx * abx + dy * aby) / len
                if (kotlin.math.abs(d) < 7.0 && t > -0.05 * len && t < 1.05 * len) {
                    fx[m] = px[i]
                    fy[m] = py[i]
                    m++
                }
            }
            if (m < minCount) continue
            val mat = MatOfPoint2f(*Array(m) { org.opencv.core.Point(fx[it].toDouble(), fy[it].toDouble()) })
            val line = Mat()
            Imgproc.fitLine(mat, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            // fitLine emits CV_32F: (vx, vy, x0, y0)
            val lf = FloatArray(4)
            line.get(0, 0, lf)
            val l = DoubleArray(4) { lf[it].toDouble() }
            lines[side] = l
            line.release()
        }

        val corners = ArrayList<Pair<Double, Double>>(4)
        for (i in 0 until 4) {
            val l1 = lines[(i + 3) % 4]
            val l2 = lines[i]
            val ox = q[2 * i]
            val oy = q[2 * i + 1]
            if (l1 == null || l2 == null) {
                corners.add(ox to oy)
                continue
            }
            val p = lineIsect(l1, l2)
            if (p == null || hypot(p.first - ox, p.second - oy) > 0.06 * diag) {
                corners.add(ox to oy)
            } else {
                corners.add(p.first to p.second)
            }
        }
        val refined = orderCorners(corners)
        if (polyArea(refined) < 0.3 * polyArea(q)) return q // refinement collapsed
        // Fitted lines can intersect outside the photo; a scan crop never
        // extends beyond the picture, so clamp corners into the frame.
        return DoubleArray(8) { i ->
            if (i % 2 == 0) refined[i].coerceIn(0.0, (ew - 1).toDouble())
            else refined[i].coerceIn(0.0, (eh - 1).toDouble())
        }
    }

    private fun lineIsect(l1: DoubleArray, l2: DoubleArray): Pair<Double, Double>? {
        // fitLine order: (dir x, dir y, point x, point y)
        val den = l1[0] * l2[1] - l1[1] * l2[0]
        if (kotlin.math.abs(den) < 1e-6) return null
        val t = ((l2[2] - l1[2]) * l2[1] - (l2[3] - l1[3]) * l2[0]) / den
        return (l1[2] + t * l1[0]) to (l1[3] + t * l1[1])
    }

    /** Fraction of the inner quad's area covered by the outer quad. */
    private fun containment(inner: DoubleArray, outer: DoubleArray): Double {
        val a = MatOfPoint2f(
            org.opencv.core.Point(inner[0], inner[1]),
            org.opencv.core.Point(inner[2], inner[3]),
            org.opencv.core.Point(inner[4], inner[5]),
            org.opencv.core.Point(inner[6], inner[7]),
        )
        val b = MatOfPoint2f(
            org.opencv.core.Point(outer[0], outer[1]),
            org.opencv.core.Point(outer[2], outer[3]),
            org.opencv.core.Point(outer[4], outer[5]),
            org.opencv.core.Point(outer[6], outer[7]),
        )
        val inter = Mat()
        val area = Imgproc.intersectConvexConvex(a, b, inter, true)
        inter.release()
        val ai = polyArea(inner)
        return if (ai <= 0.0) 0.0 else area / ai
    }

    /**
     * Grow each side outward through papery pixels (document continues past
     * the proposed boundary), stopping at a continuous boundary line or
     * non-paper. Repairs partial quads that cut content: shadowed paper
     * (low V, low S) still counts as papery, scattered text edges don't stop
     * growth but a line running along the whole side does (spine, plate rim).
     * Growth never leaves the frame. Mirrors improved.extend_sides() 1:1.
     */
    private fun extendSides(q: DoubleArray, hsv: Mat, edges: Mat, sw: Int, sh: Int): DoubleArray {
        val w = sw
        val h = sh
        val vMat = Mat()
        val sMat = Mat()
        Core.extractChannel(hsv, vMat, 2)
        Core.extractChannel(hsv, sMat, 1)
        val vBlur = Mat()
        val sBlur = Mat()
        Imgproc.blur(vMat, vBlur, Size(9.0, 9.0))
        Imgproc.blur(sMat, sBlur, Size(9.0, 9.0))
        vMat.release(); sMat.release()
        val nPx = w * h
        val vBuf = ByteArray(nPx)
        val sBuf = ByteArray(nPx)
        val eBuf = ByteArray(nPx)
        vBlur.get(0, 0, vBuf)
        sBlur.get(0, 0, sBuf)
        edges.get(0, 0, eBuf)
        vBlur.release(); sBlur.release()

        val pts = Array(4) { q[2 * it] to q[2 * it + 1] }
        val cx = (pts[0].first + pts[1].first + pts[2].first + pts[3].first) / 4.0
        val cy = (pts[0].second + pts[1].second + pts[2].second + pts[3].second) / 4.0
        val diag = kotlin.math.sqrt(h.toDouble() * h + w.toDouble() * w)
        val maxExt = 0.30 * diag
        val k = 24
        val ts = DoubleArray(k) { 0.1 + it * (0.9 - 0.1) / (k - 1) }
        // per side: (direction x,y, anchor point x,y) of the extended side, or null
        val extLines = arrayOfNulls<Pair<DoubleArray, DoubleArray>>(4)

        fun paperyAt(x: Int, y: Int): Boolean {
            val xi = x.coerceIn(0, w - 1)
            val yi = y.coerceIn(0, h - 1)
            val s = sBuf[yi * w + xi].toInt() and 0xFF
            val v = vBuf[yi * w + xi].toInt() and 0xFF
            return (v > 120 && s < 80) || (v > 85 && s < 60)
        }

        fun onLineAt(x: Int, y: Int): Boolean {
            val xi = x.coerceIn(0, w - 1)
            val yi = y.coerceIn(0, h - 1)
            return (eBuf[yi * w + xi].toInt() and 0xFF) > 0
        }

        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val abx = b.first - a.first
            val aby = b.second - a.second
            var len = kotlin.math.sqrt(abx * abx + aby * aby)
            if (len == 0.0) len = 1.0
            var nx = -aby / len
            var ny = abx / len
            val midx = (a.first + b.first) / 2.0
            val midy = (a.second + b.second) / 2.0
            if (nx * (cx - midx) + ny * (cy - midy) > 0) { // point outward
                nx = -nx; ny = -ny
            }
            val xi = IntArray(k)
            val yi = IntArray(k)
            var ext = 0.0
            var d = 2.0
            while (d <= maxExt) {
                var inside = 0
                for (s in 0 until k) {
                    val px = a.first + ts[s] * abx + d * nx
                    val py = a.second + ts[s] * aby + d * ny
                    xi[s] = Math.round(px).toInt()
                    yi[s] = Math.round(py).toInt()
                    if (xi[s] in 0 until w && yi[s] in 0 until h) inside++
                }
                if (inside < 0.9 * k) break // keep the quad inside the frame
                var papery = 0
                for (s in 0 until k) if (paperyAt(xi[s], yi[s])) papery++
                if (papery < 0.65 * k) break
                // a line continuous along the whole side is a boundary unless
                // the region beyond it is papery too (internal rule -> skip)
                var run = 0
                var best = 0
                for (s in 0 until k) {
                    run = if (onLineAt(xi[s], yi[s])) run + 1 else 0
                    if (run > best) best = run
                }
                if (best >= 0.75 * k) {
                    val d2 = d + 8.0
                    var inside2 = 0
                    for (s in 0 until k) {
                        val px = a.first + ts[s] * abx + d2 * nx
                        val py = a.second + ts[s] * aby + d2 * ny
                        if (px >= 0 && px < w && py >= 0 && py < h) inside2++
                    }
                    if (inside2 < 0.9 * k) break
                    var papery2 = 0
                    for (s in 0 until k) {
                        val px = a.first + ts[s] * abx + d2 * nx
                        val py = a.second + ts[s] * aby + d2 * ny
                        if (paperyAt(Math.round(px).toInt(), Math.round(py).toInt())) papery2++
                    }
                    if (papery2 < 0.65 * k) break
                }
                ext = d
                d += 2.0
            }
            if (ext >= 6.0) {
                extLines[i] = doubleArrayOf(abx / len, aby / len) to
                    doubleArrayOf(midx + ext * nx, midy + ext * ny)
            }
        }
        // New corner i = intersection of extended side i-1 and side i.
        val corners = ArrayList<Pair<Double, Double>>(4)
        for (i in 0 until 4) {
            val l1 = extLines[(i + 3) % 4]
            val l2 = extLines[i]
            if (l1 == null || l2 == null) {
                corners.add(pts[i])
                continue
            }
            // lineIsect expects fitLine order (dir x,y, point x,y)
            val p = lineIsect(
                doubleArrayOf(l1.first[0], l1.first[1], l1.second[0], l1.second[1]),
                doubleArrayOf(l2.first[0], l2.first[1], l2.second[0], l2.second[1]),
            )
            corners.add(p ?: pts[i])
        }
        val q2 = orderCorners(corners)
        // Same frame clamp: extended side lines may intersect off-canvas.
        val q2c = DoubleArray(8) { i ->
            if (i % 2 == 0) q2[i].coerceIn(0.0, (w - 1).toDouble())
            else q2[i].coerceIn(0.0, (h - 1).toDouble())
        }
        val a0 = polyArea(q)
        val a1 = polyArea(q2c)
        return if (0.5 * a0 <= a1 && a1 <= 3.0 * a0) q2c else q
    }

    /** Per-side fraction of sampled points sitting on/near an edge pixel
     *  (14 samples per side, band = 3 px). A true document boundary has an
     *  edge line running along every side; a winner whose sides cut through
     *  open paper shows weak sides. Mirrors improved.side_supports() 1:1. */
    private fun sideSupports(q: DoubleArray, edges: Mat): DoubleArray {
        val band = 3
        val dil = Mat()
        Imgproc.dilate(
            edges, dil,
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size((2 * band + 1).toDouble(), (2 * band + 1).toDouble()))
        )
        val ew = dil.cols()
        val eh = dil.rows()
        val buf = ByteArray(ew * eh)
        dil.get(0, 0, buf)
        dil.release()
        val k = 14
        val out = DoubleArray(4)
        for (i in 0 until 4) {
            val ax = q[2 * i]
            val ay = q[2 * i + 1]
            val j = (i + 1) % 4
            val bx = q[2 * j]
            val by = q[2 * j + 1]
            var hit = 0
            var total = 0
            for (s in 0 until k) {
                val t = 0.08 + s * (0.92 - 0.08) / (k - 1)
                val xi = Math.round(ax + t * (bx - ax)).toInt()
                val yi = Math.round(ay + t * (by - ay)).toInt()
                if (xi in 0 until ew && yi in 0 until eh) {
                    total++
                    if ((buf[yi * ew + xi].toInt() and 0xFF) > 0) hit++
                }
            }
            out[i] = if (total == 0) 0.0 else hit.toDouble() / total
        }
        return out
    }

    private fun halfEven(v: Double): Double {
        val f = kotlin.math.floor(v)
        val diff = v - f
        return when {
            diff > 0.5 -> f + 1
            diff < 0.5 -> f
            f % 2 == 0.0 -> f
            else -> f + 1
        }
    }

    private fun polyArea(q: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
        }
        return kotlin.math.abs(s) / 2
    }

    /** Rotates the quad so it starts at the TL corner (min x+y), keeping cyclic order. */
    private fun orderCorners(pts: List<Pair<Double, Double>>): DoubleArray {
        val cx = pts.map { it.first }.average()
        val cy = pts.map { it.second }.average()
        val sorted = pts.sortedBy { atan2(it.second - cy, it.first - cx) }
        val startIdx = sorted.indices.minBy { sorted[it].first + sorted[it].second }
        val ordered = List(4) { k -> sorted[(startIdx + k) % 4] }
        return DoubleArray(8) { i -> if (i % 2 == 0) ordered[i / 2].first else ordered[i / 2].second }
    }
}

/** Four-point perspective correction: rect destination ← quad source, bilinear sampling. */
private object Perspective {

    fun warpToRect(src: Bitmap, quad: FloatArray, maxEdge: Int = 1600): Bitmap {
        val top = dist(quad[0], quad[1], quad[2], quad[3])
        val bottom = dist(quad[6], quad[7], quad[4], quad[5])
        val left = dist(quad[0], quad[1], quad[6], quad[7])
        val right = dist(quad[2], quad[3], quad[4], quad[5])
        val outW = max(32, min(maxEdge, max(top, bottom).roundToInt()))
        val outH = max(32, min(maxEdge, max(left, right).roundToInt()))

        // Solve the homography mapping destination rect corners → source quad.
        val dst = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val hgt = homography(dst, quad) ?: return fallbackStretch(src, quad, outW, outH)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(outW * outH)
        val srcPixels = IntArray(src.width * src.height)
        src.getPixels(srcPixels, 0, src.width, 0, 0, src.width, src.height)
        for (y in 0 until outH) {
            for (x in 0 until outW) {
                val denom = hgt[6] * x + hgt[7] * y + 1f
                val sx = (hgt[0] * x + hgt[1] * y + hgt[2]) / denom
                val sy = (hgt[3] * x + hgt[4] * y + hgt[5]) / denom
                pixels[y * outW + x] = sampleBilinear(srcPixels, src.width, src.height, sx, sy)
            }
        }
        out.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return out
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot((bx - ax).toDouble(), (by - ay).toDouble()).toFloat()

    private fun fallbackStretch(src: Bitmap, quad: FloatArray, outW: Int, outH: Int): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val path = android.graphics.Path().apply {
            moveTo(quad[0], quad[1]); lineTo(quad[2], quad[3]); lineTo(quad[4], quad[5]); lineTo(quad[6], quad[7]); close()
        }
        canvas.clipPath(path)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    /** Exact 8-parameter solve for 4 correspondences (Gauss with partial pivot). */
    private fun homography(src: FloatArray, dst: FloatArray): FloatArray? {
        val a = FloatArray(8 * 9)
        for (i in 0 until 4) {
            val xs = src[2 * i]; val ys = src[2 * i + 1]
            val xd = dst[2 * i]; val yd = dst[2 * i + 1]
            val r1 = i * 18
            // Two rows per correspondence.
            val rows = listOf(
                floatArrayOf(xs, ys, 1f, 0f, 0f, 0f, -xd * xs, -xd * ys, xd),
                floatArrayOf(0f, 0f, 0f, xs, ys, 1f, -yd * xs, -yd * ys, yd),
            )
            rows.forEachIndexed { k, row -> System.arraycopy(row, 0, a, r1 + k * 9, 9) }
        }
        // Gaussian elimination.
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) if (abs(a[r * 9 + col]) > abs(a[pivot * 9 + col])) pivot = r
            if (abs(a[pivot * 9 + col]) < 1e-6f) return null
            if (pivot != col) for (c in 0 until 9) { val t = a[col * 9 + c]; a[col * 9 + c] = a[pivot * 9 + c]; a[pivot * 9 + c] = t }
            for (r in 0 until 8) {
                if (r == col) continue
                val f = a[r * 9 + col] / a[col * 9 + col]
                if (f == 0f) continue
                for (c in col until 9) a[r * 9 + c] -= f * a[col * 9 + c]
            }
        }
        return FloatArray(8) { i -> a[i * 9 + 8] / a[i * 9 + i] }
    }

    private fun sampleBilinear(pixels: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xf = x.coerceIn(0f, (w - 1).toFloat())
        val yf = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
        val fx = xf - x0; val fy = yf - y0
        val c00 = pixels[y0 * w + x0]; val c10 = pixels[y0 * w + x1]
        val c01 = pixels[y1 * w + x0]; val c11 = pixels[y1 * w + x1]
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) { // R, G, B — alpha assumed opaque
            val v = (c00 shr shift and 0xFF) * (1 - fx) * (1 - fy) +
                (c10 shr shift and 0xFF) * fx * (1 - fy) +
                (c01 shr shift and 0xFF) * (1 - fx) * fy +
                (c11 shr shift and 0xFF) * fx * fy
            out = out or (v.roundToInt().coerceIn(0, 255) shl shift)
        }
        return out or 0xFF000000.toInt()
    }
}
