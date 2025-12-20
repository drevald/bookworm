package com.homelibrary.server.service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OCRServiceTest {

    @Mock
    private Tesseract tesseractMock;

    @Test
    void extractText_ValidImage_ReturnsText() throws IOException, TesseractException {
        // Arrange
        OCRService ocrService = new OCRService() {
            @Override
            protected Tesseract getTesseractInstance() {
                return tesseractMock;
            }
        };

        // Create a simple image in memory
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 100);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] imageBytes = baos.toByteArray();

        when(tesseractMock.doOCR(any(BufferedImage.class))).thenReturn("Mocked Text");

        // Act
        String result = ocrService.extractText(imageBytes);

        // Assert
        assertEquals("Mocked Text", result);
        verify(tesseractMock).setLanguage("rus+eng+fra+deu+ukr+swe");
        verify(tesseractMock).doOCR(any(BufferedImage.class));
    }

    @Test
    void extractText_InvalidImage_ThrowsIOException() {
        // Arrange
        OCRService ocrService = new OCRService();
        byte[] invalidBytes = new byte[]{1, 2, 3, 4, 5}; // Not an image

        // Act & Assert
        IOException exception = assertThrows(IOException.class, () -> {
            ocrService.extractText(invalidBytes);
        });

        assertEquals("Could not decode image. Unsupported format.", exception.getMessage());
    }

    @Test
    void extractText_ConfiguresMultipleLanguages() throws IOException, TesseractException {
        // Arrange
        OCRService ocrService = new OCRService() {
            @Override
            protected Tesseract getTesseractInstance() {
                return tesseractMock;
            }
        };

        // Create a simple image
        BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 100);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] imageBytes = baos.toByteArray();

        when(tesseractMock.doOCR(any(BufferedImage.class))).thenReturn("Test");

        // Act
        ocrService.extractText(imageBytes);

        // Assert - verify all languages are configured
        verify(tesseractMock).setLanguage("rus+eng+fra+deu+ukr+swe");
    }
}
