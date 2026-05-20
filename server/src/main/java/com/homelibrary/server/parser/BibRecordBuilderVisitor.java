package com.homelibrary.server.parser;

import com.homelibrary.server.parser.gen.BibRecordBaseVisitor;
import com.homelibrary.server.parser.gen.BibRecordParser;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ANTLR4 visitor that walks the {@link BibRecordParser} parse tree and
 * builds a {@link BibRecord} via {@link AreaClassifier}.
 *
 * <h3>Traversal strategy</h3>
 * <ol>
 *   <li>Walk top-level {@code item} nodes to collect {@code classDecl}
 *       (УДК/ББК) and {@code isbnDecl} items.</li>
 *   <li>For each {@code bibRecord}, collect the text of every {@code area}
 *       between SEP tokens and classify them with {@link AreaClassifier}.</li>
 *   <li>Merge all {@link AreaClassifier.AreaResult}s into a single
 *       {@link BibRecord}; later results overwrite earlier ones only when the
 *       earlier value is empty/null (partial-parse merge strategy).</li>
 * </ol>
 *
 * <p>The visitor never throws on parse errors — the
 * {@link BibRecordErrorListener} captures them as warnings.
 */
public class BibRecordBuilderVisitor extends BibRecordBaseVisitor<Void> {

    private final BibRecord.Builder builder;
    private final GostDialect dialect;
    private final List<AreaClassifier.AreaResult> areas = new ArrayList<>();

    // Accumulation state
    private String  pendingUdk    = null;
    private String  pendingBbk    = null;
    private String  pendingIsbn   = null;
    private boolean bibRecordSeen = false;

    // Classification-code keyword extraction
    private static final Pattern P_CLASS_VALUE = Pattern.compile(
        "^(?:УДК|ББК)\\s*(.+)$",
        Pattern.UNICODE_CHARACTER_CLASS
    );
    private static final Pattern P_ISBN_VALUE = Pattern.compile(
        "(?i)(?:ISBN|1[5s][bB\u0412\u0432][nN\u041C\u043C])\\s*"
        + "([0-9][0-9\\-\\s]{8,16}[0-9Xx])"
    );
    private static final Pattern P_UDK_VALUE = Pattern.compile(
        "^\\s*(.+)$",
        Pattern.MULTILINE
    );

