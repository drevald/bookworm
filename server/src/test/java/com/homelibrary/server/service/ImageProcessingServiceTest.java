package com.homelibrary.server.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

class ImageProcessingServiceTest {

    @Test
    void testBolotovCoverCropping() throws IOException {
        ImageProcessingService service = new ImageProcessingService();
        service.init();

        // Load the bolotov cover image
        ClassPathResource resource = new ClassPathResource("bolotov_cover.jpg");
        byte[] originalBytes = Files.readAllBytes(resource.getFile().toPath());

        System.out.println("Original image size: " + originalBytes.length + " bytes");

        // Process it
        byte[] croppedBytes = service.detectAndCrop(originalBytes);

        System.out.println("Cropped image size: " + croppedBytes.length + " bytes");

        // Save the result so we can inspect it
        File outputFile = new File("build/bolotov_cropped.jpg");
        Files.write(outputFile.toPath(), croppedBytes);
        System.out.println("Cropped image saved to: " + outputFile.getAbsolutePath());

        // Check dimensions
        BufferedImage originalImg = ImageIO.read(new ByteArrayInputStream(originalBytes));
        BufferedImage croppedImg = ImageIO.read(new ByteArrayInputStream(croppedBytes));

        System.out.println("Original dimensions: " + originalImg.getWidth() + "x" + originalImg.getHeight());
        System.out.println("Cropped dimensions: " + croppedImg.getWidth() + "x" + croppedImg.getHeight());
    }
}
