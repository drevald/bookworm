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
 *  2. Compute Sobel gradients (Gx, Gy).
 *  3. For each of the four edges, scan outward→inward looking for the first
 *     row or column that has a LONG continuous run of directional edge pixels.
 *     This distinguishes the page boundary (one long edge spanning the full
 *     image width / height) from background texture or text (short isolated
 *     strokes).  Gaps of up to MAX_GAP pixels are tolerated to handle JPEG
 *     compression artefacts and minor lighting irregularities.
 *  4. Collect edge pixels in a narrow band around the found row/column.
 *  5. Remove outliers (MAD) and fit a line through each band.
 *  6. Intersect the four lines → four corners.
 *  7. Scale corners back to original bitmap coordinates.
 */
object PageDetector {

    private const val MAX_GAP = 8       // px gap allowed inside a "run"
    private const val MIN_RUN_FRAC = 0.20f  // run must span ≥ 20 % of scan axis
    private const val BAND_FRAC = 0.04f    // collect points within ±4 % of image size

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

        // ── Edge magnitude threshold (top 10 %) ─────────────────────────────
        val mags = IntArray(sw * sh) { i ->
            sqrt(gx[i].toFloat() * gx[i] + gy[i].toFloat() * gy[i].toDouble()).toInt()
        }
        val sorted = mags.copyOf().also { it.sort() }
        val thresh = sorted[(sorted.size * 0.90).toInt()].coerceAtLeast(20)

        // ── Find each boundary row / column ──────────────────────────────────
        val margin = 0.06f  // skip outer 6 % to avoid image-border artefacts

        val topRow   = findEdgeRow(gy, mags, thresh, sw, sh, fromTop  = true,  margin) ?: return null
        val botRow   = findEdgeRow(gy, mags, thresh, sw, sh, fromTop  = false, margin) ?: return null
        val leftCol  = findEdgeCol(gx, mags, thresh, sw, sh, fromLeft = true,  margin) ?: return null
        val rightCol = findEdgeCol(gx, mags, thresh, sw, sh, fromLeft = false, margin) ?: return null

        // ── Collect band of points and fit lines ─────────────────────────────
        val bandY = (sh * BAND_FRAC).toInt().coerceAtLeast(3)
        val bandX = (sw * BAND_FRAC).toInt().coerceAtLeast(3)

        val topPts   = hBandPoints(gy, mags, thresh, sw, sh, topRow,   bandY, positive = true)
        val botPts   = hBandPoints(gy, mags, thresh, sw, sh, botRow,   bandY, positive = false)
        val leftPts  = vBandPoints(gx, mags, thresh, sw, sh, leftCol,  bandX, positive = true)
        val rightPts = vBandPoints(gx, mags, thresh, sw, sh, rightCol, bandX, positive = false)

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

    // ── Edge-row finder ──────────────────────────────────────────────────────

    /**
     * Scans rows from the outer edge inward.
     * Returns the first row whose longest run of qualifying horizontal-gradient
     * pixels spans at least [MIN_RUN_FRAC] × sw.
     *
     * Qualifying pixel: magnitude > thresh AND Gy sign matches page–background
     * transition direction (positive → dark above / light below = page top;
     * negative → light above / dark below = page bottom).
     */
    private fun findEdgeRow(
        gy: IntArray, mags: IntArray, thresh: Int,
        sw: Int, sh: Int, fromTop: Boolean, margin: Float
    ): Int? {
        val mx = (sw * margin).toInt()
        val my = (sh * margin).toInt()
        val xStart = mx; val xEnd = sw - mx
        val minRun = ((xEnd - xStart) * MIN_RUN_FRAC).toInt()
        val yRange = if (fromTop) my until sh / 2 else (sh - my - 1) downTo sh / 2

        for (y in yRange) {
            if (longestRun(y, xStart, xEnd, sw, mags, gy, thresh,
                    checkDir = { g -> if (fromTop) g > 0 else g < 0 }) >= minRun)
                return y
        }
        return null
    }

    /**
     * Same idea but scans columns for vertical-gradient (left / right) edges.
     * Qualifying pixel: Gx sign matches transition direction.
     */
    private fun findEdgeCol(
        gx: IntArray, mags: IntArray, thresh: Int,
        sw: Int, sh: Int, fromLeft: Boolean, margin: Float
    ): Int? {
        val mx = (sw * margin).toInt()
        val my = (sh * margin).toInt()
        val yStart = my; val yEnd = sh - my
        val minRun = ((yEnd - yStart) * MIN_RUN_FRAC).toInt()
        val xRange = if (fromLeft) mx until sw / 2 else (sw - mx - 1) downTo sw / 2

        for (x in xRange) {
            if (longestRunCol(x, yStart, yEnd, sw, mags, gx, thresh,
                    checkDir = { g -> if (fromLeft) g > 0 else g < 0 }) >= minRun)
                return x
        }
        return null
    }

    /** Longest run (with gap tolerance) of qualifying pixels along a row. */
    private fun longestRun(
        y: Int, xStart: Int, xEnd: Int, sw: Int,
        mags: IntArray, gradient: IntArray, thresh: Int,
        checkDir: (Int) -> Boolean
    ): Int {
        var run = 0; var gap = 0; var best = 0
        for (x in xStart until xEnd) {
            val i = y * sw + x
            if (mags[i] > thresh && checkDir(gradient[i])) {
                run++; gap = 0; best = maxOf(best, run)
            } else {
                if (++gap > MAX_GAP) run = 0
            }
        }
        return best
    }

    /** Longest run along a column. */
    private fun longestRunCol(
        x: Int, yStart: Int, yEnd: Int, sw: Int,
        mags: IntArray, gradient: IntArray, thresh: Int,
        checkDir: (Int) -> Boolean
    ): Int {
        var run = 0; var gap = 0; var best = 0
        for (y in yStart until yEnd) {
            val i = y * sw + x
            if (mags[i] > thresh && checkDir(gradient[i])) {
                run++; gap = 0; best = maxOf(best, run)
            } else {
                if (++gap > MAX_GAP) run = 0
            }
        }
        return best
    }

    // ── Band point collectors ────────────────────────────────────────────────

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

    // ── Outlier filtering (MAD) ──────────────────────────────────────────────

    private fun filterOutliers(pts: List<PointF>, byY: Boolean): List<PointF> {
        val vals = if (byY) pts.map { it.y } else pts.map { it.x }
        val sorted = vals.sorted()
        val median = sorted[sorted.size / 2]
        val mad = vals.map { abs(it - median) }.sorted()
            .let { it[it.size / 2] }.coerceAtLeast(1f)
        val threshold = 3f * mad
        return pts.filter { abs((if (byY) it.y else it.x) - median) <= threshold }
    }

    // ── Line fitting ─────────────────────────────────────────────────────────

    private data class HLine(val a: Double, val b: Double)   // y = a·x + b
    private data class VLine(val a: Double, val b: Double)   // x = a·y + b

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
