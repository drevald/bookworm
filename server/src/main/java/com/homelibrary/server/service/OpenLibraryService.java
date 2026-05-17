package com.homelibrary.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ISBN lookup via Open Library (Internet Archive) API.
 * Completely free, no API key required.
 * Good coverage of classic and out-of-print books; weaker for recent Russian titles.
 *
 * @see <a href="https://openlibrary.org/developers/api">Open Library Books API</a>
 */
@Service
@Slf4j
public class OpenLibraryService implements IsbnLookupService {

    private static final String API_URL =
            "https://openlibrary.org/api/books?bibkeys=ISBN:{isbn}&format=json&jscmd=data";

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String providerName() { return "Open Library"; }

    @Override
    public int priority() { return 4; }

    @Override
    public Optional<BookMetadataDto> lookup(String isbn) {
        try {
            String url = API_URL.replace("{isbn}", isbn);
            log.info("[OpenLibrary] Looking up ISBN {}", isbn);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[OpenLibrary] HTTP {} for ISBN {}", response.getStatusCode(), isbn);
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.getBody());
            String key = "ISBN:" + isbn;

            if (!root.has(key)) {
                log.info("[OpenLibrary] No results for ISBN {}", isbn);
                return Optional.empty();
            }

            JsonNode book = root.get(key);
            BookMetadataDto dto = new BookMetadataDto();

            dto.setTitle(book.path("title").asText(null));

            // Authors
            List<String> authors = new ArrayList<>();
            book.path("authors").forEach(a -> {
                String name = a.path("name").asText(null);
                if (name != null) authors.add(name);
            });
            dto.setAuthors(authors);

            // Publisher (first entry)
            JsonNode publishers = book.path("publishers");
            if (publishers.isArray() && !publishers.isEmpty()) {
                dto.setPublisher(publishers.get(0).path("name").asText(null));
            }

            // Year — formats: "2023", "January 1, 2023", "2023-01-01"
            String publishDate = book.path("publish_date").asText(null);
            if (publishDate != null) {
                try {
                    String yearStr = publishDate.replaceAll(".*?(\\d{4}).*", "$1");
                    dto.setPublicationYear(Integer.parseInt(yearStr));
                } catch (NumberFormatException ignored) {}
            }

            dto.setIsbn(isbn);

            log.info("[OpenLibrary] Found: title='{}', authors={}, year={}",
                    dto.getTitle(), dto.getAuthors(), dto.getPublicationYear());
            return Optional.of(dto);

        } catch (Exception e) {
            log.warn("[OpenLibrary] Lookup failed for ISBN {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }
}
