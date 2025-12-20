package com.homelibrary.server.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

@Service
@Slf4j
@RequiredArgsConstructor
public class OCRService {

    private final ImageProcessingService imageProcessingService;

    public String extractText(byte[] imageBytes, String language) throws IOException, TesseractException {
        log.info("=== OCR EXTRACTION START ===");
        log.info("Language parameter: '{}'", language);

        // Enhance image for better OCR results
        byte[] enhancedImageBytes = imageProcessingService.enhanceForOCR(imageBytes);
        log.info("Image enhanced for OCR");

        // Use ImageIO to read the image from bytes.
        // This leverages the installed JAI Image I/O plugins for support.
        try (ByteArrayInputStream bis = new ByteArrayInputStream(enhancedImageBytes)) {
            BufferedImage bufferedImage = ImageIO.read(bis);

            if (bufferedImage == null) {
                throw new IOException("Could not decode image. Unsupported format.");
            }

            Tesseract tesseract = getTesseractInstance();

            // Configure tessdata path (can be set via TESSDATA_PREFIX environment variable)
            String tessdataPath = System.getenv("TESSDATA_PREFIX");
            if (tessdataPath != null && !tessdataPath.isEmpty()) {
                // Ensure we have an absolute path
                File tessdataDir = new File(tessdataPath);
                String absolutePath = tessdataDir.getAbsolutePath();
                tesseract.setDatapath(absolutePath);
                log.info("Using tessdata path: {}", absolutePath);

                // List available traineddata files
                log.info("Available tessdata files:");
                File[] files = tessdataDir.listFiles((dir, name) -> name.endsWith(".traineddata"));
                if (files != null && files.length > 0) {
                    for (File f : files) {
                        log.info("  - {}", f.getName());
                    }
                } else {
                    log.warn("  WARNING: No .traineddata files found in {}", absolutePath);
                }
            } else {
                log.info("TESSDATA_PREFIX not set, using default Tesseract locations");
            }
            // If not set, Tesseract will look in default locations:
            // - Windows: C:\Program Files\Tesseract-OCR\tessdata
            // - Linux: /usr/share/tesseract-ocr/4.00/tessdata or /usr/share/tessdata
            // - Or in the project: ./tessdata

            // Set language (default to rus if not provided or empty)
            if (language == null || language.trim().isEmpty()) {
                language = "rus";
            }
            log.info("Setting Tesseract language to: '{}'", language);
            tesseract.setLanguage(language);

            // Configure Tesseract for better accuracy
            // PSM 3 = Fully automatic page segmentation (no OSD)
            tesseract.setPageSegMode(3);
            // OEM 1 = LSTM neural network mode (best accuracy for modern Tesseract)
            tesseract.setOcrEngineMode(1);

            log.info("Tesseract configured: PSM=3 (auto), OEM=1 (LSTM)");
            log.info("Starting OCR...");
            String result = tesseract.doOCR(bufferedImage);
            log.info("OCR completed successfully, extracted {} characters", result.length());

            // Log the extracted text (truncate if too long)
            if (result.length() <= 500) {
                log.info("OCR extracted text:\n{}", result);
            } else {
                log.info("OCR extracted text (first 500 chars):\n{}", result.substring(0, 500));
                log.info("... (truncated, total length: {} chars)", result.length());
            }

            log.info("=== OCR EXTRACTION END ===");
            return result;
        } catch (Exception e) {
            log.error("OCR FAILED: {}", e.getMessage(), e);
            throw e;
        }
    }

    // Overload for backward compatibility / tests
    public String extractText(byte[] imageBytes) throws IOException, TesseractException {
        return extractText(imageBytes, "rus");
    }

    protected Tesseract getTesseractInstance() {
        return new Tesseract();
    }
}
