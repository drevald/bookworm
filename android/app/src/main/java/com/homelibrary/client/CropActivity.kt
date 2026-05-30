package com.homelibrary.client

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
        const val EXTRA_PAGE_TYPE  = "crop_page_type"
    }

    private lateinit var binding: ActivityCropBinding
    private var imagePath: String? = null
    private var sourceBitmap: Bitmap? = null  // display-size, possibly rotated
    private var totalRotation: Int = 0        // accumulated CW rotation in degrees
    private var pageType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        pageType  = intent.getStringExtra(EXTRA_PAGE_TYPE)
        if (imagePath == null) { finish(); return }

        sourceBitmap = loadBitmap(imagePath!!)
        if (sourceBitmap == null) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.cropView.rectMode = (pageType != "COVER")
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
        binding.rotateButton.setOnClickListener { rotateCW() }
        binding.resetCornersButton.setOnClickListener { autoDetect() }

        // Show page type label if provided
        val pageTypeName = intent.getStringExtra(EXTRA_PAGE_TYPE)
        if (!pageTypeName.isNullOrEmpty()) {
            binding.pageTypeLabel.text = pageTypeName.lowercase().replace('_', ' ')
        }
    }

    // ── Rotate ───────────────────────────────────────────────────────────────

    private fun rotateCW() {
        val bm = sourceBitmap ?: return
        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, matrix, true)
        sourceBitmap = rotated
        totalRotation = (totalRotation + 90) % 360
        binding.cropView.rectMode = (pageType != "COVER")
        binding.cropView.setBitmap(rotated)
        autoDetect()
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
        binding.rotateButton.isEnabled  = false
        binding.resetCornersButton.isEnabled = false
        binding.hintText.text = "Processing…"

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // Load full-resolution image for the warp — the display bitmap (bm)
                // is downsampled to ≤2048px, which throws away the resolution we captured.
                val fullBm = BitmapFactory.decodeFile(path)
                    ?: throw IllegalStateException("Failed to load full-res image")

                // Apply accumulated rotation to the full-res bitmap.
                val fullRotated = if (totalRotation != 0) {
                    val m = Matrix().apply { postRotate(totalRotation.toFloat()) }
                    Bitmap.createBitmap(fullBm, 0, 0, fullBm.width, fullBm.height, m, true)
                        .also { if (it !== fullBm) fullBm.recycle() }
                } else fullBm

                // Scale corners from display-bitmap space → full-res space.
                val scaleX = fullRotated.width.toFloat() / bm.width.toFloat()
                val scaleY = fullRotated.height.toFloat() / bm.height.toFloat()
                val fullPts = Array(pts.size) { i -> PointF(pts[i].x * scaleX, pts[i].y * scaleY) }

                val result = perspectiveWarpAndBalance(fullRotated, fullPts)
                fullRotated.recycle()

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
                    binding.rotateButton.isEnabled  = true
                    binding.resetCornersButton.isEnabled = true
                    binding.hintText.text = "Adjust corners if needed, then tap Save"
                    Toast.makeText(this@CropActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun perspectiveWarpAndBalance(src: Bitmap, pts: Array<PointF>): Bitmap =
        if (pageType == "COVER") PageCropper.warpAndBalance(src, pts)
        else                     PageCropper.cropAndBalance(src, pts)

    // Loads a downsampled bitmap for display and corner detection only.
    // The actual warp is performed on the full-resolution file in applyAndSave().
    private fun loadBitmap(path: String): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sampleSize = 1
        var w = opts.outWidth; var h = opts.outHeight
        while (w > 2048 || h > 2048) { sampleSize *= 2; w /= 2; h /= 2 }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } catch (e: Exception) { null }
}
