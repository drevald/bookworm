package com.homelibrary.server.parser;

/**
 * Identifies which GOST standard a bibliographic record conforms to.
 *
 * <p>Each dialect has distinguishing characteristics that TextNormalizer and
 * AreaClassifier use for disambiguation:
 *
 * <ul>
 *   <li>{@link #GOST_7_1_84}        – no comma after surname; abbreviated city names;
 *       em-dash area separator; dates up to ~2003.</li>
 *   <li>{@link #GOST_7_1_2003}      – no comma after surname (per GOST 7.80-2000 the
 *       comma IS used, but abbreviated city names М., СПб.); 2003–2017.</li>
 *   <li>{@link #GOST_R_7_0_100_2018} – comma after surname (Фамилия, И. О.); full
 *       city names (Москва, Санкт-Петербург); 2018+.</li>
 *   <li>{@link #GOST_R_7_0_5_2008}  – bibliographic-reference / inline-citation
 *       format; compact; no author heading block.</li>
 *   <li>{@link #UNKNOWN}            – dialect could not be determined.</li>
 * </ul>
 */
public enum GostDialect {

    /**
     * ГОСТ 7.1-84 — "Библиографическое описание документа" (pre-2003).
     * <p>Characteristics: surname without comma (Иванов В.А.), abbreviated
     * city names (М., Л., СПб.), area separator ".—".</p>
     */
    GOST_7_1_84,

    /**
     * ГОСТ 7.1-2003 — inter-state standard (2003–2017).
     * <p>Characteristics: comma after surname per ГОСТ 7.80-2000 (Иванов, В.А.),
     * abbreviated city names (М., СПб.), area separator ". —".</p>
     */
    GOST_7_1_2003,

    /**
     * ГОСТ Р 7.0.100-2018 — current Russian national standard (2018+).
     * <p>Characteristics: comma after surname (Иванов, И. О.), full city names
     * (Москва, Санкт-Петербург), area separator " — ", may include
     * "Текст : непосредственный" content-type qualifier.</p>
     */
    GOST_R_7_0_100_2018,

    /**
     * ГОСТ Р 7.0.5-2008 — "Библиографическая ссылка" (bibliographic reference).
     * <p>Characteristics: compact inline-citation format; no separate heading
     * block before title; ". —" or " — " separators; year after publisher.</p>
     */
    GOST_R_7_0_5_2008,

    /**
     * Dialect unknown or could not be determined from the available text.
     */
    UNKNOWN;

    /**
     * Returns {@code true} if the dialect uses a comma after the surname in the
     * author heading (i.e., GOST 7.1-2003 and later).
     */
    public boolean usesCommaAfterSurname() {
        return this == GOST_7_1_2003 || this == GOST_R_7_0_100_2018;
    }

    /**
     * Returns {@code true} if city names are expected to be abbreviated
     * (М., СПб., Л.) rather than written in full.
     */
    public boolean usesAbbreviatedCityNames() {
        return this == GOST_7_1_84 || this == GOST_7_1_2003;
    }
}
