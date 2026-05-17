package com.homelibrary.server.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-processing pipeline that converts noisy OCR text into a clean,
 * lexer-ready string before it is passed to the ANTLR4 {@code BibRecord}
 * grammar.
 *
 * <h3>What it does</h3>
 * <ol>
 *   <li><b>Area-separator normalisation</b> — replaces every ". —" / ".—" /
 *       ". –" / ".- " / ".—" variant with U+001E (ASCII Record Separator),
 *       the {@code SEP} token expected by the grammar.</li>
 *   <li><b>Standalone em-dash normalisation</b> — a " — " that does not
 *       follow a period (used in GOST 7.0.100-2018 between areas) is also
 *       converted to SEP.</li>
 *   <li><b>Multi-line record joining</b> — lines that are clearly continuations
 *       of a bibliographic record (no leading keyword, no blank line separator)
 *       are joined with a space.</li>
 *   <li><b>ISBN OCR-misread normalisation</b> — "15ВМ", "15BN", "1SBN" → "ISBN".</li>
 *   <li><b>Whitespace cleanup</b> — multiple spaces, tabs → single space;
 *       trailing whitespace removed.</li>
 *   <li><b>Double-period cleanup</b> — ".." → "." (common OCR artefact).</li>
 *   <li><b>Dialect detection</b> — analyses the normalised text and returns a
 *       {@link GostDialect} enum value.</li>
 * </ol>
 *
 * <p>This class is stateless and all methods are static.
 */
public final class TextNormalizer {

    // U+001E ASCII Record Separator — used as normalised area separator
    public static final char SEP_CHAR = '\u001E';
    public static final String SEP    = String.valueOf(SEP_CHAR);

    private TextNormalizer() {}

    // ── Compiled patterns ─────────────────────────────────────────────────

    /** "." followed by optional space, then an em/en/figure dash, then optional space. */
    private static final Pattern PERIOD_DASH = Pattern.compile(
        "\\.[ \\t]*[\u2014\u2013\u2012\u2015][ \\t]*"
    );

    /**
     * "." followed by optional space, then a hyphen-minus that acts as a dash.
     * Only normalise when followed by a character that can start a new area
     * (uppercase letter, digit, "(", "[").
     * This avoids normalising "изд.-" or "техн.-" abbreviations.
     */
    private static final Pattern PERIOD_HYPHEN_SEP = Pattern.compile(
        "\\.[ \\t]+-[ \\t]+(?=[\\p{Lu}\\p{N}(\\[])"
    );

    /**
     * Standalone " — " (em/en dash surrounded by spaces) that is NOT preceded
     * by a period. Used as area separator in GOST R 7.0.100-2018.
     * Matches only when preceded by a word character.
     */
    private static final Pattern STANDALONE_EMDASH = Pattern.compile(
        "(?<=[\\p{L}\\p{N}\\).])[ \\t]+[\u2014\u2013\u2015\u2212][ \\t]+"
    );

    /** ISBN OCR misreads → canonical "ISBN ". */
    private static final Pattern ISBN_MISREAD = Pattern.compile(
        "(?i)(?:1[5s][bB\u0042\u0412\u0432][nN\u041C\u043C\u004D\u2116]"
        + "|isb[mn\u041C\u043C])"
        + "(?=[ \\t]?[0-9])",
        Pattern.UNICODE_CHARACTER_CLASS
    );

    /** Multiple whitespace on the same line → single space. */
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

    /** Trailing whitespace before newline. */
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+(?=\\n|$)", Pattern.MULTILINE);

    /** Double (or more) periods → single period. */
    private static final Pattern DOUBLE_PERIOD = Pattern.compile("\\.{2,}");

