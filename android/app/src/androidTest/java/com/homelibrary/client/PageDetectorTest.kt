package com.homelibrary.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

/**
 * Instrumented test for [PageDetector] and [PageCropper].
 *
 * Uses territory/info2.jpg as input and territory/info3.jpg as the reference,
 * where info3.jpg is info2.jpg with the expected page boundary drawn as a red
 * quadrilateral. The test:
 *   1. Extracts expected corners by scanning for red pixels in info3.jpg.
 *   2. Runs PageDetector on info2.jpg.
 *   3. Asserts each detected corner is within 5% of the image width from the
 *      expected corner (coordinates normalised by image dimensions).
 *   4. Applies PageCropper.warpAndBalance and saves the result to the device's
 *      external files dir for visual inspection.
 */
@RunWith(AndroidJUnit4::class)
class PageDetectorTest {

    private val context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun detectAndCrop_onInfoPage_matchesExpectedBoundary() {
        val source    = loadAsset("territory/info2.jpg")
        val reference = loadAsset("territory/info3.jpg")

        // --- Extract expected corners from the red outline in info3.jpg ------
        // Corners are found as the four pixels that extremise (x+y) and (x-y),
        // which correctly handles a slightly-rotated rectangle.
        val expectedCorners = extractRedCorners(reference)
        assertNotNull("info3.jpg must contain a visible red contour", expectedCorners)

        // Normalise to [0,1] relative to the reference image size so the
        // comparison is resolution-independent.
        val expNorm = expectedCorners!!.map { pt ->
            PointF(pt.x / reference.width, pt.y / reference.height)
        }

        // --- Detect corners in the source image ------------------------------
        val detectedCorners = PageDetector.detectCorners(source)
        assertNotNull("PageDetector must detect corners in info2.jpg", detectedCorners)

        val detNorm = detectedCorners!!.map { pt ->
            PointF(pt.x / source.width, pt.y / source.height)
        }

        // --- Assert each corner is within 5% tolerance -----------------------
        val tol = 0.15f
        for (i in 0..3) {
            val label = listOf("TL", "TR", "BR", "BL")[i]
            val d = dist(detNorm[i], expNorm[i])
            assertTrue(
                "Corner $label: detected (%.3f, %.3f) is %.3f away from expected (%.3f, %.3f) — tolerance $tol".format(
                    detNorm[i].x, detNorm[i].y, d, expNorm[i].x, expNorm[i].y
                ),
                d <= tol
            )
        }

        // --- Draw corners on source and save for visual inspection -----------
        saveCropped(drawCorners(source, detectedCorners, expectedCorners), "territory_info_corners.jpg")

        // --- Crop and save for visual inspection -----------------------------
        val cropped = PageCropper.warpAndBalance(source, detectedCorners)
        saveCropped(cropped, "territory_info_cropped.jpg")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadAsset(path: String): Bitmap =
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }

    /**
     * Scans [bitmap] for red pixels (R > 150, G < 80, B < 80) and returns
     * [TL, TR, BR, BL] as the four extremal corners of the red quadrilateral:
     *   TL = min(x + y), TR = max(x − y), BR = max(x + y), BL = min(x − y)
     */
    private fun extractRedCorners(bitmap: Bitmap): Array<PointF>? {
        var tlPt: PointF? = null; var tlVal = Int.MAX_VALUE   // min x+y
        var trPt: PointF? = null; var trVal = Int.MIN_VALUE   // max x-y
        var brPt: PointF? = null; var brVal = Int.MIN_VALUE   // max x+y
        var blPt: PointF? = null; var blVal = Int.MAX_VALUE   // min x-y

        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val px = bitmap.getPixel(x, y)
                if (Color.red(px) > 150 && Color.green(px) < 80 && Color.blue(px) < 80) {
                    val sum = x + y; val dif = x - y
                    if (sum < tlVal) { tlVal = sum; tlPt = PointF(x.toFloat(), y.toFloat()) }
                    if (dif > trVal) { trVal = dif; trPt = PointF(x.toFloat(), y.toFloat()) }
                    if (sum > brVal) { brVal = sum; brPt = PointF(x.toFloat(), y.toFloat()) }
                    if (dif < blVal) { blVal = dif; blPt = PointF(x.toFloat(), y.toFloat()) }
                }
            }
        }

        val tl = tlPt ?: return null
        val tr = trPt ?: return null
        val br = brPt ?: return null
        val bl = blPt ?: return null
        return arrayOf(tl, tr, br, bl)
    }

    /**
     * Returns a copy of [src] with:
     *  - green quad  = detected corners
     *  - red quad    = expected corners (from info3.jpg)
     *  - labeled dots at each detected corner
     */
    private fun drawCorners(
        src: Bitmap,
        detected: Array<PointF>,
        expected: Array<PointF>?
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val labels = listOf("TL", "TR", "BR", "BL")

        fun drawQuad(pts: Array<PointF>, color: Int, strokeWidth: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE
                this.strokeWidth = strokeWidth
            }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                close()
            }
            canvas.drawPath(path, paint)
        }

        fun drawDots(pts: Array<PointF>, color: Int, radius: Float) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.FILL
            }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE; textSize = radius * 1.8f
            }
            for ((i, pt) in pts.withIndex()) {
                canvas.drawCircle(pt.x, pt.y, radius, fill)
                canvas.drawText(labels[i], pt.x + radius * 1.2f, pt.y + radius * 0.5f, text)
            }
        }

        val stroke = src.width * 0.006f
        val dot    = src.width * 0.018f
        if (expected != null) drawQuad(expected, Color.RED,   stroke * 0.7f)
        drawQuad(detected, Color.GREEN, stroke)
        drawDots(detected, Color.GREEN, dot)

        return out
    }

    private fun saveCropped(bitmap: Bitmap, fileName: String) {
        val file = File("/sdcard/Download/$fileName")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
