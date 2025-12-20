package com.homelibrary.server.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
public class ImageProcessingService {

    private boolean openCVLoaded = false;

    @PostConstruct
    public void init() {
        try {
            OpenCV.loadLocally();
            openCVLoaded = true;
            log.info("OpenCV loaded successfully");
        } catch (Throwable e) {
            log.error("Failed to load OpenCV", e);
        }
    }

    /**
     * Resize image for web display (scale to max dimension while maintaining aspect ratio)
     */
    public byte[] resizeForDisplay(byte[] imageBytes, int maxDimension) {
        if (!openCVLoaded || imageBytes == null || imageBytes.length == 0) {
            return imageBytes;
        }

        try {
            Mat src = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
            if (src.empty()) {
                return imageBytes;
            }

            int width = src.width();
            int height = src.height();

            // Only resize if image is larger than max dimension
            if (width <= maxDimension && height <= maxDimension) {
                src.release();
                return imageBytes;
            }

            // Calculate new dimensions maintaining aspect ratio
            double scale;
            if (width > height) {
                scale = (double) maxDimension / width;
            } else {
                scale = (double) maxDimension / height;
            }

            int newWidth = (int) (width * scale);
            int newHeight = (int) (height * scale);

            Mat resized = new Mat();
            Imgproc.resize(src, resized, new Size(newWidth, newHeight), 0, 0, Imgproc.INTER_AREA);

            // Encode with compression
            MatOfInt params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85);
            MatOfByte mob = new MatOfByte();
            Imgcodecs.imencode(".jpg", resized, mob, params);
            byte[] result = mob.toArray();

            log.info("Resized image from {}x{} to {}x{}, size reduced from {} to {} bytes",
                width, height, newWidth, newHeight, imageBytes.length, result.length);

            // Cleanup
            src.release();
            resized.release();
            params.release();

            return result;
        } catch (Exception e) {
            log.error("Error resizing image for display", e);
            return imageBytes;
        }
    }

    /**
     * Enhance image for better OCR accuracy
     */
    public byte[] enhanceForOCR(byte[] imageBytes) {
        if (!openCVLoaded || imageBytes == null || imageBytes.length == 0) {
            return imageBytes;
        }

        try {
            Mat src = Imgcodecs.imdecode(new MatOfByte(imageBytes), Imgcodecs.IMREAD_COLOR);
            if (src.empty()) {
                return imageBytes;
            }

            // Convert to grayscale
            Mat gray = new Mat();
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

            // Upscale significantly for better OCR (3x for small images)
            if (gray.width() < 1500 || gray.height() < 1500) {
                Mat upscaled = new Mat();
                double scaleFactor = 3.0;
                Imgproc.resize(gray, upscaled, new Size(gray.width() * scaleFactor, gray.height() * scaleFactor),
                    0, 0, Imgproc.INTER_CUBIC);
                log.info("Upscaled image for OCR: {}x{} -> {}x{}",
                    gray.width(), gray.height(), upscaled.width(), upscaled.height());
                gray.release();
                gray = upscaled;
            }

            // Enhance contrast using histogram equalization
            Mat enhanced = new Mat();
            Imgproc.equalizeHist(gray, enhanced);

            // Encode back to PNG with high quality
            MatOfByte mob = new MatOfByte();
            Imgcodecs.imencode(".png", enhanced, mob);
            byte[] result = mob.toArray();

            log.info("Enhanced image for OCR: grayscale + 3x upscale + histogram equalization");

            // Cleanup
            src.release();
            gray.release();
            enhanced.release();

            return result;
        } catch (Exception e) {
            log.error("Error enhancing image for OCR", e);
            return imageBytes;
        }
    }

    public byte[] detectAndCrop(byte[] originalImage) {
        if (!openCVLoaded || originalImage == null || originalImage.length == 0) {
            return originalImage;
        }

        try {
            // Decode image
            Mat src = Imgcodecs.imdecode(new MatOfByte(originalImage), Imgcodecs.IMREAD_COLOR);
            if (src.empty()) {
                return originalImage;
            }

            // Preprocess
            Mat gray = new Mat();
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

            Mat edges = new Mat();
            Imgproc.Canny(gray, edges, 75, 200);

            // Morphological closing to close gaps in edges (e.g. from the yellow arrow)
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5));
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel);

            // Find contours
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            // Sort by area descending
            Collections.sort(contours, (c1, c2) -> Double.compare(Imgproc.contourArea(c2), Imgproc.contourArea(c1)));

            MatOfPoint2f docContour = null;
            Mat cropped = null;

            // Strategy 1: Find largest 4-sided polygon
            for (MatOfPoint c : contours) {
                MatOfPoint2f c2f = new MatOfPoint2f(c.toArray());
                double peri = Imgproc.arcLength(c2f, true);
                MatOfPoint2f approx = new MatOfPoint2f();
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true);

                if (approx.total() == 4 && Imgproc.contourArea(c) > 1000) {
                    docContour = approx;
                    break;
                }
            }

            if (docContour != null) {
                log.info("Document contour found (4-sided), cropping...");
                cropped = fourPointTransform(src, docContour);
            } else {
                // Strategy 2: Fallback to bounding box of largest reasonable contour
                // This helps when corners are obscured or edges are too rounded/noisy
                log.info("No 4-sided contour found, falling back to bounding box...");
                for (MatOfPoint c : contours) {
                    double area = Imgproc.contourArea(c);
                    if (area > 5000) { // arbitrary min area to avoid noise
                        Rect rect = Imgproc.boundingRect(c);
                        // Check if rect is somewhat centered or sizeable?
                        // For now just take the largest one.

                        // Ensure we don't go out of bounds (shouldn't happen with boundingRect but good
                        // to be safe)
                        if (rect.x >= 0 && rect.y >= 0 &&
                                rect.x + rect.width <= src.width() &&
                                rect.y + rect.height <= src.height()) {

                            cropped = new Mat(src, rect);
                            log.info("Cropped using bounding box: " + rect);
                            break;
                        }
                    }
                }
            }

            byte[] result = originalImage;

            if (cropped != null) {
                // Encode back to byte array
                MatOfByte mob = new MatOfByte();
                Imgcodecs.imencode(".jpg", cropped, mob);
                result = mob.toArray();
                cropped.release();
            } else {
                log.info("No suitable crop found, returning original.");
            }

            // Cleanup
            src.release();
            gray.release();
            edges.release();
            hierarchy.release();
            kernel.release();

            return result;

        } catch (Exception e) {
            log.error("Error during image processing", e);
        }

        return originalImage;
    }

    private Mat fourPointTransform(Mat src, MatOfPoint2f pts) {
        Point[] points = pts.toArray();
        Point[] ordered = orderPoints(points);

        Point tl = ordered[0];
        Point tr = ordered[1];
        Point br = ordered[2];
        Point bl = ordered[3];

        // Width
        double widthA = Math.sqrt(Math.pow(br.x - bl.x, 2) + Math.pow(br.y - bl.y, 2));
        double widthB = Math.sqrt(Math.pow(tr.x - tl.x, 2) + Math.pow(tr.y - tl.y, 2));
        int maxWidth = (int) Math.max(widthA, widthB);

        // Height
        double heightA = Math.sqrt(Math.pow(tr.x - br.x, 2) + Math.pow(tr.y - br.y, 2));
        double heightB = Math.sqrt(Math.pow(tl.x - bl.x, 2) + Math.pow(tl.y - bl.y, 2));
        int maxHeight = (int) Math.max(heightA, heightB);

        MatOfPoint2f dstPts = new MatOfPoint2f(
                new Point(0, 0),
                new Point(maxWidth - 1, 0),
                new Point(maxWidth - 1, maxHeight - 1),
                new Point(0, maxHeight - 1));

        MatOfPoint2f srcPts = new MatOfPoint2f(ordered);

        Mat M = Imgproc.getPerspectiveTransform(srcPts, dstPts);
        Mat dst = new Mat();
        Imgproc.warpPerspective(src, dst, M, new Size(maxWidth, maxHeight));

        return dst;
    }

    private Point[] orderPoints(Point[] pts) {
        Point[] ordered = new Point[4];

        // tl: min sum, br: max sum
        // tr: min diff, bl: max diff

        double[] sums = new double[4];
        double[] diffs = new double[4];

        for (int i = 0; i < 4; i++) {
            sums[i] = pts[i].x + pts[i].y;
            diffs[i] = pts[i].y - pts[i].x;
        }

        int tl = 0, br = 0, tr = 0, bl = 0;
        double minSum = Double.MAX_VALUE;
        double maxSum = Double.MIN_VALUE;
        double minDiff = Double.MAX_VALUE;
        double maxDiff = Double.MIN_VALUE;

        for (int i = 0; i < 4; i++) {
            if (sums[i] < minSum) {
                minSum = sums[i];
                tl = i;
            }
            if (sums[i] > maxSum) {
                maxSum = sums[i];
                br = i;
            }
            if (diffs[i] < minDiff) {
                minDiff = diffs[i];
                tr = i;
            }
            if (diffs[i] > maxDiff) {
                maxDiff = diffs[i];
                bl = i;
            }
        }

        ordered[0] = pts[tl];
        ordered[1] = pts[tr];
        ordered[2] = pts[br];
        ordered[3] = pts[bl];

        return ordered;
    }
}
