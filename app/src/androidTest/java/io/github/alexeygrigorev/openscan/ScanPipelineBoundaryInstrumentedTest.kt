package io.github.alexeygrigorev.openscan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexeygrigorev.openscan.scan.ScanPipeline
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/** Procedural fixtures keep private documents out of the regression tests. */
@RunWith(AndroidJUnit4::class)
class ScanPipelineBoundaryInstrumentedTest {
    @Test
    fun printedTableDoesNotReplaceTheFrameFillingPage() {
        val image = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(239, 229, 225))
        val ink = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
        for (y in 180..460 step 40) canvas.drawLine(120f, y.toFloat(), 850f, y.toFloat(), ink)
        for (y in 740..1040 step 30) canvas.drawLine(180f, y.toFloat(), 820f, y.toFloat(), ink)
        for (x in 180..820 step 160) canvas.drawLine(x.toFloat(), 740f, x.toFloat(), 1040f, ink)
        try {
            assertCorners(image, floatArrayOf(0f, 0f, 999f, 0f, 999f, 1399f, 0f, 1399f))
        } finally {
            image.recycle()
        }
    }

    @Test
    fun blankNeutralDeskDoesNotReplaceASmallCard() {
        val image = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.rgb(225, 225, 225))
        val paint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(330f, 280f, 950f, 680f, paint)
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawRect(330f, 280f, 950f, 680f, paint)
        for (y in 350..550 step 50) canvas.drawLine(400f, y.toFloat(), 850f, y.toFloat(), paint)
        try {
            assertCorners(image, floatArrayOf(330f, 280f, 950f, 280f, 950f, 680f, 330f, 680f))
        } finally {
            image.recycle()
        }
    }

    private fun assertCorners(image: Bitmap, expected: FloatArray) {
        val quad = ScanPipeline.detectQuad(image)
        assertNotNull("document quad", quad)
        val tolerance = 0.02 * hypot(image.width.toDouble(), image.height.toDouble())
        for (i in 0..3) {
            val distance = hypot((quad!![2 * i] - expected[2 * i]).toDouble(),
                (quad[2 * i + 1] - expected[2 * i + 1]).toDouble())
            assertTrue("corner $i distance $distance > $tolerance", distance <= tolerance)
        }
    }
}
