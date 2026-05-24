package com.homelibrary.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ISBN lookup via Amazon Product Advertising API v5 (PA API).
 *
 * Requires an Amazon Associates account with PA API access.
 * Configure via environment variables or application.properties:
 *
 *   AMAZON_PA_ACCESS_KEY   (isbn.providers.amazon.access-key)
 *   AMAZON_PA_SECRET_KEY   (isbn.providers.amazon.secret-key)
 *   AMAZON_PA_PARTNER_TAG  (isbn.providers.amazon.partner-tag)  e.g. "mytag-20"
 *
 * The service is silently skipped when credentials are absent — no errors,
 * just a debug-level log and an empty result so the next provider is tried.
 *
 * Priority 3: tried after Russian catalogs (РГБ=1, НЭБ=2); Google Books and
 * Open Library are fallbacks (4, 5).  Amazon has excellent metadata for
 * English and other foreign-language books, including publisher and exact year.
 *
 * @see <a href="https://webservices.amazon.com/paapi5/documentation/">PA API v5 docs</a>
 */
@Service
@Slf4j
public class AmazonPaApiService implements IsbnLookupService {

    private static final String HOST     = "webservices.amazon.com";
    private static final String REGION   = "us-east-1";
    private static final String SERVICE  = "ProductAdvertisingAPI";
    private static final String PATH     = "/paapi5/getitems";
    private static final String ENDPOINT = "https://" + HOST + PATH;
    private static final String TARGET   =
            "com.amazon.paapi5.v1.ProductAdvertisingAPIv1.GetItems";

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter D_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    @Value("${isbn.providers.amazon.access-key:}")
    private String accessKey;

    @Value("${isbn.providers.amazon.secret-key:}")
    private String secretKey;

    @Value("${isbn.providers.amazon.partner-tag:}")
    private String partnerTag;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper       = new ObjectMapper();

    @Override public String  providerName()          { return "Amazon"; }
    @Override public int     priority()              { return 1; }
    @Override public boolean supports(String lang)   { return !"rus".equalsIgnoreCase(lang); }

