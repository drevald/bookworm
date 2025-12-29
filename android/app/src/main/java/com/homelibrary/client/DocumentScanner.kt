package com.homelibrary.client

import android.graphics.Bitmap
import android.graphics.Point
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

/**
 * Document scanner utility using OpenCV for edge detection and perspective correction.
 * Detects page boundaries and transforms them to rectangular shape.
 */
object DocumentScanner {
    private const val TAG = "DocumentScanner"

    /**
     * Result of document detection
     */
    data class DetectionResult(
        val corners: List<Point>?,
        val croppedBitmap: Bitmap?
    )

    /**
     * Detects document boundaries and applies perspective correction.
     * @param bitmap Input image
     * @return DetectionResult with detected corners and cropped/transformed bitmap
     */
    fun detectAndCrop(bitmap: Bitmap): DetectionResult {
        try {
            // Convert Bitmap to Mat
            val original = Mat()
            Utils.bitmapToMat(bitmap, original)

            // Convert to grayscale
            val gray = Mat()
            Imgproc.cvtColor(original, gray, Imgproc.COLOR_BGR2GRAY)

            // Apply Gaussian blur to reduce noise
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            // Edge detection using Canny
            val edges = Mat()
            Imgproc.Canny(gray, edges, 50.0, 150.0)

            // Find contours
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            // Find the largest 4-point contour (likely the document)
            val docCorners = findLargestRectangle(contours, original.size())

            if (docCorners != null) {
                Log.d(TAG, "Document detected with corners: ${docCorners.toList()}")

                // Apply perspective transform
                val transformed = applyPerspectiveTransform(original, docCorners)

                // Convert back to Bitmap
                val resultBitmap = Bitmap.createBitmap(
                    transformed.cols(),
                    transformed.rows(),
                    Bitmap.Config.ARGB_8888
                )
                Utils.matToBitmap(transformed, resultBitmap)

                // Convert corners to Android Points for UI overlay
                val corners = docCorners.toArray().map {
                    Point(it.x.toInt(), it.y.toInt())
                }

                // Clean up
                original.release()
                gray.release()
                edges.release()
                hierarchy.release()
                transformed.release()
                contours.forEach { it.release() }

                return DetectionResult(corners, resultBitmap)
            } else {
                Log.w(TAG, "No document detected, returning original")

                // Clean up
                original.release()
                gray.release()
                edges.release()
                hierarchy.release()
                contours.forEach { it.release() }

                return DetectionResult(null, bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in document detection", e)
            return DetectionResult(null, bitmap)
        }
    }

    /**
     * Finds the largest 4-point contour that approximates a rectangle
     */
    private fun findLargestRectangle(
        contours: List<MatOfPoint>,
        imageSize: Size
    ): MatOfPoint2f? {
        var maxArea = 0.0
        var largestContour: MatOfPoint2f? = null

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()

            // Approximate contour to polygon
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

            // Check if it's a quadrilateral (4 points)
            if (approx.rows() == 4) {
                val area = Imgproc.contourArea(approx)

                // Only consider contours that are at least 20% of image size
                val minArea = imageSize.width * imageSize.height * 0.2

                if (area > maxArea && area > minArea) {
                    maxArea = area
                    largestContour?.release()
                    largestContour = approx
                } else {
                    approx.release()
                }
            } else {
                approx.release()
            }

            contour2f.release()
        }

        return largestContour
    }

    /**
     * Applies perspective transformation to warp the document to rectangular shape
     */
    private fun applyPerspectiveTransform(image: Mat, corners: MatOfPoint2f): Mat {
        // Order corners: top-left, top-right, bottom-right, bottom-left
        val orderedCorners = orderCorners(corners.toArray())

        // Calculate output dimensions
        val (width, height) = calculateOutputDimensions(orderedCorners)

        // Define destination points (rectangular)
        val dst = MatOfPoint2f(
            org.opencv.core.Point(0.0, 0.0),
            org.opencv.core.Point(width - 1, 0.0),
            org.opencv.core.Point(width - 1, height - 1),
            org.opencv.core.Point(0.0, height - 1)
        )

        // Get perspective transform matrix
        val srcPoints = MatOfPoint2f(*orderedCorners)
        val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dst)

        // Apply transformation
        val warped = Mat()
        Imgproc.warpPerspective(
            image,
            warped,
            transformMatrix,
            Size(width, height)
        )

        // Clean up
        dst.release()
        srcPoints.release()
        transformMatrix.release()

        return warped
    }

    /**
     * Orders corner points as: top-left, top-right, bottom-right, bottom-left
     */
    private fun orderCorners(points: Array<org.opencv.core.Point>): Array<org.opencv.core.Point> {
        // Sort by y-coordinate to separate top and bottom
        val sorted = points.sortedBy { it.y }

        // Top two points
        val top = sorted.take(2).sortedBy { it.x }

        // Bottom two points
        val bottom = sorted.drop(2).sortedBy { it.x }

        return arrayOf(
            top[0],    // top-left
            top[1],    // top-right
            bottom[1], // bottom-right
            bottom[0]  // bottom-left
        )
    }

    /**
     * Calculates output dimensions based on corner distances
     */
    private fun calculateOutputDimensions(corners: Array<org.opencv.core.Point>): Pair<Double, Double> {
        val (tl, tr, br, bl) = corners

        // Calculate width as max of top and bottom edge lengths
        val widthTop = distance(tl, tr)
        val widthBottom = distance(bl, br)
        val width = maxOf(widthTop, widthBottom)

        // Calculate height as max of left and right edge lengths
        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)
        val height = maxOf(heightLeft, heightRight)

        return Pair(width, height)
    }

    /**
     * Calculates Euclidean distance between two points
     */
    private fun distance(p1: org.opencv.core.Point, p2: org.opencv.core.Point): Double {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Initialize OpenCV (call this once in Application onCreate)
     */
    fun init(): Boolean {
        return try {
            System.loadLibrary("opencv_java4")
            Log.d(TAG, "OpenCV initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OpenCV", e)
            false
        }
    }
}
