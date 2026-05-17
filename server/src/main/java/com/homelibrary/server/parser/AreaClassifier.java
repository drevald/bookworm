package com.homelibrary.server.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies and parses the raw content of a single bibliographic area
 * (the text between two SEP tokens) into typed {@link AreaResult} values.
 *
 * <p>All classification is content-based (regex + heuristics), not
 * position-based, so it is tolerant of missing or reordered areas caused
 * by OCR noise.
 *
 * <p>GOST-dialect awareness is used to tune city-name and author-format
 * patterns, but the classifier degrades gracefully when the dialect is
 * {@link GostDialect#UNKNOWN}.
 */
public final class AreaClassifier {

    private AreaClassifier() {}

    // ──────────────────────────────────────────────────────────────────────
    // Result types
    // ──────────────────────────────────────────────────────────────────────

    public enum AreaType {
        HEADING,      // Author name(s) before the title
        TITLE,        // Main title [: subtitle] [/ responsibility]
        EDITION,      // Edition statement
        PUBLICATION,  // Place [: Publisher [, Year]]
        PHYSICAL,     // Page count [: ill.] [; size]
        SERIES,       // Series title [; issue]
        ISBN,         // ISBN value
        ISSN,         // ISSN value
        NOTE,         // Free-form note
        UNKNOWN
    }

    /** Parsed result from a single area string. */
    public static final class AreaResult {
        public final AreaType type;
        public final String   raw;

        // Populated according to type:
        public List<String> authors    = new ArrayList<>();  // HEADING / TITLE (responsibility)
        public List<String> editors    = new ArrayList<>();  // TITLE (editors / translators)
        public String title            = null;               // TITLE
        public String subtitle         = null;               // TITLE
        public String edition          = null;               // EDITION
        public String place            = null;               // PUBLICATION
        public String publisher        = null;               // PUBLICATION
        public Integer year            = null;               // PUBLICATION
        public Integer pages           = null;               // PHYSICAL
        public String illustrations    = null;               // PHYSICAL
        public String size             = null;               // PHYSICAL
        public String series           = null;               // SERIES
        public String seriesNumber     = null;               // SERIES
        public String isbn             = null;               // ISBN
        public String issn             = null;               // ISSN
        public String noteText         = null;               // NOTE

        AreaResult(AreaType t, String raw) { this.type = t; this.raw = raw; }

        @Override public String toString() {
            return "AreaResult{" + type + ", raw='" + raw + "'}";
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Classification patterns
    // ──────────────────────────────────────────────────────────────────────

    // ISBN / ISSN
    private static final Pattern P_ISBN_LINE = Pattern.compile(
        "(?i)(?:ISBN|1[5s][bB\u0412\u0432][nN\u041C\u043C\u004D\u2116])\\s*"
        + "([0-9]{3}[-\\s]?[0-9][-\\s]?[0-9]{1,5}[-\\s]?[0-9]{1,7}[-\\s]?[0-9Xx])",
        Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Pattern P_ISSN_LINE = Pattern.compile(
        "(?i)ISSN\\s*(\\d{4}-\\d{3}[\\dXx])"
    );

    // Physical characteristics: "312 с." / "312 стр." / "312 pages"
    private static final Pattern P_PAGES = Pattern.compile(
        "^\\[?([0-9]+)\\]?\\s*(?:с\\.|стр\\.|страниц|p\\.|pages?)(?:\\s*[:,;]|$|\\s)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );
    // Physical area: must contain a page-count token
    private static final Pattern P_PHYSICAL_AREA = Pattern.compile(
        "\\b([0-9]+)\\s*с\\.?(?:\\s*[:,;]|\\s+|$)",
        Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Pattern P_ILLUSTRATIONS = Pattern.compile(
        "((?:цв\\.\\s*)?ил\\.?|рис\\.?|схем\\.?|граф\\.?|карт\\.?|фот\\.?)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Pattern P_SIZE = Pattern.compile(
        "(\\d+)\\s*(?:×\\d+\\s*)?см\\.?"
    );

    // Publication area: place [: publisher] [, year]
    // Abbreviated city: М., Л., СПб., Свердл., Минск etc. (1-6 chars + dot OR known city)
    private static final Pattern P_CITY_ABBREV = Pattern.compile(
        "^([А-ЯЁ][а-яёА-ЯЁ.]{0,15})\\.?\\s*:"
        + "\\s*(.+?)"
        + "(?:,\\s*((?:19|20)\\d{2}))?"
        + "\\s*$",
        Pattern.UNICODE_CHARACTER_CLASS
    );
    // Full city name (GOST 2018): Москва / Санкт-Петербург / …
    private static final Pattern P_CITY_FULL = Pattern.compile(
        "^(Москва|Санкт-Петербург|Петроград|Ленинград|Екатеринбург|Новосибирск"
        + "|Казань|Краснодар|Ростов-на-Дону|Воронеж|Пермь|Самара|Саратов"
        + "|Нижний Новгород|Челябинск|Уфа|Волгоград|Омск|Красноярск|Иркутск"
        + "|Хабаровск|Владивосток|Тверь|Рязань|Тула|Орёл|Брянск|Томск"
        + "|Архангельск|Мурманск|Вологда|Псков|Смоленск|Курск|Белгород"
        + "|Калуга|Ярославль|Кострома|Владимир|Иваново|Сыктывкар"
        + "|Минск|Киев|Алма-Ата|Ташкент|Баку|Тбилиси|Ереван|Рига|Таллин)"
        + "\\s*:\\s*(.+?)"
        + "(?:,\\s*((?:19|20)\\d{2}))?"
        + "\\s*$",
        Pattern.UNICODE_CHARACTER_CLASS
    );
    // Standalone year (when publication area is split across lines)
    private static final Pattern P_YEAR_ONLY = Pattern.compile(
        "^((?:19|20)\\d{2})\\.?\\s*$"
    );

    // Edition statements contain typical keywords
    private static final Pattern P_EDITION = Pattern.compile(
        "\\b(?:\\d+-[еёе]|[Пп]ереработ|[Пп]ерераб\\.|[Дд]оп\\.|[Ии]зд\\.|[Рр]едакц"
        + "|[Сс]тереотип|[Ии]спр\\.|[Рр]еprint|edition|[Рр]евид)",
        Pattern.UNICODE_CHARACTER_CLASS
    );

    // Author-heading patterns
    // GOST 7.1-84 / 7.1-2003 without comma: Иванов В.А.
    private static final Pattern P_AUTHOR_NO_COMMA = Pattern.compile(
        "^([А-ЯЁ][а-яё]+(?:\\s+[А-ЯЁ][а-яё]+)*)\\s+"
        + "([А-ЯЁ]\\.(?:[А-ЯЁ]\\.)?)"
        + "(?:,\\s*([А-ЯЁ][а-яё]+(?:\\s+[А-ЯЁ][а-яё]+)*)\\s+"
        + "([А-ЯЁ]\\.(?:[А-ЯЁ]\\.)?))?"
        + "(?:\\s+и\\s+др\\.)?\\s*$",
        Pattern.UNICODE_CHARACTER_CLASS
    );
    // GOST 7.1-2003+ with comma: Иванов, В.А.
    private static final Pattern P_AUTHOR_COMMA = Pattern.compile(
        "^([А-ЯЁ][а-яё]+),\\s*([А-ЯЁ]\\.(?:\\s*[А-ЯЁ]\\.)?)"
        + "(?:,\\s*([А-ЯЁ][а-яё]+),\\s*([А-ЯЁ]\\.(?:\\s*[А-ЯЁ]\\.)?))*\\s*$",
        Pattern.UNICODE_CHARACTER_CLASS
    );

    // Responsibility statement (after "/")
    private static final Pattern P_RESPONSIBILITY = Pattern.compile(
        "/\\s*(.+)$",
        Pattern.UNICODE_CHARACTER_CLASS
    );

    // Editor/translator markers
    private static final Pattern P_EDITOR_MARKER = Pattern.compile(
        "(?:[Пп]од\\s+ред\\.|[Рр]ед\\.:|[Сс]ост\\.:|[Пп]ер\\s+с|[Пп]ер\\.:|"
        + "[Пп]одгот\\.:|[Аа]втор\\s+идеи|[Gg]eneral\\s+ed\\.)",
        Pattern.UNICODE_CHARACTER_CLASS
    );

    // Author sign (авторский знак): Ч-49 / И 85 / С45
    private static final Pattern P_AUTHOR_SIGN = Pattern.compile(
        "^[А-ЯЁA-Z]{1,2}[-\\s]?\\d{1,3}\\s+"
    );

    // Title area: title [:subtitle] [/responsibility]
    private static final Pattern P_TITLE_COLON = Pattern.compile(
        "^(.+?)\\s*:\\s*(?!//|\\s*/\\s*)(.+?)(?:\\s*/\\s*(.+))?$",
        Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS
    );

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Classifies a single area string and returns a populated {@link AreaResult}.
     *
     * @param raw     text of the area (content between two SEP markers)
     * @param dialect GOST dialect for disambiguation
     * @param isFirst whether this is the first area in the record (title area candidate)
     */
    public static AreaResult classify(String raw, GostDialect dialect, boolean isFirst) {
        if (raw == null) return new AreaResult(AreaType.UNKNOWN, "");
        String trimmed = raw.strip();

        // ── 0. Series area: content enclosed in parentheses ──────────────
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            return parseSeriesArea(trimmed);
        }

        // Strip trailing period — common in GOST records as sentence-end marker.
        // Do this AFTER series check (parens stay intact) so publication
        // patterns with anchored $ can match "М. : Наука, 2009."
        String text = trimmed;
        if (text.length() > 1 && text.endsWith(".")) {
            text = text.substring(0, text.length() - 1).stripTrailing();
        }

        // ── 1. ISBN / ISSN ───────────────────────────────────────────────
        Matcher isbnM = P_ISBN_LINE.matcher(text);
        if (isbnM.find()) {
            AreaResult r = new AreaResult(AreaType.ISBN, trimmed);
            r.isbn = TextNormalizer.extractIsbn(isbnM.group(1));
            return r;
        }
        Matcher issnM = P_ISSN_LINE.matcher(text);
        if (issnM.find()) {
            AreaResult r = new AreaResult(AreaType.ISSN, trimmed);
            r.issn = issnM.group(1);
            return r;
        }

        // ── 2. Physical characteristics ──────────────────────────────────
        if (isPhysicalArea(text)) {
            return parsePhysical(text);
        }

        // ── 3. Publication area ──────────────────────────────────────────
        if (isPublicationArea(text, dialect)) {
            return parsePublication(text, dialect);
        }

        // ── 4. Edition ───────────────────────────────────────────────────
        if (P_EDITION.matcher(text).find()
            && !isFirst
            && !isPublicationArea(text, dialect)) {
            AreaResult r = new AreaResult(AreaType.EDITION, trimmed);
            r.edition = TextNormalizer.cleanFieldValue(text);
            return r;
        }

        // ── 5. First area = title (with optional heading prefix) ─────────
        if (isFirst) {
            return parseTitleArea(trimmed, dialect);
        }

        // ── 6. Note ──────────────────────────────────────────────────────
        AreaResult note = new AreaResult(AreaType.NOTE, trimmed);
        note.noteText = trimmed;
        return note;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Area-type detectors
    // ──────────────────────────────────────────────────────────────────────

    private static boolean isPhysicalArea(String s) {
        return P_PHYSICAL_AREA.matcher(s).find();
    }

    private static boolean isPublicationArea(String s, GostDialect dialect) {
        if (P_CITY_FULL.matcher(s).find()) return true;
        if (P_CITY_ABBREV.matcher(s).find()) return true;
        // Year-only line accepted as degenerate publication area
        if (P_YEAR_ONLY.matcher(s).matches()) return true;
        return false;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Area-specific parsers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Parses a series area that was passed in as {@code "(inner text)"}.
     * Splits on the last {@code ";"} to separate series title from issue number.
     */
    private static AreaResult parseSeriesArea(String raw) {
        // Strip outer parens
        String inner = raw.substring(1, raw.length() - 1).strip();
        AreaResult r = new AreaResult(AreaType.SERIES, raw);
        int semiIdx = inner.lastIndexOf(';');
        if (semiIdx > 0) {
            r.series       = TextNormalizer.cleanFieldValue(inner.substring(0, semiIdx));
            r.seriesNumber = TextNormalizer.cleanFieldValue(inner.substring(semiIdx + 1));
        } else {
            r.series = TextNormalizer.cleanFieldValue(inner);
        }
        return r;
    }

    private static AreaResult parsePhysical(String raw) {
        AreaResult r = new AreaResult(AreaType.PHYSICAL, raw);
        Matcher pm = P_PHYSICAL_AREA.matcher(raw);
        if (pm.find()) {
            try { r.pages = Integer.parseInt(pm.group(1)); } catch (NumberFormatException ignored) {}
        }
        Matcher im = P_ILLUSTRATIONS.matcher(raw);
        if (im.find()) r.illustrations = im.group(1);
        Matcher sm = P_SIZE.matcher(raw);
        if (sm.find()) r.size = sm.group(1) + " см";
        return r;
    }

    private static AreaResult parsePublication(String raw, GostDialect dialect) {
        AreaResult r = new AreaResult(AreaType.PUBLICATION, raw);

        // Year-only degenerate case
        Matcher yo = P_YEAR_ONLY.matcher(raw.strip());
        if (yo.matches()) {
            try { r.year = Integer.parseInt(yo.group(1)); } catch (NumberFormatException ignored) {}
            return r;
        }

        // Try full city first (2018), then abbreviated
        for (Pattern p : List.of(P_CITY_FULL, P_CITY_ABBREV)) {
            Matcher m = p.matcher(raw.strip());
            if (m.find()) {
                r.place = m.group(1).strip();
                String pubPart = m.group(2) != null ? m.group(2).strip() : null;
                String yearStr = m.groupCount() >= 3 ? m.group(3) : null;

                if (yearStr != null) {
                    try { r.year = Integer.parseInt(yearStr); } catch (NumberFormatException ignored) {}
                } else {
                    // Year may be embedded at end of publisher string: "Наука, 2009"
                    if (pubPart != null) {
                        Matcher ym = Pattern.compile(",?\\s*((?:19|20)\\d{2})\\s*$").matcher(pubPart);
                        if (ym.find()) {
                            try { r.year = Integer.parseInt(ym.group(1)); } catch (NumberFormatException ignored) {}
                            pubPart = pubPart.substring(0, ym.start()).strip();
                        }
                    }
                }
                r.publisher = TextNormalizer.cleanFieldValue(pubPart);
                return r;
            }
        }
        return r;
    }

    /**
     * Parses the first area of a bibliographic record, which may contain:
     * <ol>
     *   <li>An optional author heading (авторский знак + heading text)</li>
     *   <li>The main title</li>
     *   <li>An optional subtitle after ":"</li>
     *   <li>An optional responsibility statement after "/"</li>
     * </ol>
     */
    private static AreaResult parseTitleArea(String raw, GostDialect dialect) {
        AreaResult r = new AreaResult(AreaType.TITLE, raw);

        String text = raw;

        // Strip авторский знак prefix (e.g. "И 85 " at the start)
        Matcher signM = P_AUTHOR_SIGN.matcher(text);
        if (signM.find()) {
            text = text.substring(signM.end()).strip();
        }

        // Separate responsibility statement (after "/")
        String responsibility = null;
        Matcher respM = P_RESPONSIBILITY.matcher(text);
        if (respM.find()) {
            responsibility = respM.group(1).strip();
            text = text.substring(0, respM.start()).strip();
        }

        // Detect and strip heading from the start of text
        // Heading ends at ". " (period-space) before the title word
        //   "Иванов В.А. Алгебра …" → heading="Иванов В.А.", title="Алгебра …"
        //   "Иванов, В.А. Алгебра …" → same
        String headingCandidate = detectAndStripHeading(text, dialect);
        if (headingCandidate != null) {
            String[] parts = splitOffHeading(text, headingCandidate);
            parseHeadingAuthors(headingCandidate, r, dialect);
            text = parts[1].strip();
        }

        // Parse title [: subtitle]
        // Only split on ":" when it is preceded by at least 3 chars (avoids city "М.:")
        int colonIdx = findSubtitleColon(text);
        if (colonIdx > 0) {
            r.title    = TextNormalizer.cleanFieldValue(text.substring(0, colonIdx));
            r.subtitle = TextNormalizer.cleanFieldValue(text.substring(colonIdx + 1));
        } else {
            r.title = TextNormalizer.cleanFieldValue(text);
        }

        // Parse responsibility statement
        if (responsibility != null) {
            parseResponsibility(responsibility, r, dialect);
        }

        return r;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Heading helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Attempts to detect the author heading at the start of {@code text}.
     * Returns the raw heading string (without trailing ". ") if found,
     * or {@code null} if the text does not start with a recognisable heading.
     */
    private static String detectAndStripHeading(String text, GostDialect dialect) {
        // Pattern: "Surname[ ,]Initials[, Surname Initials]* . Title..."
        // The heading ends with "[Initials]." followed by a space then an uppercase word
        Pattern headingEnd = Pattern.compile(
            "^((?:[А-ЯЁ][а-яё]+(?:[- ][А-ЯЁ][а-яё]+)*"
            + "(?:,\\s*)?\\s*[А-ЯЁ]\\.(?:[А-ЯЁ]\\.)?\\s*"
            + "(?:(?:,|и|;)\\s*)?)+)\\.?\\s+"
            + "(?=[А-ЯЁ])",
            Pattern.UNICODE_CHARACTER_CLASS
        );
        Matcher m = headingEnd.matcher(text);
        if (m.find()) {
            String candidate = m.group(1).strip();
            // Sanity check: candidate should contain at least one initial (uppercase + dot)
            if (candidate.matches(".*[А-ЯЁA-Z]\\..*") || candidate.matches(".*[А-ЯЁA-Z]\\. *")) {
                return candidate;
            }
        }
        return null;
    }

    /** Splits a text into [heading, rest] at the end of the heading. */
    private static String[] splitOffHeading(String text, String heading) {
        int idx = text.indexOf(heading);
        if (idx < 0) return new String[]{"", text};
        int end = idx + heading.length();
        // Skip trailing ". " after heading
        while (end < text.length() && (text.charAt(end) == '.' || text.charAt(end) == ' ')) end++;
        return new String[]{heading, text.substring(end)};
    }

    /** Parses comma-separated author entries from a heading string. */
    private static void parseHeadingAuthors(String heading, AreaResult r, GostDialect dialect) {
        // Normalise comma-format (GOST 2003+): "Иванов, В.А." → "Иванов В.А."
        String norm = heading.replaceAll("([А-ЯЁ][а-яё]+),\\s+([А-ЯЁ]\\.)", "$1 $2");
        // Split on ", " boundaries between author entries
        String[] parts = norm.split("(?<=\\.)\\s*,\\s*(?=[А-ЯЁ])");
        for (String part : parts) {
            String name = part.strip().replaceAll("\\.$", "");
            if (!name.isBlank() && !name.equalsIgnoreCase("и др")) {
                r.authors.add(name);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Subtitle colon detection
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Finds the index of a "subtitle colon" in a title string.
     * Avoids false positives from "М. :" (city colon) or time expressions.
     *
     * @return index of ":" or -1 if not found
     */
    private static int findSubtitleColon(String title) {
        // Look for " : " (space-colon-space) — standard subtitle separator
        int idx = title.indexOf(" : ");
        if (idx > 2) return idx + 1; // points to the char after space
        // Also accept ": " without leading space, but only after >= 4 chars
        idx = title.indexOf(": ");
        if (idx > 3) {
            // Make sure it's not a city abbreviation like "М.:"
            char prev = title.charAt(idx - 1);
            if (prev != '.' && prev != ')') return idx + 1;
        }
        return -1;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Responsibility statement parsing
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Parses a responsibility statement (the text after "/") into authors
     * and editors lists.
     *
     * <p>Multiple entries are separated by ";".
     * Entries containing editor/translator markers go to {@code r.editors};
     * the rest are added to {@code r.authors} if the authors list is still empty.
     */
    private static void parseResponsibility(String resp, AreaResult r, GostDialect dialect) {
        // Split on "; " to find individual responsibility groups
        String[] groups = resp.split(";");
        for (String group : groups) {
            String g = group.strip();
            if (g.isEmpty()) continue;

            if (P_EDITOR_MARKER.matcher(g).find()) {
                // Extract individual names from editor group
                parseNamesFromGroup(g, r.editors, dialect);
            } else {
                // Authors in responsibility statement
                if (r.authors.isEmpty()) {
                    parseNamesFromGroup(g, r.authors, dialect);
                }
            }
        }
    }

    /** Parses individual names from a responsibility group (comma/semicolon separated). */
    private static void parseNamesFromGroup(String group, List<String> target, GostDialect dialect) {
        // Remove editor/translator prefix like "под ред. ", "сост. "
        String cleaned = group.replaceFirst(
            "^(?:[Пп]од\\s+ред\\.|[Рр]ед\\.:|[Сс]ост\\.:|[Пп]ер\\.:|[Аа]вт\\.)\\s*", ""
        ).strip();

        // Split on comma between names: "Иванов В.А., Петров Б.В."
        String[] names = cleaned.split(",\\s*(?=[А-ЯЁA-Z])");
        for (String name : names) {
            String n = name.strip()
                .replaceAll("^[и]\\s+др\\.?.*$", "")  // remove "и др."
                .strip();
            if (!n.isEmpty()) target.add(n);
        }
    }
}