    public BibRecordBuilderVisitor(GostDialect dialect) {
        this.dialect = dialect;
        this.builder = BibRecord.builder().dialect(dialect);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Top-level traversal
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public Void visitInfoPage(BibRecordParser.InfoPageContext ctx) {
        // Visit all children; the per-item visitors accumulate state
        visitChildren(ctx);
        // After visiting everything, apply collected state to builder
        applyAccumulatedState();
        return null;
    }

    @Override
    public Void visitClassDecl(BibRecordParser.ClassDeclContext ctx) {
        String keyword = ctx.CLASS_KW().getText();
        String content = extractText(ctx.lineContent());
        if ("УДК".equals(keyword)) {
            pendingUdk = content.strip();
        } else if ("ББК".equals(keyword)) {
            pendingBbk = content.strip();
        }
        return null;
    }

    @Override
    public Void visitIsbnDecl(BibRecordParser.IsbnDeclContext ctx) {
        String content = extractText(ctx.lineContent());
        Matcher m = P_ISBN_VALUE.matcher(ctx.ISBN_KW().getText() + " " + content);
        if (m.find()) {
            String candidate = TextNormalizer.extractIsbn(m.group(1));
            if (candidate != null) pendingIsbn = candidate;
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Bibliographic record: collect area texts
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public Void visitBibRecord(BibRecordParser.BibRecordContext ctx) {
        bibRecordSeen = true;
        List<BibRecordParser.AreaContext> areaCtxs = ctx.area();

        for (int i = 0; i < areaCtxs.size(); i++) {
            BibRecordParser.AreaContext aCtx = areaCtxs.get(i);
            String areaText = extractAreaText(aCtx);
            if (areaText.isBlank()) continue;

            boolean isFirst = (i == 0) && areas.isEmpty();
            AreaClassifier.AreaResult result =
                AreaClassifier.classify(areaText, dialect, isFirst);
            areas.add(result);
        }
        return null;
    }

    // We do NOT need to override visitArea / visitFlatArea / visitSeriesArea —
    // visitBibRecord handles all area traversal via extractAreaText.

    // ──────────────────────────────────────────────────────────────────────
    // Text extraction helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Recursively concatenates all terminal node texts within a context. */
    private static String extractText(org.antlr.v4.runtime.ParserRuleContext ctx) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ctx.getChildCount(); i++) {
            org.antlr.v4.runtime.tree.ParseTree child = ctx.getChild(i);
            if (child instanceof TerminalNode tn) {
                int type = tn.getSymbol().getType();
                // Skip SEP and NL tokens from the extracted text
                if (type != BibRecordParser.SEP && type != BibRecordParser.NL) {
                    sb.append(tn.getText());
                }
            } else if (child instanceof org.antlr.v4.runtime.ParserRuleContext rc) {
                sb.append(extractText(rc));
            }
        }
        return sb.toString();
    }

    /**
     * Extracts visible text from an {@code area} context.
     * For a {@code seriesArea}, the returned text includes parentheses so
     * the visitor can recognise the series later.
     */
    private static String extractAreaText(BibRecordParser.AreaContext ctx) {
        if (ctx.seriesArea() != null) {
            // Return with parens so AreaClassifier knows it's a series
            return "(" + extractText(ctx.seriesArea().flatArea()) + ")";
        }
        if (ctx.flatArea() != null) {
            return extractText(ctx.flatArea());
        }
        return extractText(ctx);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Final merge into BibRecord
    // ──────────────────────────────────────────────────────────────────────

    /**
     * After the entire parse tree has been visited, merges all accumulated
     * state into the builder.
     */
    private void applyAccumulatedState() {
        if (pendingUdk  != null) builder.udk(pendingUdk);
        if (pendingBbk  != null) builder.bbk(pendingBbk);
        if (pendingIsbn != null) builder.isbn(pendingIsbn);

        for (AreaClassifier.AreaResult ar : areas) {
            mergeAreaResult(ar);
        }
    }

    private void mergeAreaResult(AreaClassifier.AreaResult ar) {
        switch (ar.type) {
            case TITLE -> {
                if (ar.title    != null) builder.title(ar.title);
                if (ar.subtitle != null) builder.subtitle(ar.subtitle);
                if (!ar.authors.isEmpty()) builder.authors(new ArrayList<>(ar.authors));
                if (!ar.editors.isEmpty()) builder.editors(new ArrayList<>(ar.editors));
            }
            case EDITION -> {
                if (ar.edition != null) builder.edition(ar.edition);
            }
            case PUBLICATION -> {
                if (ar.place     != null) builder.place(ar.place);
                if (ar.publisher != null) builder.publisher(ar.publisher);
                if (ar.year      != null) builder.year(ar.year);
            }
            case PHYSICAL -> {
                if (ar.pages        != null) builder.pages(ar.pages);
                if (ar.illustrations != null) builder.illustrations(ar.illustrations);
                if (ar.size         != null) builder.size(ar.size);
            }
            case SERIES -> {
                if (ar.series       != null) builder.series(ar.series);
                if (ar.seriesNumber != null) builder.seriesNumber(ar.seriesNumber);
            }
            case ISBN -> {
                if (ar.isbn != null && pendingIsbn == null) builder.isbn(ar.isbn);
            }
            case ISSN -> {
                if (ar.issn != null) builder.issn(ar.issn);
            }
            case NOTE -> {
                if (ar.noteText != null && !ar.noteText.isBlank()) {
                    builder.annotation(ar.noteText);
                }
            }
            default -> { /* UNKNOWN — skip */ }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Series area special handling
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public Void visitSeriesArea(BibRecordParser.SeriesAreaContext ctx) {
        String content = extractText(ctx.flatArea()).strip();
        if (content.isBlank()) return null;

        // Split on ";" to separate series title from issue number
        int semiIdx = content.lastIndexOf(';');
        if (semiIdx > 0) {
            String seriesTitle  = content.substring(0, semiIdx).strip();
            String seriesNumber = content.substring(semiIdx + 1).strip();
            AreaClassifier.AreaResult r =
                new AreaClassifier.AreaResult(AreaClassifier.AreaType.SERIES, content);
            r.series       = TextNormalizer.cleanFieldValue(seriesTitle);
            r.seriesNumber = TextNormalizer.cleanFieldValue(seriesNumber);
            areas.add(r);
        } else {
            AreaClassifier.AreaResult r =
                new AreaClassifier.AreaResult(AreaClassifier.AreaType.SERIES, content);
            r.series = TextNormalizer.cleanFieldValue(content);
            areas.add(r);
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Result accessor
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Returns the fully-built {@link BibRecord}.
     * Call this <em>after</em> passing the visitor to
     * {@link BibRecordParser#infoPage()}.
     */
    public BibRecord getResult() {
        return builder.build();
    }
}
