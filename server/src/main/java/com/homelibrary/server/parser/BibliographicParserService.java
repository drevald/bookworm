package com.homelibrary.server.parser;

import com.homelibrary.server.parser.gen.BibRecordLexer;
import com.homelibrary.server.parser.gen.BibRecordParser;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.springframework.stereotype.Service;

/**
 * Spring {@code @Service} that drives the full bibliographic parsing pipeline:
 *
 * <ol>
 *   <li>Normalise raw OCR text via {@link TextNormalizer#normalize}.</li>
 *   <li>Detect the GOST dialect via {@link TextNormalizer#detectDialect}.</li>
 *   <li>Feed normalised text to the ANTLR4 {@link BibRecordLexer} +
 *       {@link BibRecordParser} with a silent {@link BibRecordErrorListener}.</li>
 *   <li>Walk the parse tree with {@link BibRecordBuilderVisitor}.</li>
 *   <li>Compute a rough confidence score from the number of successfully
 *       extracted fields.</li>
 *   <li>Return a fully populated {@link BibRecord}.</li>
 * </ol>
 *
 * <p>The service never throws — it returns a {@link BibRecord} with empty fields
 * (and {@code confidence == 0}) when parsing fails completely.
 */
@Service
@Slf4j
public class BibliographicParserService {

    // One field = roughly 10 confidence points; cap at 100.
    private static final int POINTS_PER_FIELD = 10;

    /**
     * Parses raw OCR text and returns a {@link BibRecord}.
     *
     * @param rawOcrText  raw text extracted from the book's info page
     * @return parsed record; never {@code null}
     */
    public BibRecord parse(String rawOcrText) {
        if (rawOcrText == null || rawOcrText.isBlank()) {
            log.debug("BibliographicParserService.parse called with blank input — returning empty record");
            return BibRecord.builder().build();
        }

        // ── Phase 1: normalise ──────────────────────────────────────────────
        String normalised = TextNormalizer.normalize(rawOcrText);
        GostDialect dialect = TextNormalizer.detectDialect(normalised);
        log.debug("Detected GOST dialect: {}", dialect);

        // ── Phase 2: lex + parse ────────────────────────────────────────────
        BibRecordErrorListener errorListener = new BibRecordErrorListener();

        BibRecordLexer lexer = new BibRecordLexer(CharStreams.fromString(normalised));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);

        CommonTokenStream tokens = new CommonTokenStream(lexer);

        BibRecordParser parser = new BibRecordParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        // Enable ANTLR4's built-in error-recovery so that noisy input
        // doesn't stop the parse mid-way.
        parser.setErrorHandler(new org.antlr.v4.runtime.DefaultErrorStrategy());

        BibRecordParser.InfoPageContext tree = parser.infoPage();

        if (!errorListener.isClean()) {
            log.debug("Parse warnings for record: {}", errorListener.getWarnings());
        }

        // ── Phase 3: visit ──────────────────────────────────────────────────
        BibRecordBuilderVisitor visitor = new BibRecordBuilderVisitor(dialect);
        visitor.visit(tree);
        BibRecord record = visitor.getResult();

        // Propagate parse warnings into the record
        errorListener.getWarnings().forEach(w -> {
            // Access builder isn't available after build(); add directly via setter
            record.getParseWarnings().add(w);
        });

        // Store the raw input so callers can debug parse failures
        record.setRaw(rawOcrText);

        // ── Phase 4: confidence ─────────────────────────────────────────────
        int confidence = Math.min(100, record.fieldCount() * POINTS_PER_FIELD);
        record.setConfidence(confidence);

        log.debug("Parsed BibRecord: title='{}', authors={}, year={}, confidence={}",
                record.getTitle(), record.getAuthors(), record.getYear(), confidence);

        return record;
    }
}
