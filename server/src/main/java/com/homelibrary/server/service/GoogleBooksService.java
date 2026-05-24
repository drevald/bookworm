package com.homelibrary.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ISBN lookup via Google Books API (priority 2, after НЭБ).
 * Works without an API key (rate-limited to ~1000 req/day).
 * Set GOOGLE_BOOKS_API_KEY env var for a higher quota.
 * Good fallback for books not found in НЭБ (translated titles, non-Russian publishers).
 *
 * @see <a href="https://developers.google.com/books/docs/v1/using">Google Books API</a>
 */
@Service
@Slf4j
public class GoogleBooksService implements IsbnLookupService {

    private static final String BASE_URL =
            "https://www.googleapis.com/books/v1/volumes?q=isbn:{isbn}";
    private static final String BASE_URL_WITH_KEY =
            "https://www.googleapis.com/books/v1/volumes?q=isbn:{isbn}&key={key}";

    @Value("${isbn.providers.google.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String providerName() { return "Google Books"; }

    @Override
    public int priority() { return 3; }

    @Override
    public boolean supports(String lang) { return !"rus".equalsIgnoreCase(lang); }

    @Override
    public Optional<BookMetadataDto> lookup(String isbn) {
        try {
            String url = apiKey.isBlank()
                    ? BASE_URL.replace("{isbn}", isbn)
                    : BASE_URL_WITH_KEY.replace("{isbn}", isbn).replace("{key}", apiKey);

            log.info("[GoogleBooks] Looking up ISBN {}", isbn);
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[GoogleBooks] HTTP {} for ISBN {}", response.getStatusCode(), isbn);
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.getBody());
            if (root.path("totalItems").asInt(0) == 0) {
                log.info("[GoogleBooks] No results for ISBN {}", isbn);
                return Optional.empty();
            }

            JsonNode vi = root.path("items").get(0).path("volumeInfo");

            BookMetadataDto dto = new BookMetadataDto();

            // Title (append subtitle if present)
            String title = vi.path("title").asText(null);
            String subtitle = vi.path("subtitle").asText(null);
            dto.setTitle(subtitle != null && !subtitle.isBlank() ? title + ". " + subtitle : title);

            // Authors
            List<String> authors = new ArrayList<>();
            vi.path("authors").forEach(a -> authors.add(a.asText()));
            dto.setAuthors(authors);

            // Publisher
            String publisher = vi.path("publisher").asText(null);
            dto.setPublisher(publisher);

            // Year (publishedDate can be "2023", "2023-01", or "2023-01-15")
            String publishedDate = vi.path("publishedDate").asText(null);
            if (publishedDate != null && publishedDate.length() >= 4) {
                try {
                    dto.setPublicationYear(Integer.parseInt(publishedDate.substring(0, 4)));
                } catch (NumberFormatException ignored) {}
            }

            // Description
            String description = vi.path("description").asText(null);
            dto.setDescription(description);

            dto.setIsbn(isbn);

            log.info("[GoogleBooks] Found: title='{}', authors={}, year={}",
                    dto.getTitle(), dto.getAuthors(), dto.getPublicationYear());
            return Optional.of(dto);

        } catch (Exception e) {
            log.warn("[GoogleBooks] Lookup failed for ISBN {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }
}
