package com.homelibrary.client

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

/**
 * Interactive view for selecting a quadrilateral crop region on an image.
 * Shows 4 draggable corner handles connected by a yellow dashed border.
 * The area outside the quad is dimmed to indicate what will be cropped away.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null

    // Where the bitmap is drawn inside the view (letterboxed/pillarboxed)
    private val imageRect = RectF()

    // 4 corners in VIEW coordinates: [0]=TL, [1]=TR, [2]=BR, [3]=BL
    private val corners = Array(4) { PointF() }
    private var activeCorner = -1

    // Touch area radius in pixels (large so finger doesn't block handle)
    private val touchRadius: Float = 72f

    private val handleRadius = 22f
    private val handleInnerRadius = 18f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 4f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(24f, 12f), 0f)
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val dimPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }

    fun setBitmap(bm: Bitmap) {
        bitmap = bm
        if (width > 0 && height > 0) {
            updateImageRect()
            initDefaultCorners()
        }
        invalidate()
    }

    fun getBitmap(): Bitmap? = bitmap

    /**
     * Set corners from bitmap pixel coordinates (inverse of getCornersInBitmapSpace).
     * Must be called after the view is laid out so imageRect is valid.
     */
    fun setCornersFromBitmapSpace(bitmapCorners: Array<PointF>) {
        val bm = bitmap ?: return
        if (imageRect.isEmpty) return
        val scaleX = imageRect.width()  / bm.width.toFloat()
        val scaleY = imageRect.height() / bm.height.toFloat()
        for (i in 0 until minOf(corners.size, bitmapCorners.size)) {
            corners[i].set(
                (bitmapCorners[i].x * scaleX + imageRect.left).coerceIn(imageRect.left, imageRect.right),
                (bitmapCorners[i].y * scaleY + imageRect.top ).coerceIn(imageRect.top,  imageRect.bottom)
            )
        }
        invalidate()
    }

    /** Returns 4 corners in BITMAP pixel coordinates: [TL, TR, BR, BL] */
    fun getCornersInBitmapSpace(): Array<PointF> {
        val bm = bitmap ?: return corners.copyOf()
        val scaleX = bm.width.toFloat() / imageRect.width()
        val scaleY = bm.height.toFloat() / imageRect.height()
        return Array(4) { i ->
            PointF(
                (corners[i].x - imageRect.left) * scaleX,
                (corners[i].y - imageRect.top) * scaleY
            )
        }
    }

    private fun updateImageRect() {
        val bm = bitmap ?: return
        val bitmapAspect = bm.width.toFloat() / bm.height.toFloat()
        val viewAspect = width.toFloat() / height.toFloat()

        if (bitmapAspect > viewAspect) {
            val drawnHeight = width / bitmapAspect
            imageRect.set(0f, (height - drawnHeight) / 2f, width.toFloat(), (height + drawnHeight) / 2f)
        } else {
            val drawnWidth = height * bitmapAspect
            imageRect.set((width - drawnWidth) / 2f, 0f, (width + drawnWidth) / 2f, height.toFloat())
        }
    }

    private fun initDefaultCorners() {
        val insetX = imageRect.width() * 0.03f
        val insetY = imageRect.height() * 0.03f
        corners[0].set(imageRect.left + insetX, imageRect.top + insetY)      // TL
        corners[1].set(imageRect.right - insetX, imageRect.top + insetY)     // TR
        corners[2].set(imageRect.right - insetX, imageRect.bottom - insetY)  // BR
        corners[3].set(imageRect.left + insetX, imageRect.bottom - insetY)   // BL
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageRect()
        initDefaultCorners()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = bitmap ?: return

        // Draw the image (letterboxed inside imageRect)
        canvas.drawBitmap(bm, null, imageRect, null)

        // Build quad path
        val quadPath = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            lineTo(corners[1].x, corners[1].y)
            lineTo(corners[2].x, corners[2].y)
            lineTo(corners[3].x, corners[3].y)
            close()
        }

        // Dim everything outside the selected quad
        canvas.save()
        canvas.clipOutPath(quadPath)
        canvas.drawRect(imageRect, dimPaint)
        canvas.restore()

        // Draw dashed yellow outline of the quad
        canvas.drawPath(quadPath, linePaint)

        // Draw corner handles (black ring + yellow fill)
        for (pt in corners) {
            canvas.drawCircle(pt.x, pt.y, handleRadius, handleStrokePaint)
            canvas.drawCircle(pt.x, pt.y, handleInnerRadius, handleFillPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeCorner = nearestCorner(event.x, event.y)
                if (activeCorner >= 0) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner >= 0) {
                    corners[activeCorner].set(
                        event.x.coerceIn(imageRect.left, imageRect.right),
                        event.y.coerceIn(imageRect.top, imageRect.bottom)
                    )
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                activeCorner = -1
            }
        }
        return super.onTouchEvent(event)
    }

    private fun nearestCorner(x: Float, y: Float): Int {
        var best = -1
        var bestDist = touchRadius
        for (i in corners.indices) {
            val dx = corners[i].x - x
            val dy = corners[i].y - y
            val d = sqrt(dx * dx + dy * dy)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }
}
