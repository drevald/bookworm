package com.homelibrary.server.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BookParser {

    // Regex patterns for extracting metadata
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern ISBN_PATTERN = Pattern.compile("ISBN[:\\s-]*([\\d-Xx]+)");
    private static final Pattern ISBN_NUMBER_PATTERN = Pattern.compile("\\d[\\d-]{8,}[\\dXx]");

    // Russian book classification codes
    private static final Pattern BBK_PATTERN = Pattern.compile("ББК[:\\s]*([\\d/.]+)");
    private static final Pattern UDK_PATTERN = Pattern.compile("УДК[:\\s]*([\\d/.]+)");

    // Copyright pattern to extract author
    private static final Pattern COPYRIGHT_PATTERN = Pattern.compile("©\\s*([А-ЯЁA-Z][^,©]+)");

    // Common Russian publishers
    private static final Set<String> KNOWN_PUBLISHERS = Set.of(
        "Азбука", "Азбука-классика", "АСТ", "Эксмо", "Питер", "МИФ",
        "Росмэн", "Детская литература", "Просвещение", "ОЛМА", "Альпина"
    );

    // Common Russian cities
    private static final Set<String> KNOWN_CITIES = Set.of(
        "Москва", "М.", "Санкт-Петербург", "СПб", "СПб.", "Петербург",
        "Киев", "Минск", "Екатеринбург", "Новосибирск"
    );

    @Data
    public static class ParsedBookData {
        private String title;
        private String author;
        private String isbn;
        private Integer publicationYear;
        private String publisher;
        private String city;
        private Set<String> authors = new HashSet<>();
    }

    /**
     * Parse book metadata from OCR-extracted text from multiple pages
     */
    public ParsedBookData parse(String coverText, String backText, String infoText) {
        ParsedBookData data = new ParsedBookData();

        // Cover is most reliable for title and author - parse first
        if (coverText != null && !coverText.trim().isEmpty()) {
            parseCoverPage(coverText, data);
        }

        // Info page provides ISBN, publisher, year - fills in missing data
        if (infoText != null && !infoText.trim().isEmpty()) {
            parseInfoPage(infoText, data);
        }

        // Back cover can provide additional metadata
        if (backText != null && !backText.trim().isEmpty()) {
            parseBackCover(backText, data);
        }

        return data;
    }

    /**
     * Parse info page - most structured source of metadata
     */
    private void parseInfoPage(String text, ParsedBookData data) {
        List<String> lines = Arrays.stream(text.split("\n"))
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .collect(Collectors.toList());

        // Extract ISBN - high priority
        extractISBN(text, data);

        // Extract year - look throughout the text
        extractYear(text, data);

        // Extract publisher and city
        extractPublisherAndCity(text, data);

        // Extract author from copyright lines
        extractAuthorFromCopyright(text, data);

        // Extract title - typically appears after classification codes and before publication info
        extractTitle(lines, data);
    }

    /**
     * Extract title from info page
     * Title usually appears after classification codes (УДК, ББК, П XX)
     * Often on a line starting with a catalog number like "П 61"
     */
    private void extractTitle(List<String> lines, ParsedBookData data) {
        boolean passedClassificationCodes = false;
        List<String> candidateTitles = new ArrayList<>();
        List<String> catalogNumberTitles = new ArrayList<>(); // Higher priority titles with catalog numbers

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String normalized = line.toLowerCase();

            // Skip classification codes at top
            if (normalized.matches("^[уббк\\sпудк/\\.\\d]+$") ||
                UDK_PATTERN.matcher(line).find() ||
                BBK_PATTERN.matcher(line).find()) {
                passedClassificationCodes = true;
                continue;
            }

            // Look for title on line with catalog number
            // Formats: "1161 Послания из вымышленного царства" or "П 61 Послания..." or "Б79 Жизнь..."
            // BUT skip barcodes like "9 \\78581 5\\903395\\"
            if (line.matches("^([\\d]+|[А-ЯЁA-Z]\\s*\\d+)\\s+.+") && !line.contains("\\\\")) {
                String titlePart = line.replaceFirst("^([\\d]+|[А-ЯЁA-Z]\\s*\\d+)\\s+", "");
                // Remove trailing " / Пер." or similar
                titlePart = titlePart.split("/")[0].trim();
                if (titlePart.length() > 10 && !titlePart.matches("^[:\\s].*")) {
                    catalogNumberTitles.add(titlePart); // Add to high-priority list
                    passedClassificationCodes = true;
                    continue;
                }
            }

            // Skip translator/editor credits and names in genitive case
            if (normalized.contains("перевод") || normalized.contains("редакт") ||
                normalized.contains("оформление") || normalized.startsWith("©") ||
                normalized.startsWith(":") || // Lines starting with ":"
                line.matches(".*[а-яё]+(ой|его|ого|ова|овой|ова|ина|иной),.*")) { // Genitive case names
                continue;
            }

            // Skip lines with publication info (author, city, publisher, year)
            if (normalized.contains("спб") || normalized.contains("isbn") ||
                normalized.contains("тираж") || normalized.contains("подписано") ||
                line.matches(".*[А-ЯЁA-Z]\\. [А-ЯЁA-Z][а-яёa-z]+\\..*\\d{4}.*") ||
                ISBN_PATTERN.matcher(line).find()) {
                continue;
            }

            // Collect other potential title lines (after classification codes)
            if (passedClassificationCodes && line.length() > 5) {
                // Skip lines that are clearly not titles
                if (!line.matches("^\\d+\\s+c\\.*$") && // page count
                    !line.matches(".*\\d{4}.*страниц.*") && // page info
                    !line.matches("^[\\d\\s.]+$") && // just numbers
                    !line.matches("^\\d{1,2}\\s+[А-ЯЁа-яё].+") && // TOC entries (1-2 digits + Cyrillic: "3 типология")
                    !line.endsWith(", |") && // TOC entries ending with comma + pipe
                    !line.matches("^[А-ЯЁ][а-яё]+\\s*$") && // single words (section headings)
                    !line.contains("\\\\") && // barcodes
                    !line.contains("ISBN")) { // ISBN lines
                    // Check if it's all uppercase (likely title)
                    if (line.equals(line.toUpperCase()) && line.matches(".*[А-ЯЁA-Z]{3,}.*")) {
                        candidateTitles.add(0, line); // Add to front (higher priority)
                    } else {
                        candidateTitles.add(line);
                    }
                }
            }

            // Stop collecting after first substantial content section
            if (candidateTitles.size() > 3 && line.length() > 50) {
                break;
            }
        }

        // Pick the best title candidate - prioritize catalog number titles
        String title = null;
        if (!catalogNumberTitles.isEmpty()) {
            title = catalogNumberTitles.get(0); // Catalog number titles have highest priority
        } else if (!candidateTitles.isEmpty()) {
            title = candidateTitles.get(0); // Fall back to other candidates
        }

        if (title != null) {
            // Clean up the title
            title = cleanTitle(title);
            if (title.length() > 3) {
                data.setTitle(title);
            }
        }
    }

    /**
     * Extract author from copyright lines (© Author, ...) and standalone author lines
     */
    private void extractAuthorFromCopyright(String text, ParsedBookData data) {
        // First try copyright lines
        Matcher matcher = COPYRIGHT_PATTERN.matcher(text);

        while (matcher.find()) {
            String copyrightHolder = matcher.group(1).trim();

            // Skip if it's clearly a publisher or organization
            if (isLikelyPublisher(copyrightHolder)) {
                continue;
            }

            // Look for author indicators (initials + last name)
            // Russian: Н. Горелов or Н.Горелов
            // English: N. Gorelov or H. Gorelova
            // Mixed: H. Горелов (English initial, Russian surname)
            boolean hasAuthorPattern =
                copyrightHolder.matches(".*[А-ЯЁ]\\. ?[А-ЯЁ][а-яёА-ЯЁ]+.*") ||  // Russian
                copyrightHolder.matches(".*[A-Z]\\. ?[A-Z][a-zA-Z]+.*") ||        // English
                copyrightHolder.matches(".*[A-Z]\\. ?[А-ЯЁ][а-яёА-ЯЁ]+.*") ||     // Mixed: English initial + Russian surname
                copyrightHolder.matches(".*[А-ЯЁ]\\. ?[A-Z][a-zA-Z]+.*");         // Mixed: Russian initial + English surname

            if (hasAuthorPattern) {
                // Extract just the name part (before keywords)
                String name = copyrightHolder.split(",")[0].trim();

                // Remove trailing keywords if present
                name = name.replaceAll("(?i)(перевод|статьи|комментарии|оформление|иллюстрации).*", "").trim();
                name = name.replaceAll("\\s+", " "); // normalize spaces

                if (name.length() > 2) {
                    data.getAuthors().add(name);
                    if (data.getAuthor() == null) {
                        data.setAuthor(name);
                    }
                }
            }
        }

        // If no author found in copyright, look for standalone author lines
        if (data.getAuthor() == null) {
            // Pattern 1: "Firstname Lastname" (Russian names)
            Pattern fullNamePattern = Pattern.compile("^([А-ЯЁ][а-яё]+\\s+[А-ЯЁ][а-яё]+)$", Pattern.MULTILINE);
            Matcher fullNameMatcher = fullNamePattern.matcher(text);

            if (fullNameMatcher.find()) {
                String author = fullNameMatcher.group(1).trim();
                // Make sure it's not a publisher or location
                if (!author.matches(".*(Москва|Санкт|Петербург|Издательств).*")) {
                    data.setAuthor(author);
                    data.getAuthors().add(author);
                }
            }

            // Pattern 2: "Lastname A. T." or "Lastname A.T." (author with initials)
            if (data.getAuthor() == null) {
                Pattern standaloneAuthorPattern = Pattern.compile("^([А-ЯЁA-Z][а-яёa-z]+\\s+[А-ЯЁA-Z]\\.\\s*[А-ЯЁA-Z]\\.)\\s*", Pattern.MULTILINE);
                Matcher standaloneMatcher = standaloneAuthorPattern.matcher(text);

                if (standaloneMatcher.find()) {
                    String author = standaloneMatcher.group(1).trim();
                    data.setAuthor(author);
                    data.getAuthors().add(author);
                }
            }
        }
    }

    /**
     * Extract ISBN from text
     */
    private void extractISBN(String text, ParsedBookData data) {
        // First try with ISBN keyword
        Matcher matcher = ISBN_PATTERN.matcher(text);
        if (matcher.find()) {
            String isbn = matcher.group(1).trim();
            // Normalize ISBN (remove extra spaces/hyphens if needed)
            isbn = isbn.replaceAll("\\s+", "");
            data.setIsbn(isbn);
            return;
        }

        // Fallback: look for ISBN-like numbers
        matcher = ISBN_NUMBER_PATTERN.matcher(text);
        if (matcher.find()) {
            data.setIsbn(matcher.group().trim());
        }
    }

    /**
     * Extract publication year
     */
    private void extractYear(String text, ParsedBookData data) {
        List<String> years = new ArrayList<>();
        Matcher matcher = YEAR_PATTERN.matcher(text);

        while (matcher.find()) {
            years.add(matcher.group());
        }

        if (!years.isEmpty()) {
            // Prefer years that appear multiple times (more reliable)
            Map<String, Long> yearCounts = years.stream()
                .collect(Collectors.groupingBy(y -> y, Collectors.counting()));

            String mostCommonYear = yearCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(years.get(0));

            data.setPublicationYear(Integer.parseInt(mostCommonYear));
        }
    }

    /**
     * Extract publisher and city information
     */
    private void extractPublisherAndCity(String text, ParsedBookData data) {
        List<String> lines = Arrays.asList(text.split("\n"));

        for (String line : lines) {
            String normalized = line.toLowerCase();

            // Look for city abbreviations and names
            if (data.getCity() == null) {
                for (String city : KNOWN_CITIES) {
                    if (line.contains(city)) {
                        data.setCity(city);
                        break;
                    }
                }
            }

            // Look for "City: Publisher, Year" pattern (common in Russian books)
            // Example: "СПб.: Азбука-классика, 2004"
            if (data.getPublisher() == null) {
                for (String city : KNOWN_CITIES) {
                    String pattern = city + "[.:\\s]+([^,;\\d]+)";
                    Pattern p = Pattern.compile(pattern);
                    Matcher m = p.matcher(line);
                    if (m.find()) {
                        String publisherCandidate = m.group(1).trim();
                        // Clean up common separators
                        publisherCandidate = publisherCandidate.replaceAll("[—\\-]+", "").trim();
                        if (publisherCandidate.length() > 2 && !publisherCandidate.matches(".*\\d{4}.*")) {
                            data.setPublisher(publisherCandidate);
                            break;
                        }
                    }
                }
            }

            // Look for known publishers in copyright lines
            if (line.startsWith("©") && data.getPublisher() == null) {
                for (String pub : KNOWN_PUBLISHERS) {
                    if (normalized.contains(pub.toLowerCase())) {
                        data.setPublisher(pub);
                        break;
                    }
                }
            }

            // Look for "Издательство" keyword
            if (normalized.contains("издательство")) {
                String[] parts = line.split(":");
                if (parts.length > 1 && data.getPublisher() == null) {
                    String pubPart = parts[1].trim();
                    // Extract publisher name (before year or comma)
                    pubPart = pubPart.split(",")[0].split("\\d{4}")[0].trim();
                    if (pubPart.length() > 2) {
                        data.setPublisher(pubPart);
                    }
                }
            }
        }
    }

    /**
     * Parse cover page for title
     */
    private void parseCoverPage(String text, ParsedBookData data) {
        // Cover usually has title prominently - take longest line
        String[] lines = text.split("\n");
        String longestLine = Arrays.stream(lines)
            .map(String::trim)
            .filter(line -> line.length() > 5)
            .max(Comparator.comparingInt(String::length))
            .orElse(null);

        if (longestLine != null && data.getTitle() == null) {
            data.setTitle(cleanTitle(longestLine));
        }
    }

    /**
     * Parse back cover for additional metadata
     */
    private void parseBackCover(String text, ParsedBookData data) {
        // Back cover might have ISBN
        if (data.getIsbn() == null) {
            extractISBN(text, data);
        }
    }

    /**
     * Clean up extracted title
     */
    private String cleanTitle(String title) {
        if (title == null) return null;

        // Remove leading/trailing punctuation and spaces
        title = title.replaceAll("^[\\s.,;:/-]+|[\\s.,;:/-]+$", "");

        // Remove catalog numbers that might be stuck to title
        title = title.replaceAll("^[А-ЯЁA-Z]\\s*\\d+\\s*", "");

        // Remove OCR garbage at the end (random letters/numbers that don't look like words)
        // Pattern: space + uppercase letters mixed with digits/symbols at end
        title = title.replaceAll("\\s+[A-Z0-9]{2,}[0-9/+\\-]*$", "");

        return title.trim();
    }

    /**
     * Check if a copyright holder name is likely a publisher rather than an author
     */
    private boolean isLikelyPublisher(String name) {
        String normalized = name.toLowerCase();
        return KNOWN_PUBLISHERS.stream()
            .anyMatch(pub -> normalized.contains(pub.toLowerCase())) ||
            normalized.contains("издательство") ||
            normalized.contains("publishing");
    }
}