    @Override
    public Optional<BookMetadataDto> lookup(String isbn) {
        if (accessKey.isBlank() || secretKey.isBlank() || partnerTag.isBlank()) {
            log.debug("[Amazon] Credentials not configured — skipping");
            return Optional.empty();
        }
        try {
            String body    = buildBody(isbn);
            HttpHeaders hdrs = sign(body);

            log.info("[Amazon] Looking up ISBN {}", isbn);
            ResponseEntity<String> resp = restTemplate.exchange(
                    ENDPOINT, HttpMethod.POST, new HttpEntity<>(body, hdrs), String.class);

            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                log.warn("[Amazon] HTTP {} for ISBN {}", resp.getStatusCode(), isbn);
                return Optional.empty();
            }
            return parse(resp.getBody(), isbn);

        } catch (Exception e) {
            log.warn("[Amazon] Lookup failed for ISBN {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Request body ───────────────────────────────────────────────────────────

    private String buildBody(String isbn) throws Exception {
        // PA API accepts ItemIdType=ISBN for both ISBN-10 and ISBN-13.
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("ItemIds",    List.of(isbn));
        req.put("ItemIdType", "ISBN");
        req.put("Resources",  List.of(
                "ItemInfo.Title",
                "ItemInfo.ByLineInfo",
                "ItemInfo.ContentInfo",
                "ItemInfo.ExternalIds"));
        req.put("PartnerTag",  partnerTag);
        req.put("PartnerType", "Associates");
        req.put("Marketplace", "www.amazon.com");
        return mapper.writeValueAsString(req);
    }

    // ── AWS Signature Version 4 ────────────────────────────────────────────────

    /**
     * Builds signed HTTP headers for the PA API request.
     *
     * SigV4 steps:
     *   1. Canonical request  = method + path + query + canonical headers + body hash
     *   2. String to sign     = algorithm + datetime + credential scope + hash of (1)
     *   3. Signing key        = HMAC chain: date → region → service → "aws4_request"
     *   4. Signature          = HMAC(signing key, string to sign)
     */
    private HttpHeaders sign(String body) throws Exception {
        ZonedDateTime now      = ZonedDateTime.now(ZoneOffset.UTC);
        String        dateTime = DT_FMT.format(now);
        String        date     = D_FMT.format(now);

        String contentType = "application/json; charset=UTF-8";
        String bodyHash    = sha256Hex(body.getBytes(StandardCharsets.UTF_8));

        // Canonical headers must be sorted alphabetically by header name.
        String canonicalHeaders =
                "content-encoding:amz-1.0\n"
                + "content-type:" + contentType + "\n"
                + "host:" + HOST + "\n"
                + "x-amz-date:" + dateTime + "\n"
                + "x-amz-target:" + TARGET + "\n";
        String signedHeaders = "content-encoding;content-type;host;x-amz-date;x-amz-target";

        String canonicalRequest = String.join("\n",
                "POST", PATH, "",
                canonicalHeaders, signedHeaders, bodyHash);

        String credentialScope = date + "/" + REGION + "/" + SERVICE + "/aws4_request";
        String stringToSign    = String.join("\n",
                "AWS4-HMAC-SHA256", dateTime, credentialScope,
                sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = hmacSha256(
                hmacSha256(
                        hmacSha256(
                                hmacSha256(
                                        ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8),
                                        date),
                                REGION),
                        SERVICE),
                "aws4_request");

        String signature = hex(hmacSha256(signingKey, stringToSign));

        String authorization =
                "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        HttpHeaders headers = new HttpHeaders();
        headers.set("content-encoding", "amz-1.0");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("host",         HOST);
        headers.set("x-amz-date",   dateTime);
        headers.set("x-amz-target", TARGET);
        headers.set("Authorization", authorization);
        return headers;
    }

    // ── Response parsing ───────────────────────────────────────────────────────

    private Optional<BookMetadataDto> parse(String json, String isbn) throws Exception {
        JsonNode root  = mapper.readTree(json);
        JsonNode items = root.path("ItemsResult").path("Items");
        if (!items.isArray() || items.isEmpty()) {
            log.info("[Amazon] No items in response for ISBN {}", isbn);
            return Optional.empty();
        }

        JsonNode item = items.get(0);
        JsonNode info = item.path("ItemInfo");
        BookMetadataDto dto = new BookMetadataDto();

        // Title
        dto.setTitle(info.path("Title").path("DisplayValue").asText(null));

        // Authors — Contributors with RoleType "author"
        List<String> authors = new ArrayList<>();
        info.path("ByLineInfo").path("Contributors").forEach(c -> {
            if ("author".equalsIgnoreCase(c.path("RoleType").asText(""))) {
                String name = c.path("Name").asText(null);
                if (name != null) authors.add(name);
            }
        });
        dto.setAuthors(authors);

        // Publisher
        String brand = info.path("ByLineInfo").path("Brand").path("DisplayValue").asText(null);
        if (brand != null && !brand.isBlank()) dto.setPublisher(brand);

        // Publication year (format: "January 1, 1996" or "1996-01-01" or "1996")
        String pubDate = info.path("ContentInfo").path("PublicationDate")
                             .path("DisplayValue").asText(null);
        if (pubDate != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}").matcher(pubDate);
            if (m.find()) {
                try { dto.setPublicationYear(Integer.parseInt(m.group())); }
                catch (NumberFormatException ignored) {}
            }
        }

        // ISBN-13 from ExternalIds (prefer ISBN-13 over the query ISBN)
        JsonNode isbnNodes = info.path("ExternalIds").path("ISBNs").path("DisplayValues");
        if (isbnNodes.isArray()) {
            for (JsonNode n : isbnNodes) {
                String val = n.asText("").replaceAll("[^0-9X]", "");
                if (val.length() == 13) { dto.setIsbn(val); break; }
            }
        }
        if (dto.getIsbn() == null) dto.setIsbn(isbn);

        log.info("[Amazon] Found: title='{}', authors={}, year={}",
                dto.getTitle(), dto.getAuthors(), dto.getPublicationYear());
        return dto.hasTitle() ? Optional.of(dto) : Optional.empty();
    }

    // ── Crypto helpers ─────────────────────────────────────────────────────────

    private static String sha256Hex(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
