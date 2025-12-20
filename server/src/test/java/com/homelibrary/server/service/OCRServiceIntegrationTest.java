package com.homelibrary.server.service;

import net.sourceforge.tess4j.TesseractException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for OCRService using real image files.
 * This test requires Tesseract binaries and trained data files to be installed.
 * 
 * To run this test:
 * 1. Download tessdata files using: .\download-tessdata.bat
 * 2. Set TESSDATA_PREFIX environment variable
 * 3. Run: gradle test
 */
@EnabledIfEnvironmentVariable(named = "TESSDATA_PREFIX", matches = ".*")
class OCRServiceIntegrationTest {

    private final OCRService ocrService = new OCRService();

    @Test
    void extractText_RussianBookPage_ExtractsCorrectInformation() throws IOException, TesseractException {
        // Arrange
        ClassPathResource resource = new ClassPathResource("page.jpg");
        byte[] imageBytes = Files.readAllBytes(resource.getFile().toPath());

        // Act
        String extractedText = ocrService.extractText(imageBytes);

        // Assert
        assertNotNull(extractedText, "Extracted text should not be null");
        assertFalse(extractedText.trim().isEmpty(), "Extracted text should not be empty");

        // Verify the extracted text contains expected Russian book information
        // Note: OCR might not be 100% accurate, so we check for key parts
        String normalizedText = extractedText.toLowerCase();
        
        // Book title (in Russian): "Послания из вымышленного царства"
        // We'll check for key words that should appear
        assertTrue(
            normalizedText.contains("послания") || normalizedText.contains("вымышленного") || normalizedText.contains("царства"),
            "Should contain parts of the book title. Extracted: " + extractedText
        );

        // Publisher: "СПб.: Азбука-классика"
        assertTrue(
            normalizedText.contains("азбука") || normalizedText.contains("спб"),
            "Should contain publisher information. Extracted: " + extractedText
        );

        // Publication year: 2004
        assertTrue(
            extractedText.contains("2004"),
            "Should contain publication year 2004. Extracted: " + extractedText
        );

        // ISBN: 5-352-01102-Х
        assertTrue(
            extractedText.contains("5-352-01102") || extractedText.contains("5352"),
            "Should contain ISBN number. Extracted: " + extractedText
        );

        // Print the extracted text for manual verification
        System.out.println("=== Extracted Text ===");
        System.out.println(extractedText);
        System.out.println("======================");
    }

    @Test
    void extractText_RussianBookPage_PerformanceTest() throws IOException, TesseractException {
        // Arrange
        ClassPathResource resource = new ClassPathResource("page.jpg");
        byte[] imageBytes = Files.readAllBytes(resource.getFile().toPath());

        // Act
        long startTime = System.currentTimeMillis();
        String extractedText = ocrService.extractText(imageBytes);
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Assert
        assertNotNull(extractedText);
        
        // OCR should complete in reasonable time (adjust threshold as needed)
        assertTrue(duration < 10000, 
            "OCR should complete within 10 seconds. Took: " + duration + "ms");

        System.out.println("OCR processing time: " + duration + "ms");
    }
}
