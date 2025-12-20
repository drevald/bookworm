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

import java.util.List;

@Service
@Slf4j
public class LLMMetadataExtractor {

    @Value("${bookworm.llm.service.url:http://host.docker.internal:5000}")
    private String llmServiceUrl;

    @Value("${bookworm.llm.service.enabled:true}")
    private boolean llmServiceEnabled;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    public static class OCRTextRequest {
        @JsonProperty("cover_text")
        private String coverText;

        @JsonProperty("back_text")
        private String backText;

        @JsonProperty("info_text")
        private String infoText;
    }

    @Data
    public static class LLMBookMetadata {
        private String title;
        private String author;
        private List<String> authors;
        private String isbn;
        private String publisher;

        @JsonProperty("publication_year")
        private Integer publicationYear;

        private Double confidence;
    }

    /**
     * Extract book metadata using the LLM service
     */
    public BookParser.ParsedBookData extractMetadata(String coverText, String backText, String infoText) {
        if (!llmServiceEnabled) {
            log.info("LLM service is disabled, skipping");
            return null;
        }

        try {
            log.info("Calling LLM service at {} for metadata extraction", llmServiceUrl);

            OCRTextRequest request = new OCRTextRequest();
            request.setCoverText(coverText);
            request.setBackText(backText);
            request.setInfoText(infoText);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<OCRTextRequest> httpEntity = new HttpEntity<>(request, headers);

            String url = llmServiceUrl + "/extract-metadata";
            ResponseEntity<LLMBookMetadata> response = restTemplate.postForEntity(
                    url, httpEntity, LLMBookMetadata.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("LLM service returned error status: {}", response.getStatusCode());
                return null;
            }

            LLMBookMetadata llmMetadata = response.getBody();
            if (llmMetadata == null) {
                log.warn("LLM service returned null response");
                return null;
            }

            log.info("LLM service successfully extracted metadata: title='{}', author='{}', isbn='{}', confidence={}",
                    llmMetadata.getTitle(), llmMetadata.getAuthor(), llmMetadata.getIsbn(), llmMetadata.getConfidence());

            // Convert to BookParser.ParsedBookData
            return convertToParsedBookData(llmMetadata);

        } catch (Exception e) {
            log.error("Failed to call LLM service, will fall back to regex parsing", e);
            return null;
        }
    }

    private BookParser.ParsedBookData convertToParsedBookData(LLMBookMetadata llmMetadata) {
        BookParser.ParsedBookData data = new BookParser.ParsedBookData();

        data.setTitle(llmMetadata.getTitle());
        data.setAuthor(llmMetadata.getAuthor());
        data.setIsbn(llmMetadata.getIsbn());
        data.setPublisher(llmMetadata.getPublisher());
        data.setPublicationYear(llmMetadata.getPublicationYear());

        if (llmMetadata.getAuthors() != null && !llmMetadata.getAuthors().isEmpty()) {
            data.getAuthors().addAll(llmMetadata.getAuthors());
        } else if (llmMetadata.getAuthor() != null) {
            data.getAuthors().add(llmMetadata.getAuthor());
        }

        return data;
    }

    /**
     * Check if LLM service is healthy
     */
    public boolean isHealthy() {
        if (!llmServiceEnabled) {
            return false;
        }

        try {
            String url = llmServiceUrl + "/health";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("LLM service health check failed: {}", e.getMessage());
            return false;
        }
    }
}
