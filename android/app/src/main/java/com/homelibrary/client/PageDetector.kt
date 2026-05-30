package com.homelibrary.client

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects the corners of a book page in a photograph.
 *
 * Algorithm:
 *  1. Downsample to ≤600 px for speed.
 *  2. Compute per-row and per-column average brightness profiles to robustly
 *     locate the approximate page boundary (page is the bright region).
 *  3. Collect Sobel-gradient edge pixels in a narrow band around each
 *     approximate boundary position, keeping only pixels whose gradient
 *     direction matches the expected page↔background transition.
 *  4. Filter outliers (MAD) and fit a line through each of the four bands.
 *  5. Intersect the four lines → four corners.
 *  6. Scale corners back to original bitmap coordinates.
 */
object PageDetector {

    /**
     * Returns [TL, TR, BR, BL] in original bitmap pixel coordinates,
     * or null if no clear page boundary is found.
     */
    fun detectCorners(bitmap: Bitmap): Array<PointF>? {
        val maxDim = 600
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1))
        val sw = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val sh = (bitmap.height * scale).toInt().coerceAtLeast(1)

        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val gray = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            val c = small.getPixel(x, y)
            gray[y * sw + x] =
                (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        }
        small.recycle()

        // ── Sobel gradients ──────────────────────────────────────────────────
        val gx = IntArray(sw * sh)
        val gy = IntArray(sw * sh)
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val g = { dy: Int, dx: Int -> gray[(y + dy) * sw + (x + dx)] }
                gx[y * sw + x] = (g(-1, 1) + 2 * g(0, 1) + g(1, 1)) -
                                  (g(-1,-1) + 2 * g(0,-1) + g(1,-1))
                gy[y * sw + x] = (g(1,-1) + 2 * g(1, 0) + g(1, 1)) -
                                  (g(-1,-1) + 2 * g(-1,0) + g(-1,1))
            }
        }
        val mags = IntArray(sw * sh) { i ->
            sqrt(gx[i].toFloat() * gx[i] + gy[i].toFloat() * gy[i].toDouble()).toInt()
        }
        val sortedMags = mags.copyOf().also { it.sort() }
        val edgeThresh = sortedMags[(sortedMags.size * 0.80).toInt()].coerceAtLeast(10)

        // ── Brightness profiles ──────────────────────────────────────────────
        // Row profile: average gray across the center 60% of columns.
        // Bright rows → page interior; dim rows → background above/below.
        val xL = sw / 5; val xR = sw * 4 / 5
        val rowAvg = FloatArray(sh) { y ->
            var s = 0
            for (x in xL until xR) s += gray[y * sw + x]
            s.toFloat() / (xR - xL)
        }

        // Column profile: average gray across the center 60% of rows.
        val yT = sh / 5; val yB = sh * 4 / 5
        val colAvg = FloatArray(sw) { x ->
            var s = 0
            for (y in yT until yB) s += gray[y * sw + x]
            s.toFloat() / (yB - yT)
        }

        val rowSmooth = smooth(rowAvg)
        val colSmooth = smooth(colAvg)

        // Threshold: 55 % of the peak (page) brightness.
        val rowPeak = rowSmooth.max()!!
        val colPeak = colSmooth.max()!!
        val rowThresh = rowPeak * 0.55f
        val colThresh = colPeak * 0.55f

        // ── Approximate boundary positions from profiles ──────────────────────
        val margin = sw / 30  // ~3 %
        val topApprox  = (margin until sh / 2).firstOrNull { rowSmooth[it] >= rowThresh } ?: return null
        val botApprox  = (sh - 1 - margin downTo sh / 2).firstOrNull { rowSmooth[it] >= rowThresh } ?: return null
        val leftApprox = (margin until sw / 2).firstOrNull { colSmooth[it] >= colThresh } ?: return null
        val rightApprox= (sw - 1 - margin downTo sw / 2).firstOrNull { colSmooth[it] >= colThresh } ?: return null

        // ── Collect edge pixels in bands and fit lines ────────────────────────
        val bandY = (sh * 0.05f).toInt().coerceAtLeast(4)
        val bandX = (sw * 0.05f).toInt().coerceAtLeast(4)

        val topPts   = hBandPoints(gy, mags, edgeThresh, sw, sh, topApprox,   bandY, positive = true)
        val botPts   = hBandPoints(gy, mags, edgeThresh, sw, sh, botApprox,   bandY, positive = false)
        val leftPts  = vBandPoints(gx, mags, edgeThresh, sw, sh, leftApprox,  bandX, positive = true)
        val rightPts = vBandPoints(gx, mags, edgeThresh, sw, sh, rightApprox, bandX, positive = false)

        if (topPts.size < 5 || botPts.size < 5 ||
            leftPts.size < 5 || rightPts.size < 5) return null

        val topLine   = fitH(filterOutliers(topPts,   byY = true))  ?: return null
        val botLine   = fitH(filterOutliers(botPts,   byY = true))  ?: return null
        val leftLine  = fitV(filterOutliers(leftPts,  byY = false)) ?: return null
        val rightLine = fitV(filterOutliers(rightPts, byY = false)) ?: return null

        val tl = intersect(topLine, leftLine)  ?: return null
        val tr = intersect(topLine, rightLine) ?: return null
        val br = intersect(botLine, rightLine) ?: return null
        val bl = intersect(botLine, leftLine)  ?: return null

        if (quadArea(tl, tr, br, bl) < sw * sh * 0.10f) return null
        if (tl.x >= tr.x || bl.x >= br.x || tl.y >= bl.y || tr.y >= br.y) return null

        val inv = 1f / scale
        return arrayOf(
            PointF(tl.x * inv, tl.y * inv),
            PointF(tr.x * inv, tr.y * inv),
            PointF(br.x * inv, br.y * inv),
            PointF(bl.x * inv, bl.y * inv)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun smooth(arr: FloatArray, w: Int = 7): FloatArray {
        val out = FloatArray(arr.size)
        for (i in arr.indices) {
            var s = 0f; var n = 0
            for (d in -w..w) {
                val j = i + d
                if (j in arr.indices) { s += arr[j]; n++ }
            }
            out[i] = s / n
        }
        return out
    }

    private fun hBandPoints(
        gy: IntArray, mags: IntArray, thresh: Int,
        sw: Int, sh: Int, centerY: Int, band: Int, positive: Boolean
    ): List<PointF> {
        val pts = mutableListOf<PointF>()
        for (y in maxOf(0, centerY - band)..minOf(sh - 1, centerY + band)) {
            for (x in 0 until sw) {
                val i = y * sw + x
                if (mags[i] > thresh && (if (positive) gy[i] > 0 else gy[i] < 0))
                    pts.add(PointF(x.toFloat(), y.toFloat()))
            }
        }
        return pts
    }

    private fun vBandPoints(
        gx: IntArray, mags: IntArray, thresh: Int,
        sw: Int, sh: Int, centerX: Int, band: Int, positive: Boolean
    ): List<PointF> {
        val pts = mutableListOf<PointF>()
        for (y in 0 until sh) {
            for (x in maxOf(0, centerX - band)..minOf(sw - 1, centerX + band)) {
                val i = y * sw + x
                if (mags[i] > thresh && (if (positive) gx[i] > 0 else gx[i] < 0))
                    pts.add(PointF(x.toFloat(), y.toFloat()))
            }
        }
        return pts
    }

    private fun filterOutliers(pts: List<PointF>, byY: Boolean): List<PointF> {
        val vals = if (byY) pts.map { it.y } else pts.map { it.x }
        val sorted = vals.sorted()
        val median = sorted[sorted.size / 2]
        val mad = vals.map { abs(it - median) }.sorted()
            .let { it[it.size / 2] }.coerceAtLeast(1f)
        return pts.filter { abs((if (byY) it.y else it.x) - median) <= 3f * mad }
    }

    // ── Line fitting ─────────────────────────────────────────────────────────

    private data class HLine(val a: Double, val b: Double)
    private data class VLine(val a: Double, val b: Double)

    private fun fitH(pts: List<PointF>): HLine? {
        if (pts.size < 2) return null
        val n = pts.size.toDouble()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (p in pts) { sx += p.x; sy += p.y; sxx += p.x * p.x; sxy += p.x * p.y }
        val d = n * sxx - sx * sx
        if (abs(d) < 1e-6) return HLine(0.0, sy / n)
        val a = (n * sxy - sx * sy) / d
        return HLine(a, (sy - a * sx) / n)
    }

    private fun fitV(pts: List<PointF>): VLine? {
        if (pts.size < 2) return null
        val n = pts.size.toDouble()
        var sx = 0.0; var sy = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pts) { sx += p.x; sy += p.y; syy += p.y * p.y; sxy += p.x * p.y }
        val d = n * syy - sy * sy
        if (abs(d) < 1e-6) return VLine(0.0, sx / n)
        val a = (n * sxy - sy * sx) / d
        return VLine(a, (sx - a * sy) / n)
    }

    private fun intersect(h: HLine, v: VLine): PointF? {
        val d = 1.0 - h.a * v.a
        if (abs(d) < 1e-9) return null
        val x = (v.a * h.b + v.b) / d
        val y = h.a * x + h.b
        return PointF(x.toFloat(), y.toFloat())
    }

    private fun quadArea(tl: PointF, tr: PointF, br: PointF, bl: PointF): Float {
        val pts = arrayOf(tl, tr, br, bl)
        var area = 0f
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            area += pts[i].x * pts[j].y - pts[j].x * pts[i].y
        }
        return abs(area) / 2f
    }
}
