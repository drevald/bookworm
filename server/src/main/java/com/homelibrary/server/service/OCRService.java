package com.homelibrary.server.service;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class OCRService {

    public String extractText(byte[] imageBytes) throws IOException, TesseractException {
        // Tesseract needs a file, so we create a temp file
        Path tempFile = Files.createTempFile("ocr_", ".tmp");
        Files.write(tempFile, imageBytes);
        File file = tempFile.toFile();

        try {
            Tesseract tesseract = new Tesseract();
            // Assuming tessdata is in the project root or configured in environment
            // You might need to set datapath: tesseract.setDatapath("path/to/tessdata");
            tesseract.setLanguage("eng"); 
            return tesseract.doOCR(file);
        } finally {
            file.delete();
        }
    }
}
