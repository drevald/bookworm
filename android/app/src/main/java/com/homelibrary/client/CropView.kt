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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Interactive view for selecting a quadrilateral crop region on an image.
 * Shows 4 draggable corner handles and 4 draggable edge midpoint handles,
 * all connected by a yellow dashed border.
 * The area outside the quad is dimmed to indicate what will be cropped away.
 *
 * Touch priority: corners are checked first (within touchRadius), then sides.
 * Dragging a side moves both adjacent corners by the same delta.
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

    // When true the selection is constrained to an axis-aligned rectangle.
    // Used for all page types except COVER (which needs perspective warp).
    var rectMode: Boolean = false

    // Active corner index (-1 = none)
    private var activeCorner = -1

    // Active side index (-1 = none). Sides: 0=top(0-1), 1=right(1-2), 2=bottom(2-3), 3=left(3-0)
    private var activeSide = -1
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Touch area radius for corners (large so finger doesn't block handle)
    private val touchRadius: Float = 72f
    // Touch area for sides: perpendicular distance to edge segment
    private val sideHitWidth: Float = 40f

    private val handleRadius = 22f
    private val handleInnerRadius = 18f
    private val midHandleRadius = 14f
    private val midHandleInnerRadius = 10f

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

    private val midHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        alpha = 200
        style = Paint.Style.FILL
    }

    private val dimPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // Rect-mode: solid cyan border (no dash — perspective cue removed)
    private val rectLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val rectHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
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

    // ── Rect-mode helpers ────────────────────────────────────────────────────

    /** Snap corners to the axis-aligned bounding rect of their current positions. */
    private fun snapToRect() {
        val l = corners.minOf { it.x }; val r = corners.maxOf { it.x }
        val t = corners.minOf { it.y }; val b = corners.maxOf { it.y }
        corners[0].set(l, t); corners[1].set(r, t)
        corners[2].set(r, b); corners[3].set(l, b)
    }

    /**
     * In rect mode, move only the edge(s) that the dragged corner controls.
     * Corner 0=TL → left+top, 1=TR → right+top, 2=BR → right+bottom, 3=BL → left+bottom.
     */
    private fun moveRectCorner(idx: Int, x: Float, y: Float) {
        val cx = x.coerceIn(imageRect.left, imageRect.right)
        val cy = y.coerceIn(imageRect.top, imageRect.bottom)
        val l = corners[0].x; val r = corners[2].x
        val t = corners[0].y; val b = corners[2].y
        when (idx) {
            0 -> { corners[0].set(cx, cy); corners[1].set(r,  cy); corners[3].set(cx, b) }
            1 -> { corners[0].set(l,  cy); corners[1].set(cx, cy); corners[2].set(cx, b) }
            2 -> { corners[1].set(r,  t ); corners[2].set(cx, cy); corners[3].set(l,  cy) }
            3 -> { corners[0].set(cx, t ); corners[2].set(r,  cy); corners[3].set(cx, cy) }
        }
    }

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
        if (rectMode) snapToRect()
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

    /** Returns the two corner indices for a given side index. */
    private fun sideCorners(side: Int): Pair<Int, Int> = when (side) {
        0 -> Pair(0, 1)  // top
        1 -> Pair(1, 2)  // right
        2 -> Pair(2, 3)  // bottom
        else -> Pair(3, 0) // left
    }

    /** Midpoint of a side. */
    private fun sideMidpoint(side: Int): PointF {
        val (a, b) = sideCorners(side)
        return PointF((corners[a].x + corners[b].x) / 2f, (corners[a].y + corners[b].y) / 2f)
    }

    /**
     * Perpendicular distance from point (px, py) to the segment (ax,ay)→(bx,by).
     * Returns Float.MAX_VALUE if the perpendicular foot lies outside the segment.
     */
    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0f) return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay))
        val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        if (t < 0f || t > 1f) return Float.MAX_VALUE
        val projX = ax + t * dx; val projY = ay + t * dy
        return sqrt((px - projX) * (px - projX) + (py - projY) * (py - projY))
    }

    /** Returns the index of the nearest side within sideHitWidth, or -1. */
    private fun nearestSide(x: Float, y: Float): Int {
        var best = -1
        var bestDist = sideHitWidth
        for (side in 0..3) {
            val (a, b) = sideCorners(side)
            val d = distToSegment(x, y, corners[a].x, corners[a].y, corners[b].x, corners[b].y)
            if (d < bestDist) {
                bestDist = d
                best = side
            }
        }
        return best
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = bitmap ?: return

        // Draw the image (letterboxed inside imageRect)
        canvas.drawBitmap(bm, null, imageRect, null)

        // Build selection path (quad or rect)
        val selPath = Path().apply {
            moveTo(corners[0].x, corners[0].y)
            lineTo(corners[1].x, corners[1].y)
            lineTo(corners[2].x, corners[2].y)
            lineTo(corners[3].x, corners[3].y)
            close()
        }

        // Dim everything outside the selection
        canvas.save()
        canvas.clipOutPath(selPath)
        canvas.drawRect(imageRect, dimPaint)
        canvas.restore()

        if (rectMode) {
            // Solid cyan rectangle — no perspective cue
            canvas.drawPath(selPath, rectLinePaint)
            // Corner handles only (cyan)
            for (pt in corners) {
                canvas.drawCircle(pt.x, pt.y, handleRadius, handleStrokePaint)
                canvas.drawCircle(pt.x, pt.y, handleInnerRadius, rectHandleFillPaint)
            }
        } else {
            // Dashed yellow quad
            canvas.drawPath(selPath, linePaint)
            // Edge midpoint handles
            for (side in 0..3) {
                val mid = sideMidpoint(side)
                canvas.drawCircle(mid.x, mid.y, midHandleRadius, handleStrokePaint)
                canvas.drawCircle(mid.x, mid.y, midHandleInnerRadius, midHandleFillPaint)
            }
            // Corner handles (yellow)
            for (pt in corners) {
                canvas.drawCircle(pt.x, pt.y, handleRadius, handleStrokePaint)
                canvas.drawCircle(pt.x, pt.y, handleInnerRadius, handleFillPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Corners take priority over sides
                val corner = nearestCorner(event.x, event.y)
                if (corner >= 0) {
                    activeCorner = corner
                    activeSide = -1
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                val side = nearestSide(event.x, event.y)
                if (side >= 0) {
                    activeSide = side
                    activeCorner = -1
                    lastTouchX = event.x
                    lastTouchY = event.y
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner >= 0) {
                    if (rectMode) {
                        moveRectCorner(activeCorner, event.x, event.y)
                    } else {
                        corners[activeCorner].set(
                            event.x.coerceIn(imageRect.left, imageRect.right),
                            event.y.coerceIn(imageRect.top, imageRect.bottom)
                        )
                    }
                    invalidate()
                    return true
                }
                if (activeSide >= 0) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    if (rectMode) {
                        // Move only the single edge that corresponds to this side
                        when (activeSide) {
                            0 -> { // top
                                val ny = (corners[0].y + dy).coerceIn(imageRect.top, imageRect.bottom)
                                corners[0].y = ny; corners[1].y = ny
                            }
                            1 -> { // right
                                val nx = (corners[1].x + dx).coerceIn(imageRect.left, imageRect.right)
                                corners[1].x = nx; corners[2].x = nx
                            }
                            2 -> { // bottom
                                val ny = (corners[2].y + dy).coerceIn(imageRect.top, imageRect.bottom)
                                corners[2].y = ny; corners[3].y = ny
                            }
                            3 -> { // left
                                val nx = (corners[0].x + dx).coerceIn(imageRect.left, imageRect.right)
                                corners[0].x = nx; corners[3].x = nx
                            }
                        }
                    } else {
                        val (a, b) = sideCorners(activeSide)
                        corners[a].set(
                            (corners[a].x + dx).coerceIn(imageRect.left, imageRect.right),
                            (corners[a].y + dy).coerceIn(imageRect.top, imageRect.bottom)
                        )
                        corners[b].set(
                            (corners[b].x + dx).coerceIn(imageRect.left, imageRect.right),
                            (corners[b].y + dy).coerceIn(imageRect.top, imageRect.bottom)
                        )
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                activeCorner = -1
                activeSide = -1
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
