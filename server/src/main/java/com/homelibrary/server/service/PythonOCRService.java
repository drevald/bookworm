package com.homelibrary.server.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client for Python OCR + Metadata Extraction Service
 * (Uses proven working logic from ocr.py)
 */
@Service
@Slf4j
public class PythonOCRService {

    @Value("${ocr.service.url:http://localhost:5000}")
    private String ocrServiceUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    public static class OCRRequest {
        @JsonProperty("cover_image")
        private String coverImage;

        @JsonProperty("info_images")
        private List<String> infoImages;

        @JsonProperty("back_image")
        private String backImage;

        private String language = "rus";
    }

    @Data
    public static class BookMetadata {
        private String title;
        private String author;
        private List<String> authors;
        private String isbn;
        private String publisher;

        @JsonProperty("year")
        private Integer publicationYear;

        private String udk;
        private String bbk;
        private String annotation;
        private Double confidence;
    }

    @Data
    public static class ParsedBookData {
        private String title;
        private String author;
        private String isbn;
        private Integer publicationYear;
        private String publisher;
        private String udk;
        private String bbk;
        private String annotation;
        private Set<String> authors = new HashSet<>();
    }

    /**
     * Extract metadata from book images using Python OCR service
     */
    public ParsedBookData extractMetadata(byte[] coverImage, List<byte[]> infoImages, byte[] backImage, String language) {
        try {
            log.info("Calling Python OCR service at: {}", ocrServiceUrl);

            OCRRequest request = new OCRRequest();
            request.setLanguage(language != null ? language : "rus");

            if (coverImage != null && coverImage.length > 0) {
                request.setCoverImage(Base64.getEncoder().encodeToString(coverImage));
            }

            if (infoImages != null && !infoImages.isEmpty()) {
                List<String> encodedInfoImages = new ArrayList<>();
                for (byte[] infoImage : infoImages) {
                    if (infoImage != null && infoImage.length > 0) {
                        encodedInfoImages.add(Base64.getEncoder().encodeToString(infoImage));
                    }
                }
                request.setInfoImages(encodedInfoImages);
                log.info("Sending {} info page images to OCR service", encodedInfoImages.size());
            }

            if (backImage != null && backImage.length > 0) {
                request.setBackImage(Base64.getEncoder().encodeToString(backImage));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<OCRRequest> httpEntity = new HttpEntity<>(request, headers);

            String url = ocrServiceUrl + "/extract";
            log.info("POST {}", url);

            ResponseEntity<BookMetadata> response = restTemplate.postForEntity(
                    url,
                    httpEntity,
                    BookMetadata.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("Python OCR service returned error: {}", response.getStatusCode());
                return null;
            }

            BookMetadata metadata = response.getBody();
            log.info("Python OCR service returned metadata: title={}, author={}, isbn={}",
                    metadata.getTitle(), metadata.getAuthor(), metadata.getIsbn());

            // Convert to ParsedBookData
            return convertToParsedBookData(metadata);

        } catch (Exception e) {
            log.error("Failed to call Python OCR service", e);
            return null;
        }
    }

    private ParsedBookData convertToParsedBookData(BookMetadata metadata) {
        ParsedBookData data = new ParsedBookData();

        data.setTitle(metadata.getTitle());
        data.setAuthor(metadata.getAuthor());
        data.setIsbn(metadata.getIsbn());
        data.setPublisher(metadata.getPublisher());
        data.setPublicationYear(metadata.getPublicationYear());
        data.setUdk(metadata.getUdk());
        data.setBbk(metadata.getBbk());
        data.setAnnotation(metadata.getAnnotation());

        if (metadata.getAuthors() != null) {
            data.getAuthors().addAll(metadata.getAuthors());
        } else if (metadata.getAuthor() != null && !metadata.getAuthor().equals("unknown")) {
            data.getAuthors().add(metadata.getAuthor());
        }

        return data;
    }

    /**
     * Check if Python OCR service is healthy
     */
    public boolean isHealthy() {
        try {
            String url = ocrServiceUrl + "/health";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Python OCR service health check failed: {}", e.getMessage());
            return false;
        }
    }
}
