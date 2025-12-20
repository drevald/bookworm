package com.homelibrary.server.service;

import com.homelibrary.server.service.BookParser.ParsedBookData;
import net.sourceforge.tess4j.TesseractException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for OCR + BookParser pipeline.
 * This test verifies that OCR correctly extracts text from a book info page
 * and BookParser correctly extracts title, author, ISBN, and year.
 *
 * Expected values from page.jpg:
 * - Title: "Послания из вымышленного царства"
 * - Author: "Н. Горелов" or "Н. Горелова"
 * - ISBN: 5-352-01102-X
 * - Year: 2004
 * - Publisher: "Азбука-классика"
 * - City: "СПб" or "СПб."
 *
 * To run this test:
 * 1. Download tessdata files using: .\download-tessdata.bat
 * 2. Set TESSDATA_PREFIX environment variable
 * 3. Run: gradle test
 */
@EnabledIfEnvironmentVariable(named = "TESSDATA_PREFIX", matches = ".*")
class OCRBookParserIntegrationTest {

    private final OCRService ocrService = new OCRService();
    private final BookParser bookParser = new BookParser();

    @Test
    void extractAndParseBook_RussianBookInfoPage_ExtractsCorrectMetadata() throws IOException, TesseractException {
        // Arrange
        ClassPathResource resource = new ClassPathResource("page.jpg");
        byte[] imageBytes = Files.readAllBytes(resource.getFile().toPath());

        // Act - Extract text using OCR
        String extractedText = ocrService.extractText(imageBytes);

        // Print extracted text for debugging
        System.out.println("=== Extracted Text ===");
        System.out.println(extractedText);
        System.out.println("======================");

        // Parse the info page text to extract book metadata
        ParsedBookData book = bookParser.parse("", "", extractedText);

        // Assert - Verify all required fields are extracted correctly
        assertNotNull(book, "Book object should not be null");

        // Verify Title - "Послания из вымышленного царства"
        assertNotNull(book.getTitle(), "Title should not be null");
        String normalizedTitle = book.getTitle().toLowerCase();
        assertTrue(
            normalizedTitle.contains("послания") ||
            normalizedTitle.contains("вымышленного") ||
            normalizedTitle.contains("царства") ||
            normalizedTitle.contains("царство"),
            "Title should contain key words from 'Послания из вымышленного царства'. Got: " + book.getTitle()
        );

        // Verify Author - Should contain "Горелов" or "Горелова"
        assertNotNull(book.getAuthor(), "Author should not be null");
        String normalizedAuthor = book.getAuthor().toLowerCase();
        assertTrue(
            normalizedAuthor.contains("горелов") || normalizedAuthor.contains("горелова"),
            "Author should contain 'Горелов' or 'Горелова'. Got: " + book.getAuthor()
        );

        // Verify ISBN - 5-352-01102-X (or without hyphens)
        assertNotNull(book.getIsbn(), "ISBN should not be null");
        String normalizedIsbn = book.getIsbn().replaceAll("-", "");
        assertTrue(
            normalizedIsbn.contains("535201102"),
            "ISBN should be '5-352-01102-X' or similar. Got: " + book.getIsbn()
        );

        // Verify Year - 2004
        assertNotNull(book.getPublicationYear(), "Year should not be null");
        assertEquals(2004, book.getPublicationYear(), "Year should be 2004");

        // Print parsed book for verification
        System.out.println("=== Parsed Book ===");
        System.out.println("Title: " + book.getTitle());
        System.out.println("Author: " + book.getAuthor());
        System.out.println("ISBN: " + book.getIsbn());
        System.out.println("Year: " + book.getPublicationYear());
        System.out.println("Publisher: " + book.getPublisher());
        System.out.println("City: " + book.getCity());
        System.out.println("===================");
    }

    @Test
    void extractAndParseBook_VerifiesSpecificValues() throws IOException, TesseractException {
        // Arrange
        ClassPathResource resource = new ClassPathResource("page.jpg");
        byte[] imageBytes = Files.readAllBytes(resource.getFile().toPath());

        // Act
        String extractedText = ocrService.extractText(imageBytes);
        ParsedBookData book = bookParser.parse("", "", extractedText);

        // Assert - More strict verification of expected values
        assertAll("Book metadata should be correctly extracted",
            () -> assertNotNull(book.getTitle(), "Title must be extracted"),
            () -> assertNotNull(book.getAuthor(), "Author must be extracted"),
            () -> assertNotNull(book.getIsbn(), "ISBN must be extracted"),
            () -> assertNotNull(book.getPublicationYear(), "Year must be extracted"),
            () -> assertEquals(2004, book.getPublicationYear(), "Year must be 2004"),
            () -> assertTrue(book.getIsbn().replaceAll("-", "").contains("535201102"),
                "ISBN must contain 535201102"),
            () -> assertFalse(book.getTitle().trim().isEmpty(),
                "Title must not be empty"),
            () -> assertFalse(book.getAuthor().trim().isEmpty(),
                "Author must not be empty")
        );
    }

    @Test
    void extractAndParseBook_VerifiesPublisherAndCity() throws IOException, TesseractException {
        // Arrange
        ClassPathResource resource = new ClassPathResource("page.jpg");
        byte[] imageBytes = Files.readAllBytes(resource.getFile().toPath());

        // Act
        String extractedText = ocrService.extractText(imageBytes);
        ParsedBookData book = bookParser.parse("", "", extractedText);

        // Assert - Check publisher and city if OCR can extract them
        System.out.println("Publisher: " + book.getPublisher());
        System.out.println("City: " + book.getCity());

        // These are optional but good to verify
        if (book.getPublisher() != null) {
            String normalizedPublisher = book.getPublisher().toLowerCase();
            assertTrue(
                normalizedPublisher.contains("азбука") || normalizedPublisher.contains("классика"),
                "Publisher should contain 'Азбука' or 'классика'. Got: " + book.getPublisher()
            );
        }

        if (book.getCity() != null) {
            String normalizedCity = book.getCity().toLowerCase();
            assertTrue(
                normalizedCity.contains("спб") || normalizedCity.contains("петербург"),
                "City should contain 'СПб' or 'Петербург'. Got: " + book.getCity()
            );
        }
    }

    @Test
    void extractAndParseBook_BolotovBook_ShowsActualOCR() throws IOException, TesseractException {
        // Arrange
        ClassPathResource resource = new ClassPathResource("bolotov_info.jpg");
        byte[] imageBytes = Files.readAllBytes(resource.getFile().toPath());

        // Act - Extract text using OCR
        String extractedText = ocrService.extractText(imageBytes);

        // Print extracted text to see what OCR actually produces
        System.out.println("=== ACTUAL Bolotov OCR Text ===");
        System.out.println(extractedText);
        System.out.println("================================");

        // Parse and show results
        ParsedBookData book = bookParser.parse("", "", extractedText);

        System.out.println("=== Parsed Results ===");
        System.out.println("Title: " + book.getTitle());
        System.out.println("Author: " + book.getAuthor());
        System.out.println("ISBN: " + book.getIsbn());
        System.out.println("Year: " + book.getPublicationYear());
        System.out.println("Publisher: " + book.getPublisher());
        System.out.println("City: " + book.getCity());
        System.out.println("======================");
    }
}
