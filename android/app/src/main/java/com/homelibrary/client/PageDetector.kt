package com.homelibrary.client

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.abs

/**
 * Detects the corners of a book page (bright rectangular region) in a photograph.
 *
 * Algorithm:
 *  1. Downsample to ≤400 px for speed.
 *  2. Convert to grayscale and compute Otsu threshold.
 *  3. For each column/row, find the outermost "paper" pixel (gray > threshold).
 *  4. Fit lines through the top/bottom/left/right boundary points (least squares).
 *  5. Intersect the 4 lines → 4 corners.
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

        // Try detection at multiple thresholds: Otsu, then looser values
        val otsu = otsuThreshold(gray)
        val candidates = listOf(otsu, (otsu * 0.7).toInt().coerceIn(10, 240),
                                (otsu * 1.3).toInt().coerceIn(10, 240))

        for (threshold in candidates) {
            val result = tryDetect(gray, sw, sh, threshold, scale)
            if (result != null) return result
        }
        return null
    }

    private fun tryDetect(gray: IntArray, sw: Int, sh: Int, threshold: Int, scale: Float): Array<PointF>? {
        val step = maxOf(1, minOf(sw, sh) / 80)
        val topPts  = mutableListOf<PointF>()
        val botPts  = mutableListOf<PointF>()
        val leftPts = mutableListOf<PointF>()
        val rightPts = mutableListOf<PointF>()

        // Trim 3% from edges to ignore vignetting / camera borders
        val marginX = (sw * 0.03f).toInt()
        val marginY = (sh * 0.03f).toInt()

        for (x in marginX until sw - marginX step step) {
            var topY = -1; var botY = -1
            for (y in marginY until sh - marginY) {
                if (gray[y * sw + x] > threshold) { if (topY < 0) topY = y; botY = y }
            }
            if (topY >= 0) topPts.add(PointF(x.toFloat(), topY.toFloat()))
            if (botY >= 0) botPts.add(PointF(x.toFloat(), botY.toFloat()))
        }
        for (y in marginY until sh - marginY step step) {
            var leftX = -1; var rightX = -1
            for (x in marginX until sw - marginX) {
                if (gray[y * sw + x] > threshold) { if (leftX < 0) leftX = x; rightX = x }
            }
            if (leftX  >= 0) leftPts.add(PointF(leftX.toFloat(), y.toFloat()))
            if (rightX >= 0) rightPts.add(PointF(rightX.toFloat(), y.toFloat()))
        }

        if (topPts.size < 3 || botPts.size < 3 ||
            leftPts.size < 3 || rightPts.size < 3) return null

        val topLine   = fitH(topPts)   ?: return null
        val botLine   = fitH(botPts)   ?: return null
        val leftLine  = fitV(leftPts)  ?: return null
        val rightLine = fitV(rightPts) ?: return null

        val tl = intersect(topLine, leftLine)  ?: return null
        val tr = intersect(topLine, rightLine) ?: return null
        val br = intersect(botLine, rightLine) ?: return null
        val bl = intersect(botLine, leftLine)  ?: return null

        // Sanity: quad must cover at least 10% of the image and corners must be in order
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

    // ── Otsu threshold ──────────────────────────────────────────────────────

    private fun otsuThreshold(gray: IntArray): Int {
        val hist = IntArray(256)
        for (v in gray) hist[v]++
        val total = gray.size
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var sumB = 0L; var wB = 0; var maxVar = 0.0; var best = 128
        for (t in 0..255) {
            wB += hist[t]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val v  = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > maxVar) { maxVar = v; best = t }
        }
        return best
    }

    // ── Line fitting ─────────────────────────────────────────────────────────

    // Horizontal-ish edge: y = a*x + b
    private data class HLine(val a: Double, val b: Double)
    // Vertical-ish edge:   x = a*y + b
    private data class VLine(val a: Double, val b: Double)

    private fun fitH(pts: List<PointF>): HLine? {
        val n = pts.size.toDouble()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (p in pts) { sx += p.x; sy += p.y; sxx += p.x * p.x; sxy += p.x * p.y }
        val d = n * sxx - sx * sx
        if (abs(d) < 1e-6) return HLine(0.0, sy / n)
        val a = (n * sxy - sx * sy) / d
        return HLine(a, (sy - a * sx) / n)
    }

    private fun fitV(pts: List<PointF>): VLine? {
        val n = pts.size.toDouble()
        var sx = 0.0; var sy = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pts) { sx += p.x; sy += p.y; syy += p.y * p.y; sxy += p.x * p.y }
        val d = n * syy - sy * sy
        if (abs(d) < 1e-6) return VLine(0.0, sx / n)
        val a = (n * sxy - sy * sx) / d
        return VLine(a, (sx - a * sy) / n)
    }

    // Intersect y = a1*x + b1  with  x = a2*y + b2
    private fun intersect(h: HLine, v: VLine): PointF? {
        val d = 1.0 - h.a * v.a
        if (abs(d) < 1e-9) return null
        val x = (v.a * h.b + v.b) / d
        val y = h.a * x + h.b
        return PointF(x.toFloat(), y.toFloat())
    }

    // Shoelace area of a quadrilateral
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
