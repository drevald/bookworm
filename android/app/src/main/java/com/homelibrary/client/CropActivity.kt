package com.homelibrary.client

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.homelibrary.client.databinding.ActivityCropBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Full-screen crop/fix activity:
 *  1. Loads the page image and auto-detects its corners with PageDetector.
 *  2. Shows a draggable 4-corner overlay (yellow dashed) pre-positioned at the
 *     detected corners so the user can fine-tune if needed.
 *  3. On "Crop & Fix":
 *     a. Applies a perspective warp (Matrix.setPolyToPoly) to straighten the page.
 *     b. Applies white-point balance (95th-percentile per channel → 255) so the
 *        paper appears pure white.
 *  4. Saves the result back over the original file and returns RESULT_OK.
 *  5. "Cancel" returns RESULT_CANCELED without touching the file.
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "crop_image_path"
        const val EXTRA_PAGE_ID    = "crop_page_id"
    }

    private lateinit var binding: ActivityCropBinding
    private var imagePath: String? = null
    private var sourceBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        if (imagePath == null) { finish(); return }

        sourceBitmap = loadBitmap(imagePath!!)
        if (sourceBitmap == null) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.cropView.setBitmap(sourceBitmap!!)
        binding.hintText.text = "Detecting page…"

        // Run corner detection on a background thread once the view is laid out
        // (imageRect inside CropView is only valid after layout).
        binding.cropView.doOnLayout { autoDetect() }

        binding.confirmButton.setOnClickListener { applyAndSave() }
        binding.cancelButton.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    // ── Auto-detection ───────────────────────────────────────────────────────

    private fun autoDetect() {
        val bm = sourceBitmap ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            val corners = PageDetector.detectCorners(bm)
            withContext(Dispatchers.Main) {
                if (corners != null) {
                    binding.cropView.setCornersFromBitmapSpace(corners)
                    binding.hintText.text = "Adjust corners if needed, then tap Crop & Fix"
                } else {
                    binding.hintText.text = "Drag corners to align with the page"
                }
            }
        }
    }

    // ── Crop + white balance ─────────────────────────────────────────────────

    private fun applyAndSave() {
        val path = imagePath ?: return
        val bm   = sourceBitmap ?: return

        // Snapshot corners on the main thread to avoid races with touch events
        val pts = binding.cropView.getCornersInBitmapSpace()

        binding.confirmButton.isEnabled = false
        binding.cancelButton.isEnabled  = false
        binding.hintText.text = "Processing…"

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val result = perspectiveWarpAndBalance(bm, pts)

                FileOutputStream(File(path)).use { out ->
                    result.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                result.recycle()

                withContext(Dispatchers.Main) {
                    setResult(Activity.RESULT_OK,
                        Intent().putExtra(EXTRA_PAGE_ID, intent.getLongExtra(EXTRA_PAGE_ID, -1L)))
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.confirmButton.isEnabled = true
                    binding.cancelButton.isEnabled  = true
                    binding.hintText.text = "Adjust corners if needed, then tap Crop & Fix"
                    Toast.makeText(this@CropActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun perspectiveWarpAndBalance(src: Bitmap, pts: Array<PointF>): Bitmap {
        val tl = pts[0]; val tr = pts[1]; val br = pts[2]; val bl = pts[3]
        val outW = maxOf(dist(tl, tr), dist(bl, br)).roundToInt().coerceIn(50, 4096)
        val outH = maxOf(dist(tl, bl), dist(tr, br)).roundToInt().coerceIn(50, 4096)

        val srcPts = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
        val dstPts = floatArrayOf(0f, 0f, outW.toFloat(), 0f,
                                  outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4))
            throw IllegalArgumentException("Corners are degenerate — cannot warp")

        // Perspective correction
        val warped = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(warped).drawBitmap(src, matrix, null)

        // White-point balance
        val balanced = applyWhiteBalance(warped)
        warped.recycle()
        return balanced
    }

    /**
     * Scales each RGB channel so that its 95th-percentile value maps to 255.
     * Assumes the page background is white paper → makes paper pure white.
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
        val wpR = findPercentile(rH, target).coerceAtLeast(1)
        val wpG = findPercentile(gH, target).coerceAtLeast(1)
        val wpB = findPercentile(bH, target).coerceAtLeast(1)

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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun loadBitmap(path: String): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sampleSize = 1
        var w = opts.outWidth; var h = opts.outHeight
        while (w > 2048 || h > 2048) { sampleSize *= 2; w /= 2; h /= 2 }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } catch (e: Exception) { null }

    private fun findPercentile(hist: IntArray, target: Int): Int {
        var sum = 0
        for (i in 0..255) { sum += hist[i]; if (sum >= target) return i }
        return 255
    }

    private fun dist(a: PointF, b: PointF): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
