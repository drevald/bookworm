package com.homelibrary.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ISBN lookup via the Russian State Library (РГБ / search.rsl.ru).
 *
 * Search flow:
 *  1. GET /ru/search            → establish session, extract Yii2 CSRF token
 *  2. POST /site/ajax-search    → AJAX search with CSRF in request body
 *     Falls back to GET if POST fails
 *  3. Parse content HTML for record data-id attributes
 *  4. For each candidate: GET /ru/record/{id} → parse fields with Jsoup
 *  5. Validate record ISBN matches queried ISBN before returning
 *
 * Fields extracted: Заглавие, Автор, ББК, УДК, Выходные данные, ISBN
 */
@Service
@Slf4j
public class RslService implements IsbnLookupService {

    @Value("${isbn.providers.rsl.base-url:https://search.rsl.ru}")
    private String baseUrl;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final int MAX_CANDIDATES = 5;

    private static final Pattern RECORD_ID_PAT = Pattern.compile("/ru/record/(\\d{9,12})");
    private static final Pattern YEAR_PAT      = Pattern.compile("\\b((?:19|20)\\d{2})\\b");

    private final ObjectMapper mapper = new ObjectMapper();

    /** Shared cookie jar — session persists across multiple lookups. */
    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String providerName() { return "РГБ"; }

    @Override
    public int priority() { return 1; }

    // ── Public entry point ─────────────────────────────────────────────────────

