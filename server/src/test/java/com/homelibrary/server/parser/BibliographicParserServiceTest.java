package com.homelibrary.server.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BibliographicParserService}.
 *
 * <p>Each test uses a real bibliographic record (or a realistic OCR simulation)
 * for one of the four supported GOST dialects. Tests verify the most important
 * fields; minor OCR artefact recovery is also exercised.
 *
 * <p>These tests run against the generated ANTLR4 parser — no Spring context
 * is needed.
 */
class BibliographicParserServiceTest {

    private BibliographicParserService service;

    @BeforeEach
    void setUp() {
        service = new BibliographicParserService();
    }

    // ── GOST R 7.0.100-2018 ───────────────────────────────────────────────

    @Test
    void gost2018_fullRecord() {
        // Canonical GOST R 7.0.100-2018 example (full city names, comma after surname)
        String ocr = """
                УДК 004.9
                ББК 32.97
                Иванов, А. Б.
                    Проектирование информационных систем : учебник / А. Б. Иванов. — Москва : Юрайт, 2021. — 320 с. : ил. — (Бакалавр и специалист).
                ISBN 978-5-534-12345-6
                """;

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getTitle()).isNotBlank();
        assertThat(rec.getTitle()).contains("Проектирование информационных систем");
        assertThat(rec.getYear()).isEqualTo(2021);
        assertThat(rec.getPages()).isEqualTo(320);
        assertThat(rec.getDialect()).isEqualTo(GostDialect.GOST_R_7_0_100_2018);
        assertThat(rec.getConfidence()).isGreaterThanOrEqualTo(50);
        assertThat(rec.getUdk()).isEqualTo("004.9");
        assertThat(rec.getBbk()).isEqualTo("32.97");
    }

    @Test
    void gost2018_withContentTypeQualifier() {
        // "Текст : непосредственный" is a definitive 2018 marker
        String ocr = """
                Петрова, М. В.
                    Русская литература XX века : монография. Текст : непосредственный / М. В. Петрова. — Санкт-Петербург : Лань, 2022. — 240 с.
                ISBN 978-5-8114-9999-1
                """;

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getDialect()).isEqualTo(GostDialect.GOST_R_7_0_100_2018);
        assertThat(rec.getTitle()).contains("Русская литература");
        assertThat(rec.getYear()).isEqualTo(2022);
    }

    // ── GOST 7.1-2003 ────────────────────────────────────────────────────

    @Test
    void gost2003_abbreviatedCity() {
        // GOST 7.1-2003: comma after surname, abbreviated city М., year in 2003-2017 range
        String ocr = """
                УДК 621.3
                Сидоров, В. Н. Электротехника : учеб. пособие / В. Н. Сидоров. — М. : Энергоатомиздат, 2008. — 416 с. : ил. — ISBN 978-5-283-03428-1
                """;

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getTitle()).contains("Электротехника");
        assertThat(rec.getPlace()).isNotNull();
        assertThat(rec.getPublisher()).contains("Энергоатомиздат");
        assertThat(rec.getYear()).isEqualTo(2008);
        assertThat(rec.getDialect()).isIn(GostDialect.GOST_7_1_2003, GostDialect.GOST_R_7_0_100_2018);
        assertThat(rec.getIsbn()).isNotBlank();
    }

    // ── GOST 7.1-84 ──────────────────────────────────────────────────────

    @Test
    void gost84_noCommaAfterSurname() {
        // GOST 7.1-84: surname without comma, pre-2003 year
        String ocr = """
                Смирнов Б.А. Теория автоматического управления.— М. : Машиностроение, 1988.— 328 с.
                """;

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getTitle()).contains("Теория автоматического управления");
        assertThat(rec.getYear()).isEqualTo(1988);
        assertThat(rec.getPages()).isEqualTo(328);
        assertThat(rec.getDialect()).isEqualTo(GostDialect.GOST_7_1_84);
    }

    // ── GOST R 7.0.5-2008 (compact reference) ────────────────────────────

    @Test
    void gost2008_compactReference() {
        // Compact inline-citation format: no heading block, year after publisher
        String ocr = "Коваленко Н.П. Основы программирования. — СПб. : Питер, 2010. — 512 с. — ISBN 978-5-49807-879-5";

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getTitle()).contains("Основы программирования");
        assertThat(rec.getYear()).isEqualTo(2010);
        assertThat(rec.getPages()).isEqualTo(512);
        assertThat(rec.getIsbn()).isNotBlank();
    }

    // ── OCR noise tolerance ───────────────────────────────────────────────

    @Test
    void ocrNoise_isbnMisread() {
        // "15ВМ" is a common OCR misread of "ISBN"
        String ocr = """
                Волков А.Н. Введение в базы данных. — М. : Наука, 1995. — 200 с.
                15ВМ 5-02-003456-7
                """;

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getTitle()).contains("Введение в базы данных");
        assertThat(rec.getIsbn()).isNotNull();
    }

    @Test
    void ocrNoise_doublePeriod() {
        // ".." OCR artefact should be cleaned up without confusion
        String ocr = "Попов И.И.. Алгоритмы и структуры данных.. — М.. : МГУ, 2001.. — 256 с..";

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getTitle()).contains("Алгоритмы");
        assertThat(rec.getYear()).isEqualTo(2001);
    }

    @Test
    void ocrNoise_multiLineRecord() {
        // Record split across multiple lines (common in PDF text extraction)
        String ocr = """
                Александров Д.С.
                Методы численного анализа :
                учебное пособие для вузов /
                Д.С. Александров. — Екатеринбург :
                УрФУ, 2019. — 188 с.
                """;

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getTitle()).contains("Методы численного анализа");
        assertThat(rec.getYear()).isEqualTo(2019);
    }

    // ── Partial / empty input ─────────────────────────────────────────────

    @Test
    void emptyInput_returnsEmptyRecord() {
        BibRecord rec = service.parse("");
        assertThat(rec).isNotNull();
        assertThat(rec.getTitle()).isNull();
        assertThat(rec.getConfidence()).isEqualTo(0);
    }

    @Test
    void nullInput_returnsEmptyRecord() {
        BibRecord rec = service.parse(null);
        assertThat(rec).isNotNull();
        assertThat(rec.getConfidence()).isEqualTo(0);
    }

    @Test
    void partialRecord_onlyTitleAndYear() {
        String ocr = "Неизвестный автор. Краткий справочник. — М. : Книга, 1975.";

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getTitle()).isNotBlank();
        assertThat(rec.getYear()).isEqualTo(1975);
        assertThat(rec.getConfidence()).isGreaterThan(0);
    }

    @Test
    void garbledInput_doesNotThrow() {
        // Completely garbled OCR output should not throw
        String ocr = "!@#$%^&* ??? ??? ??? abc123\n\n###\n\tXXXX";
        assertThat(service.parse(ocr)).isNotNull();
    }

    // ── Series extraction ─────────────────────────────────────────────────

    @Test
    void seriesExtraction() {
        String ocr = """
                Кузнецов Р.А., Лебедев С.М. Физика твёрдого тела : учебник. — М. : Физматлит, 2015. — 400 с. — (Учебники для вузов ; вып. 12). — ISBN 978-5-9221-1620-4
                """;

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getSeries()).isNotBlank();
        assertThat(rec.getSeries()).contains("Учебники для вузов");
        assertThat(rec.getSeriesNumber()).isNotBlank();
    }

    // ── Multiple authors / editors ────────────────────────────────────────

    @Test
    void multipleAuthors_fromResponsibilityStatement() {
        String ocr = """
                УДК 519.6
                Численные методы : учебник / А. А. Самарский, А. В. Гулин. — М. : Наука, 1989. — 432 с.
                """;

        BibRecord rec = service.parse(ocr);

        assertThat(rec.getTitle()).contains("Численные методы");
        assertThat(rec.getAuthors()).isNotEmpty();
    }

    // ── TextNormalizer unit checks ────────────────────────────────────────

    @Test
    void textNormalizer_normalizeSeparators() {
        String input = "Заголовок. — место : издатель, 2020. — 100 с.";
        String norm = TextNormalizer.normalize(input);
        assertThat(norm).contains(String.valueOf(TextNormalizer.SEP_CHAR));
    }

    @Test
    void textNormalizer_detectsDialect2018() {
        String input = "Иванов, А.Б.\u001EМосква : Юрайт, 2020.\u001E320 с.";
        GostDialect d = TextNormalizer.detectDialect(input);
        assertThat(d).isEqualTo(GostDialect.GOST_R_7_0_100_2018);
    }

    @Test
    void textNormalizer_detectsDialect84() {
        String input = "Иванов Б.А.\u001EМ. : Наука, 1985.\u001E256 с.";
        GostDialect d = TextNormalizer.detectDialect(input);
        assertThat(d).isEqualTo(GostDialect.GOST_7_1_84);
    }

    @Test
    void textNormalizer_extractIsbn13() {
        assertThat(TextNormalizer.extractIsbn("978-5-534-12345-6")).isEqualTo("9785534123456");
    }

    @Test
    void textNormalizer_extractIsbn10WithX() {
        // Cyrillic Х (U+0425) → Latin X; ISBN-10 ending in X is valid
        assertThat(TextNormalizer.extractIsbn("0-306-40615-Х")).isEqualTo("030640615X");
    }

}
