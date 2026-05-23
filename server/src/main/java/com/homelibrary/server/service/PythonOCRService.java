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
import java.util.Map;
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

        @JsonProperty("title_images")
        private List<String> titleImages;

        @JsonProperty("back_image")
        private String backImage;

        @JsonProperty("barcode_image")
        private String barcodeImage;

        private String language = "rus";

        /** Force a specific Python GOST parser: "2018", "2003", "84", "2008", or null for auto. */
        @JsonProperty("gost_parser")
        private String gostParser;
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

        @JsonProperty("barcode_value")
        private String barcodeValue;

        @JsonProperty("raw_ocr")
        private String rawOcr;
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
        /** Raw value decoded from barcode image (ISBN-13 digits, null if no barcode). */
        private String barcodeValue;
        /** Raw OCR text from all images combined. */
        private String rawOcrText;
    }

    /**
     * Extract metadata from book images using Python OCR service
     */
    public ParsedBookData extractMetadata(byte[] coverImage, List<byte[]> titleImages, List<byte[]> infoImages, byte[] backImage, byte[] barcodeImage, String language, String gostParser) {
        try {
            log.info("Calling Python OCR service at: {}", ocrServiceUrl);

            OCRRequest request = new OCRRequest();
            request.setLanguage(language != null ? language : "rus");
            if (gostParser != null && !gostParser.isBlank()) {
                request.setGostParser(gostParser);
            }

            if (coverImage != null && coverImage.length > 0) {
                request.setCoverImage(Base64.getEncoder().encodeToString(coverImage));
            }

            if (titleImages != null && !titleImages.isEmpty()) {
                List<String> encoded = new ArrayList<>();
                for (byte[] img : titleImages) {
                    if (img != null && img.length > 0) encoded.add(Base64.getEncoder().encodeToString(img));
                }
                request.setTitleImages(encoded);
                log.info("Sending {} title page images to OCR service", encoded.size());
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

            if (barcodeImage != null && barcodeImage.length > 0) {
                request.setBarcodeImage(Base64.getEncoder().encodeToString(barcodeImage));
                log.info("Sending barcode image to OCR service");
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

        data.setBarcodeValue(metadata.getBarcodeValue());
        data.setRawOcrText(metadata.getRawOcr());

        return data;
    }

    /**
     * Run the full preprocessing pipeline on a single image and return each
     * intermediate stage as JPEG bytes.
     *
     * Keys in the returned map:
     *   "perspective" — after planar homography correction
     *   "dewarped"    — after full pipeline (perspective + dewarp + illumination)
     *
     * Returns an empty map if the call fails.
     */
    public Map<String, byte[]> preprocessImageStages(byte[] imageData) {
        try {
            String b64 = Base64.getEncoder().encodeToString(imageData);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{\"image\":\"" + b64 + "\"}", headers);
            ResponseEntity<java.util.Map> response = restTemplate.postForEntity(
                    ocrServiceUrl + "/preprocess-stages", entity, java.util.Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, byte[]> result = new java.util.LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) response.getBody()).entrySet()) {
                    if (entry.getValue() instanceof String b64val) {
                        result.put((String) entry.getKey(), Base64.getDecoder().decode(b64val));
                    }
                }
                return result;
            }
        } catch (Exception e) {
            log.warn("preprocessImageStages call failed: {}", e.getMessage());
        }
        return java.util.Collections.emptyMap();
    }

    /**
     * Dewarp and illuminate-correct a single image via the Python service.
     * Returns the processed JPEG bytes, or the original bytes if the call fails.
     */
    public byte[] preprocessImage(byte[] imageData) {
        try {
            String b64 = Base64.getEncoder().encodeToString(imageData);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>("{\"image\":\"" + b64 + "\"}", headers);
            ResponseEntity<java.util.Map> response = restTemplate.postForEntity(
                    ocrServiceUrl + "/preprocess-image", entity, java.util.Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String resultB64 = (String) response.getBody().get("image");
                if (resultB64 != null) return Base64.getDecoder().decode(resultB64);
            }
        } catch (Exception e) {
            log.warn("preprocessImage call failed: {}", e.getMessage());
        }
        return imageData;
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