    @Override
    public Optional<BookMetadataDto> lookup(String isbn) {
        try {
            String normalized0 = normalizeIsbn(isbn);
            if (normalized0.length() < 10) {
                log.info("[РГБ] Skipping lookup — '{}' is not a valid ISBN", isbn);
                return Optional.empty();
            }
            log.info("[РГБ] Looking up ISBN {}", isbn);
            String csrfToken = initSession();
            List<String> ids = fetchRecordIds(isbn, csrfToken);
            log.info("[РГБ] {} candidate IDs for ISBN {}", ids.size(), isbn);
            if (ids.isEmpty()) return Optional.empty();

            String normalized = normalizeIsbn(isbn);
            for (String id : ids.subList(0, Math.min(MAX_CANDIDATES, ids.size()))) {
                Optional<BookMetadataDto> result = parseRecord(id, normalized);
                if (result.isPresent()) {
                    log.info("[РГБ] Matched record {}: title='{}'", id, result.get().getTitle());
                    return result;
                }
            }

            log.info("[РГБ] No matching record found among {} candidates for ISBN {}", ids.size(), isbn);
            return Optional.empty();

        } catch (Exception e) {
            log.warn("[РГБ] Lookup failed for ISBN {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Session init ───────────────────────────────────────────────────────────

    /**
     * GETs the RSL search page to establish session cookies.
     * Extracts and returns the Yii2 CSRF token from the HTML meta tag.
     */
    private String initSession() throws Exception {
        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/ru/search"))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .GET().timeout(Duration.ofSeconds(15)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        Document doc = Jsoup.parse(resp.body());
        Element meta = doc.selectFirst("meta[name=csrf-token]");
        String token = (meta != null) ? meta.attr("content") : "";
        log.debug("[РГБ] Session init HTTP={}, CSRF present={}", resp.statusCode(), !token.isBlank());
        return token;
    }

    // ── Search: returns a list of candidate record IDs ─────────────────────────

    /**
     * Tries POST then GET AJAX search, returns a deduplicated list of record IDs.
     * When the AJAX search returns unfiltered (full-catalog) results, all records
     * will fail ISBN validation in parseRecord(), so at worst we make a few
     * extra HTTP fetches.
     */
    private List<String> fetchRecordIds(String isbn, String csrfToken) throws Exception {
        String isbnEncoded = URLEncoder.encode(isbn, StandardCharsets.UTF_8);

        // Attempt 1: POST (proper Yii2 form submission with CSRF)
        List<String> ids = tryAjaxPost(isbn, isbnEncoded, csrfToken);
        if (ids != null && !ids.isEmpty()) return ids;

        // Attempt 2: GET AJAX endpoint
        ids = tryAjaxGet(isbn, isbnEncoded, csrfToken);
        if (ids != null && !ids.isEmpty()) return ids;

        return List.of();
    }

    /** POST /site/ajax-search with CSRF in body. */
    private List<String> tryAjaxPost(String isbn, String isbnEncoded, String csrfToken) throws Exception {
        StringBuilder bodyBuilder = new StringBuilder();
        bodyBuilder.append("SearchFilterForm%5Bsearch%5D=").append(isbnEncoded);
        if (!csrfToken.isBlank()) {
            bodyBuilder.append("&_csrf=").append(URLEncoder.encode(csrfToken, StandardCharsets.UTF_8));
        }
        String body = bodyBuilder.toString();

        HttpResponse<String> resp = http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/site/ajax-search?language=ru"))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", baseUrl + "/ru/search#q=isbn%3A" + isbnEncoded)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(20)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        log.debug("[РГБ] POST /site/ajax-search → HTTP {}", resp.statusCode());
        if (resp.statusCode() != 200) return null;
        return parseAjaxResponse(resp.body(), isbn);
    }

    /** GET /site/ajax-search with ISBN in query string. */
    private List<String> tryAjaxGet(String isbn, String isbnEncoded, String csrfToken) throws Exception {
        String url = baseUrl + "/site/ajax-search?language=ru&SearchFilterForm%5Bsearch%5D=" + isbnEncoded;

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", baseUrl + "/ru/search#q=isbn%3A" + isbnEncoded)
                .GET().timeout(Duration.ofSeconds(20));
        if (!csrfToken.isBlank()) {
            b.header("X-Csrf-Token", csrfToken);
        }

        HttpResponse<String> resp = http.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        log.debug("[РГБ] GET /site/ajax-search → HTTP {}", resp.statusCode());
        if (resp.statusCode() != 200) return null;
        return parseAjaxResponse(resp.body(), isbn);
    }

    /**
     * Parses the JSON response from the AJAX search endpoint.
     * {"TotalHits": N, "content": "<HTML with data-id attributes>"}
     * Returns record IDs, or empty list if TotalHits=0.
     */
    private List<String> parseAjaxResponse(String json, String isbn) {
        try {
            JsonNode root = mapper.readTree(json);
            int totalHits = root.path("TotalHits").asInt(0);
            log.info("[РГБ] AJAX TotalHits={} for ISBN {}", totalHits, isbn);
            if (totalHits == 0) return List.of();
            return extractRecordIds(root.path("content").asText(""));
        } catch (Exception e) {
            log.debug("[РГБ] Failed to parse AJAX response: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Record ID extraction ───────────────────────────────────────────────────

    private List<String> extractRecordIds(String contentHtml) {
        List<String> ids = new ArrayList<>();
        if (contentHtml.isBlank()) return ids;
        Document doc = Jsoup.parse(contentHtml);

        // Primary: data-id attribute on result items
        for (Element el : doc.select("[data-id]")) {
            String id = el.attr("data-id").trim();
            if (id.matches("\\d{9,12}") && !ids.contains(id)) {
                ids.add(id);
            }
        }

        // Fallback: extract IDs from href="/ru/record/{id}"
        if (ids.isEmpty()) {
            Matcher m = RECORD_ID_PAT.matcher(contentHtml);
            while (m.find()) {
                String id = m.group(1);
                if (!ids.contains(id)) ids.add(id);
            }
        }

        return ids;
    }

    // ── Record detail fetch + parse ────────────────────────────────────────────

    /**
     * Fetches /ru/record/{id}, parses it, and validates the ISBN.
     * @param normalizedIsbn pass empty string to skip ISBN check
     */
    private Optional<BookMetadataDto> parseRecord(String recordId, String normalizedIsbn) {
        try {
            HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/ru/record/" + recordId))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9")
                    .GET().timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (resp.statusCode() != 200) return Optional.empty();
            return parseRecordHtml(resp.body(), normalizedIsbn, recordId);

        } catch (Exception e) {
            log.debug("[РГБ] Failed to fetch record {}: {}", recordId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses the RSL record detail page.
     *
     * Catalog table structure:
     * <tr><th>Заглавие</th><td itemprop="name">title...</td></tr>
     * <tr><th>Автор</th><td>Фамилия И. О.</td></tr>
     * <tr><th>ББК</th><td>84(2Рос=Рус)6...</td></tr>
     * <tr><th>УДК</th><td>821.161.1</td></tr>
     * <tr><th>Выходные данные</th><td>Санкт-Петербург : Азбука, 2018</td></tr>
     * <tr><th>ISBN</th><td>ISBN 978-5-389-14284-2 (в пер.) : 800.00 руб.</td></tr>
     */
    private Optional<BookMetadataDto> parseRecordHtml(String html, String normalizedIsbn, String recordId) {
        Document doc = Jsoup.parse(html);

        // Build field map from table rows
        Map<String, String> fields = new LinkedHashMap<>();
        for (Element row : doc.select("tr")) {
            Element th = row.selectFirst("th");
            Element td = row.selectFirst("td");
            if (th != null && td != null) {
                String key = th.text().trim();
                if (!key.isEmpty() && !fields.containsKey(key)) {
                    fields.put(key, td.text().trim());
                }
            }
        }

        // ── ISBN validation ─────────────────────────────────────────────────
        // When normalizedIsbn is non-blank we REQUIRE the record to have a
        // matching ISBN.  Records without an ISBN (pre-ISBN era books) are also
        // rejected — they are clearly not what we searched for.
        if (!normalizedIsbn.isBlank()) {
            String isbnRaw = fields.get("ISBN");
            if (isbnRaw == null || isbnRaw.isBlank()) {
                log.debug("[РГБ] Record {} has no ISBN field, skipping", recordId);
                return Optional.empty();
            }
            String recordIsbn = extractIsbnDigits(isbnRaw);
            if (recordIsbn.isBlank() || !isbnMatches(normalizedIsbn, recordIsbn)) {
                log.debug("[РГБ] Record {} ISBN '{}' != '{}'", recordId, recordIsbn, normalizedIsbn);
                return Optional.empty();
            }
        }

        BookMetadataDto dto = new BookMetadataDto();
        dto.setIsbn(normalizedIsbn.isBlank() ? null : normalizedIsbn);

        // ── Title ──────────────────────────────────────────────────────────
        String title = fields.get("Заглавие");
        if (title == null || title.isBlank()) {
            Element el = doc.selectFirst("[itemprop=name]");
            if (el != null) title = el.text();
        }
        if (title != null && !title.isBlank()) {
            dto.setTitle(cleanTitle(title));
        }

        // ── Authors ────────────────────────────────────────────────────────
        String authorRaw = fields.get("Автор");
        if (authorRaw != null && !authorRaw.isBlank()) {
            dto.setAuthors(parseAuthors(authorRaw));
        }

        // ── BBK ────────────────────────────────────────────────────────────
        String bbk = fields.get("ББК");
        if (bbk != null && !bbk.isBlank()) dto.setBbk(firstToken(bbk));

        // ── UDK ────────────────────────────────────────────────────────────
        String udk = fields.get("УДК");
        if (udk != null && !udk.isBlank()) dto.setUdk(firstToken(udk));

        // ── Publisher + Year from Выходные данные ─────────────────────────
        String vydaniya = fields.get("Выходные данные");
        if (vydaniya != null && !vydaniya.isBlank()) {
            parsePublisherYear(vydaniya, dto);
        }

        if (!dto.hasTitle()) {
            log.debug("[РГБ] Record {} has no title, skipping", recordId);
            return Optional.empty();
        }

        return Optional.of(dto);
    }

    // ── Parsing helpers ────────────────────────────────────────────────────────

    /** Strip responsibility statement (after " / ") and normalize whitespace. */
    private String cleanTitle(String raw) {
        int slash = raw.indexOf(" / ");
        if (slash > 0) raw = raw.substring(0, slash);
        return raw.trim().replaceAll("\\s+", " ");
    }

    /** Split authors on ";" or newlines; return at least the raw string if nothing splits. */
    private List<String> parseAuthors(String raw) {
        List<String> result = new ArrayList<>();
        for (String part : raw.split("[;\n]+")) {
            String name = part.trim();
            if (!name.isBlank() && name.length() > 2) result.add(name);
        }
        return result.isEmpty() ? List.of(raw.trim()) : result;
    }

    /**
     * Parse "City : Publisher, Year[...]" from Выходные данные.
     * Examples:
     *   "Санкт-Петербург : Азбука, 2018"
     *   "Москва : АСТ, 2018. — 352 с."
     *   "М. : Наука, 1986"
     */
    private void parsePublisherYear(String raw, BookMetadataDto dto) {
        int colonIdx = raw.indexOf(" : ");
        if (colonIdx > 0) {
            String afterColon = raw.substring(colonIdx + 3);
            Matcher m = YEAR_PAT.matcher(afterColon);
            if (m.find()) {
                try { dto.setPublicationYear(Integer.parseInt(m.group(1))); }
                catch (NumberFormatException ignored) {}
                String pub = afterColon.substring(0, m.start()).replaceAll(",\\s*$", "").trim();
                if (!pub.isBlank()) dto.setPublisher(pub);
            }
        }
        if (dto.getPublicationYear() == null) {
            Matcher m = YEAR_PAT.matcher(raw);
            if (m.find()) {
                try { dto.setPublicationYear(Integer.parseInt(m.group(1))); }
                catch (NumberFormatException ignored) {}
            }
        }
    }

    /**
     * Extract contiguous ISBN-13 or ISBN-10 digits.
     * E.g. "ISBN 978-5-389-14284-2 (в пер.) : 800.00 руб." → "9785389142842"
     */
    private String extractIsbnDigits(String raw) {
        String stripped = raw.replaceAll("[^0-9Xx]", "").toUpperCase();
        if (stripped.length() >= 13) return stripped.substring(0, 13);
        if (stripped.length() >= 10) return stripped.substring(0, 10);
        return "";
    }

    /** Normalize ISBN: digits and uppercase X only. */
    private String normalizeIsbn(String isbn) {
        if (isbn == null) return "";
        return isbn.replaceAll("[^0-9Xx]", "").toUpperCase();
    }

    /** ISBN-10 ↔ ISBN-13 cross-matching (core 9 digits must agree). */
    private boolean isbnMatches(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() == 13 && b.length() == 10) return a.substring(3, 12).equals(b.substring(0, 9));
        if (a.length() == 10 && b.length() == 13) return b.substring(3, 12).equals(a.substring(0, 9));
        return false;
    }

    /** First token from a classification string (before double-space or newline). */
    private String firstToken(String value) {
        return value.split("(?:\\s{2,}|\\n)")[0].trim();
    }
}
