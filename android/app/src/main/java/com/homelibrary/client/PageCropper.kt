package com.homelibrary.client

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Applies a perspective warp to straighten a quadrilateral region of an image,
 * then normalises white-point so the paper background appears pure white.
 *
 * Extracted from CropActivity so that it can be called from tests.
 */
object PageCropper {

    /**
     * Warps [src] so that the quadrilateral [pts] ([TL, TR, BR, BL] in bitmap
     * pixel coordinates) becomes a straight rectangle, then applies white-point
     * balance (95th-percentile per channel → 255).
     */
    fun warpAndBalance(src: Bitmap, pts: Array<PointF>): Bitmap {
        val tl = pts[0]; val tr = pts[1]; val br = pts[2]; val bl = pts[3]
        val outW = maxOf(dist(tl, tr), dist(bl, br)).roundToInt().coerceIn(50, 4096)
        val outH = maxOf(dist(tl, bl), dist(tr, br)).roundToInt().coerceIn(50, 4096)

        val srcPts = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
        val dstPts = floatArrayOf(0f, 0f, outW.toFloat(), 0f,
                                  outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4))
            throw IllegalArgumentException("Corners are degenerate — cannot warp")

        val warped = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(warped).drawBitmap(src, matrix, null)

        val balanced = applyWhiteBalance(warped)
        warped.recycle()
        return balanced
    }

    /**
     * Scales each RGB channel so that its 95th-percentile value maps to 255.
     */
    private fun applyWhiteBalance(src: Bitmap): Bitmap {
        val rH = IntArray(256); val gH = IntArray(256); val bH = IntArray(256)
        var count = 0
        for (y in 0 until src.height step 4) {
            for (x in 0 until src.width step 4) {
                val px = src.getPixel(x, y)
                rH[Color.red(px)]++; gH[Color.green(px)]++; bH[Color.blue(px)]++
                count++
            }
        }
        val target = (count * 0.95f).toInt().coerceAtLeast(1)
        val wpR = percentile(rH, target).coerceAtLeast(1)
        val wpG = percentile(gH, target).coerceAtLeast(1)
        val wpB = percentile(bH, target).coerceAtLeast(1)

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                255f / wpR, 0f, 0f, 0f, 0f,
                0f, 255f / wpG, 0f, 0f, 0f,
                0f, 0f, 255f / wpB, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        })
        return out
    }

    private fun percentile(hist: IntArray, target: Int): Int {
        var sum = 0
        for (i in 0..255) { sum += hist[i]; if (sum >= target) return i }
        return 255
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
