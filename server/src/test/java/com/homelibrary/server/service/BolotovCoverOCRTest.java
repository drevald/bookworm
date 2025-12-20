package com.homelibrary.server.service;

import net.sourceforge.tess4j.TesseractException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;

@EnabledIfEnvironmentVariable(named = "TESSDATA_PREFIX", matches = ".*")
class BolotovCoverOCRTest {

    @Test
    void testBolotovCoverOCR() throws IOException, TesseractException {
        OCRService ocrService = new OCRService();
        ImageProcessingService imageProcessingService = new ImageProcessingService();
        imageProcessingService.init();
        BookParser bookParser = new BookParser();

        // Load the bolotov cover image
        ClassPathResource resource = new ClassPathResource("bolotov_cover.jpg");
        byte[] originalBytes = Files.readAllBytes(resource.getFile().toPath());

        // Crop it (simulating what happens during upload)
        byte[] croppedBytes = imageProcessingService.detectAndCrop(originalBytes);

        // Run OCR on the cropped image
        String coverText = ocrService.extractText(croppedBytes);

        System.out.println("=== Bolotov Cover OCR Text ===");
        System.out.println(coverText);
        System.out.println("===============================");

        // Try to parse it
        BookParser.ParsedBookData book = bookParser.parse(coverText, "", "");

        System.out.println("=== Parsed from Cover ===");
        System.out.println("Title: " + book.getTitle());
        System.out.println("Author: " + book.getAuthor());
        System.out.println("ISBN: " + book.getIsbn());
        System.out.println("Year: " + book.getPublicationYear());
        System.out.println("=========================");
    }
}