    // Dialect-detection helpers
    private static final Pattern COMMA_SURNAME = Pattern.compile(
        "^[\\u0410-\\u044F\\u0401A-Z][\\u0430-\\u044F\\u0451a-z]{2,},[ \\t]+[\\u0410-\\u042F\\u0401A-Z]",
        Pattern.MULTILINE
    );
    private static final Pattern FULL_CITY = Pattern.compile(
        SEP + "[ \\t]*(?:Москва|Санкт-Петербург|Екатеринбург|Новосибирск|Казань|Краснодар)",
        Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Pattern ABBREV_CITY = Pattern.compile(
        SEP + "[ \\t]*(?:М\\.|Л\\.|СПб\\.|Лгр\\.|Свердл\\.|Киев|Минск)",
        Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Pattern CONTENT_TYPE_QUALIFIER = Pattern.compile(
        "Текст\\s*:\\s*непосредственный", Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Pattern YEAR_RANGE_2018 = Pattern.compile("\\b20(?:1[89]|[2-9]\\d)\\b");
    private static final Pattern YEAR_RANGE_2003 = Pattern.compile("\\b20(?:0[3-9]|1[0-7])\\b");
    private static final Pattern YEAR_RANGE_84   = Pattern.compile("\\b(?:19[0-9]{2}|200[0-2])\\b");

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Runs the full normalisation pipeline on raw OCR text.
     *
     * @param raw  raw OCR output (may be multi-page, multi-line)
     * @return normalised text suitable for the ANTLR4 lexer
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "";

        String s = raw;

        // 1. Normalise line endings
        s = s.replace("\r\n", "\n").replace('\r', '\n');

        // 2. Join hyphenated line-breaks (PDF extraction artefact: "назва-\nние")
        s = s.replaceAll("-\\n(?=[а-яёa-z])", "");

        // 3. ISBN OCR misreads
        s = ISBN_MISREAD.matcher(s).replaceAll("ISBN");

        // 4. Double periods
        s = DOUBLE_PERIOD.matcher(s).replaceAll(".");

        // 5. Multi-line record joining — lines that belong to the same bib record
        s = joinContinuationLines(s);

        // 6. Period+dash area separators (most definitive — do before standalone dash)
        s = PERIOD_DASH.matcher(s).replaceAll(SEP);
        s = PERIOD_HYPHEN_SEP.matcher(s).replaceAll(SEP);

        // 7. Standalone em-dash area separator (GOST 2018 style)
        s = STANDALONE_EMDASH.matcher(s).replaceAll(SEP);

        // 8. Whitespace cleanup
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        s = TRAILING_SPACE.matcher(s).replaceAll("");

        return s.strip();
    }

    /**
     * Detects the most likely GOST dialect from normalised text.
     *
     * <p>Detection heuristics (in priority order):
     * <ol>
     *   <li>Presence of "Текст : непосредственный" → GOST R 7.0.100-2018</li>
     *   <li>Full city names after SEP → GOST R 7.0.100-2018</li>
     *   <li>Comma after surname AND year ≥ 2003 → GOST 7.1-2003</li>
     *   <li>Abbreviated city names AND year 2003-2017 → GOST 7.1-2003</li>
     *   <li>Year ≤ 2002 or no comma after surname → GOST 7.1-84</li>
     *   <li>Otherwise → UNKNOWN</li>
     * </ol>
     *
     * @param normalised text that has already been passed through {@link #normalize}
     * @return best-guess dialect
     */
    public static GostDialect detectDialect(String normalised) {
        if (normalised == null || normalised.isBlank()) return GostDialect.UNKNOWN;

        // Definitive 2018 markers
        if (CONTENT_TYPE_QUALIFIER.matcher(normalised).find()) return GostDialect.GOST_R_7_0_100_2018;
        if (FULL_CITY.matcher(normalised).find())              return GostDialect.GOST_R_7_0_100_2018;

        boolean hasCommaAfterSurname = COMMA_SURNAME.matcher(normalised).find();
        boolean hasAbbrevCity        = ABBREV_CITY.matcher(normalised).find();
        boolean hasYear2018          = YEAR_RANGE_2018.matcher(normalised).find();
        boolean hasYear2003          = YEAR_RANGE_2003.matcher(normalised).find();
        boolean hasYear84            = YEAR_RANGE_84.matcher(normalised).find();

        if (hasYear2018 && hasCommaAfterSurname)          return GostDialect.GOST_R_7_0_100_2018;
        if (hasCommaAfterSurname && (hasYear2003 || hasAbbrevCity)) return GostDialect.GOST_7_1_2003;
        if (hasYear84)                                    return GostDialect.GOST_7_1_84;
        if (hasYear2003 && hasAbbrevCity)                 return GostDialect.GOST_7_1_2003;

        // No definitive markers — default to the newest standard (GOST R 7.0.100-2018)
        // so that modern records parse correctly without dialect-specific hints.
        // Older records that lack year/comma markers will also use 2018 heuristics,
        // which degrades gracefully because AreaClassifier is dialect-tolerant.
        return GostDialect.GOST_R_7_0_100_2018;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Joins lines that are continuation lines of a bibliographic record.
     *
     * <p>A line is a "continuation" when:
     * <ul>
     *   <li>It does not start with a known section keyword (УДК, ББК, ISBN).</li>
     *   <li>It does not start with an author sign (1-2 uppercase + digits alone).</li>
     *   <li>The previous line did not end with a newline that terminates a complete
     *       sentence (heuristic: prev line ended with a digit, dash, comma, or
     *       letter — not a period followed by nothing).</li>
     * </ul>
     *
     * <p>Conservative algorithm: only join when the current line does NOT look
     * like the beginning of a new logical item.
     */
    private static String joinContinuationLines(String text) {
        String[] lines = text.split("\n");
        if (lines.length <= 1) return text;

        StringBuilder sb = new StringBuilder(text.length());
        boolean prevWasBibLine = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();

            if (trimmed.isEmpty()) {
                sb.append('\n');
                prevWasBibLine = false;
                continue;
            }

            boolean isKeywordLine = trimmed.startsWith("УДК")
                || trimmed.startsWith("ББК")
                || ISBN_MISREAD.matcher(trimmed).lookingAt()
                || trimmed.toUpperCase().startsWith("ISBN");

            boolean isAuthorSignLine = trimmed.matches(
                "[\\u0410-\\u042F\\u0401A-Z]{1,2}[ -]?\\d{1,3}\\s*$"
            );

            // A line that starts a new bib record block: starts with uppercase Cyrillic
            // word followed by initials (heading pattern) but only when prevWasBibLine=false
            boolean looksLikeNewItem = isKeywordLine || isAuthorSignLine;

            if (prevWasBibLine && !looksLikeNewItem && i > 0) {
                // Continuation — remove the newline and join with space
                // (trim trailing space from what was already written)
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
                    sb.deleteCharAt(sb.length() - 1);
                    sb.append(' ');
                }
            }

            sb.append(trimmed).append('\n');
            prevWasBibLine = !looksLikeNewItem;
        }

        return sb.toString();
    }

    /**
     * Strips noise tokens that commonly appear at the start of a field value
     * extracted from OCR: leading/trailing dashes, colons, spaces.
     */
    public static String cleanFieldValue(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        // Strip leading punctuation noise
        s = s.replaceFirst("^[:\\-–—\\.\\s]+", "");
        // Strip trailing period if it does not follow an abbreviation
        s = s.replaceFirst("(?<![А-ЯЁа-яёA-Za-z])\\.\\s*$", "");
        return s.strip();
    }

    /**
     * Extracts and validates an ISBN from a raw string.
     * Handles Cyrillic Х → Latin X substitution and dash normalisation.
     *
     * @return 13- or 10-digit clean ISBN string, or {@code null} if invalid
     */
    public static String extractIsbn(String raw) {
        if (raw == null) return null;
        // Normalise Cyrillic Х to Latin X
        String s = raw.replace('\u0425', 'X').replace('\u0445', 'x');
        // Remove all non-alphanumeric except X/x
        String digits = s.replaceAll("[^0-9Xx]", "").toUpperCase();
        if (digits.length() == 13 && digits.chars().allMatch(Character::isDigit)) return digits;
        if (digits.length() == 10
            && (digits.substring(0, 9).chars().allMatch(Character::isDigit))
            && (Character.isDigit(digits.charAt(9)) || digits.charAt(9) == 'X')) {
            return digits;
        }
        return null;
    }
}
