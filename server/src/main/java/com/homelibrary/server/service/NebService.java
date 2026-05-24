package com.homelibrary.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ISBN lookup via the National Electronic Library of Russia (НЭБ / нэб.рф).
 * Free, no API key required.
 * Best coverage for Russian-language books.
 *
 * Default base URL uses the IDN punycode form (нэб.рф → xn--c1abs.xn--p1ai)
 * to avoid URL encoding issues in RestTemplate. Override with:
 *   isbn.providers.neb.base-url=https://нэб.рф
 *
 * API endpoint used:
 *   GET {base}/api/public/catalog/documents?query=isbn:{isbn}&size=1
 *
 * If НЭБ changes their API paths, update isbn.providers.neb.base-url
 * or the path constants below.
 */
@Service
@Slf4j
public class NebService implements IsbnLookupService {

    private static final String CATALOG_PATH = "/api/public/catalog/documents";

    @Value("${isbn.providers.neb.base-url:https://xn--c1abs.xn--p1ai}")
    private String baseUrl;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String providerName() { return "НЭБ"; }

    @Override
    public int priority() { return 2; }
    @Override public boolean supports(String lang) { return "rus".equalsIgnoreCase(lang); }

    @Override
    public Optional<BookMetadataDto> lookup(String isbn) {
        try {
            String query = URLEncoder.encode("isbn:" + isbn, StandardCharsets.UTF_8);
            String url = baseUrl + CATALOG_PATH + "?query=" + query + "&size=1";

            log.info("[НЭБ] Looking up ISBN {}", isbn);
            ResponseEntity<String> response = restTemplate.getForEntity(URI.create(url), String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[НЭБ] HTTP {} for ISBN {}", response.getStatusCode(), isbn);
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(response.getBody());

            // Probe common envelope field names used by НЭБ API versions
            JsonNode items = firstArray(root, "documents", "items", "data", "records");
            if (items == null || items.isEmpty()) {
                log.info("[НЭБ] No results for ISBN {}", isbn);
                return Optional.empty();
            }

            JsonNode item = items.get(0);
            BookMetadataDto dto = new BookMetadataDto();

            // Title
            dto.setTitle(textField(item, "title", "name", "caption"));

            // Authors — either array-of-objects, array-of-strings, or a flat string
            List<String> authors = new ArrayList<>();
            JsonNode authorsNode = item.path("authors");
            if (authorsNode.isArray()) {
                authorsNode.forEach(a -> {
                    String name = textField(a, "name", "fullName", "author");
                    if (name == null && a.isTextual()) name = a.asText();
                    if (name != null) authors.add(name);
                });
            } else {
                String single = textField(item, "author", "creator", "authors");
                if (single != null) authors.add(single);
            }
            dto.setAuthors(authors);

            // Publisher — either object with "name" or plain string
            JsonNode pubNode = item.path("publisher");
            String publisher = pubNode.isObject()
                    ? textField(pubNode, "name", "title")
                    : (pubNode.isTextual() ? pubNode.asText(null) : null);
            if (publisher == null) {
                publisher = textField(item, "publishingHouse", "publisherName", "press");
            }
            dto.setPublisher(publisher);

            // Year
            String yearStr = textField(item, "year", "publishYear", "publicationYear", "date");
            if (yearStr != null && yearStr.length() >= 4) {
                try {
                    dto.setPublicationYear(Integer.parseInt(yearStr.substring(0, 4)));
                } catch (NumberFormatException ignored) {}
            }

            dto.setIsbn(isbn);

            log.info("[НЭБ] Found: title='{}', authors={}, year={}",
                    dto.getTitle(), dto.getAuthors(), dto.getPublicationYear());
            return Optional.of(dto);

        } catch (Exception e) {
            log.warn("[НЭБ] Lookup failed for ISBN {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }

    /** Returns the first array-valued child field found among the given names. */
    private JsonNode firstArray(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode v = node.path(field);
            if (v.isArray()) return v;
        }
        return null;
    }

    /** Returns the text value of the first field that exists and is non-blank. */
    private String textField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode v = node.path(field);
            if (v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }
}
