package com.homelibrary.server.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ISBN lookup by scraping public Amazon product pages — no credentials required.
 *
 * URL pattern: https://www.amazon.com/dp/{isbn10}
 * ISBN-13 inputs are converted to ISBN-10 before building the URL.
 *
 * Priority 4: runs after Amazon PA API (3) so PA API is preferred when
 * credentials are configured; this service acts as the credential-free fallback.
 *
 * Amazon aggressively detects bots but a personal library app (one request per
 * book, realistic headers, no loop) is almost never blocked in practice.
 * Returns empty on any HTTP error or bot-wall page so the next provider is tried.
 */
@Service
@Slf4j
public class AmazonScraperService implements IsbnLookupService {

    private static final String BASE_URL  = "https://www.amazon.com/dp/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern YEAR_RE = Pattern.compile("\\b(19|20)\\d{2}\\b");

    @Override public String  providerName()        { return "Amazon (scraper)"; }
    @Override public int     priority()            { return 2; }
    @Override public boolean supports(String lang) { return !"rus".equalsIgnoreCase(lang); }

    @Override
    public Optional<BookMetadataDto> lookup(String isbn) {
        String asin = toIsbn10(isbn);
        String url  = BASE_URL + asin;
        try {
            log.info("[AmazonScraper] Fetching {}", url);
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(15_000)
                    .get();

            // Detect bot-wall / captcha page
            if (isBotWall(doc)) {
                log.warn("[AmazonScraper] Bot-wall detected for ISBN {}", isbn);
                return Optional.empty();
            }

            BookMetadataDto dto = new BookMetadataDto();
            dto.setIsbn(isbn);

            parseTitle(doc, dto);
            parseAuthors(doc, dto);
            parseDetails(doc, dto);
            parseDescription(doc, dto);

            if (!dto.hasTitle()) {
                log.info("[AmazonScraper] No title found for ISBN {}", isbn);
                return Optional.empty();
            }

            log.info("[AmazonScraper] Found: title='{}', authors={}, year={}",
                    dto.getTitle(), dto.getAuthors(), dto.getPublicationYear());
            return Optional.of(dto);

        } catch (Exception e) {
            log.warn("[AmazonScraper] Failed for ISBN {}: {}", isbn, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Parsers ────────────────────────────────────────────────────────────────

    private void parseTitle(Document doc, BookMetadataDto dto) {
        Element el = doc.selectFirst("#productTitle");
        if (el != null) dto.setTitle(el.text().trim());
    }

    private void parseAuthors(Document doc, BookMetadataDto dto) {
        // Modern layout: #bylineInfo .author a
        Elements links = doc.select("#bylineInfo span.author a");
        if (links.isEmpty()) {
            // Fallback: any contributor link in bylineInfo
            links = doc.select("#bylineInfo a.contributorNameID, #bylineInfo a.a-link-normal");
        }
        List<String> authors = new ArrayList<>();
        for (Element a : links) {
            String name = a.text().trim();
            if (!name.isEmpty()) authors.add(name);
        }
        if (!authors.isEmpty()) dto.setAuthors(authors);
    }

    /**
     * Parses the product details section which exists in two layouts:
     *
     * Layout A — bullet list (#detailBullets_feature_div):
     *   <li><span class="a-text-bold">Publisher</span><span>Macmillan; 1st edition (Jan 1 1996)</span></li>
     *
     * Layout B — table (#productDetails_techSpec_section_1, #productDetails_detailBullets_sections1):
     *   <tr><th>Publisher</th><td>Macmillan</td></tr>
     */
    private void parseDetails(Document doc, BookMetadataDto dto) {
        // Layout A: bullet list
        for (Element li : doc.select("#detailBullets_feature_div li")) {
            String label = text(li.selectFirst(".a-text-bold"));
            String value = "";
            Elements spans = li.select("span");
            if (spans.size() >= 2) value = spans.last().text().trim();
            applyDetail(label, value, dto);
        }

        // Layout B: table rows
        for (Element tr : doc.select(
                "#productDetails_techSpec_section_1 tr, " +
                "#productDetails_detailBullets_sections1 tr, " +
                "#prodDetails tr")) {
            String label = text(tr.selectFirst("th"));
            String value = text(tr.selectFirst("td"));
            applyDetail(label, value, dto);
        }
    }

    private void applyDetail(String label, String value, BookMetadataDto dto) {
        if (label.isEmpty() || value.isEmpty()) return;
        String l = label.toLowerCase();

        if (l.contains("publisher")) {
            // Publisher field often contains edition and date: "Macmillan; 1st edition (January 1, 1996)"
            // Strip everything from ";" or "(" onward for clean publisher name
            String pub = value.replaceAll("[;(].*", "").trim();
            if (!pub.isEmpty()) dto.setPublisher(pub);
            // Extract year from the full value
            if (dto.getPublicationYear() == null) extractYear(value, dto);
        } else if (l.contains("publication date") || l.contains("date first available")) {
            extractYear(value, dto);
        } else if (l.contains("isbn-10")) {
            // Keep existing isbn unless we get a cleaner one here
            String clean = value.replaceAll("[^0-9Xx]", "");
            if (clean.length() == 10) dto.setIsbn(clean);
        } else if (l.contains("isbn-13")) {
            String clean = value.replaceAll("[^0-9]", "");
            if (clean.length() == 13) dto.setIsbn(clean);
        }
    }

    /**
     * Parses the book description/editorial review.
     * Amazon uses two common selectors:
     *   - #bookDescription_feature_div noscript  (clean text, no JS required)
     *   - #productDescription p                  (older layout)
     */
    private void parseDescription(Document doc, BookMetadataDto dto) {
        // Modern layout: noscript inside bookDescription_feature_div
        Element noscript = doc.selectFirst("#bookDescription_feature_div noscript");
        if (noscript != null) {
            String desc = noscript.text().trim();
            if (!desc.isEmpty()) { dto.setDescription(desc); return; }
        }
        // Older layout
        Element p = doc.selectFirst("#productDescription p");
        if (p != null) {
            String desc = p.text().trim();
            if (!desc.isEmpty()) dto.setDescription(desc);
        }
    }

    private void extractYear(String value, BookMetadataDto dto) {
        Matcher m = YEAR_RE.matcher(value);
        if (m.find()) {
            try { dto.setPublicationYear(Integer.parseInt(m.group())); }
            catch (NumberFormatException ignored) {}
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static boolean isBotWall(Document doc) {
        String title = doc.title().toLowerCase();
        return title.contains("robot check") || title.contains("captcha")
                || title.contains("sorry") || title.contains("access denied")
                || doc.selectFirst("#captchacharacters") != null;
    }

    private static String text(Element el) {
        return el == null ? "" : el.text().trim();
    }

    /**
     * Convert ISBN-13 (978-prefix) to ISBN-10 for use as Amazon ASIN.
     * Returns the input unchanged for ISBN-10 or 979-prefix ISBN-13
     * (those have no ISBN-10 equivalent; Amazon usually still resolves them).
     */
    static String toIsbn10(String isbn) {
        String digits = isbn.replaceAll("[^0-9X]", "").toUpperCase();
        if (digits.length() == 13 && digits.startsWith("978")) {
            String core = digits.substring(3, 12);
            int sum = 0;
            for (int i = 0; i < 9; i++) sum += (core.charAt(i) - '0') * (10 - i);
            int check = (11 - sum % 11) % 11;
            return core + (check == 10 ? "X" : String.valueOf(check));
        }
        return digits;
    }
}
