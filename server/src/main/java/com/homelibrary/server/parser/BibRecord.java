package com.homelibrary.server.parser;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unified parsed representation of a bibliographic record extracted from a
 * book's info-page (оборот титульного листа / выходные данные).
 *
 * <p>This is the output model of the ANTLR4-based parser pipeline.
 * All fields are optional (null if not found); {@link #confidence} (0–100)
 * indicates overall parse quality.
 *
 * <p>JSON serialisation (via Jackson) skips {@code null} fields so the
 * REST response stays compact.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BibRecord {

    // ── Primary metadata ──────────────────────────────────────────────────

    /** Primary author(s) listed in the heading or responsibility statement. */
    private List<String> authors = new ArrayList<>();

    /** Editors / compilers / translators from the responsibility statement. */
    private List<String> editors = new ArrayList<>();

    /** Main title of the work. */
    private String title;

    /** Subtitle (after " : " in the title area). */
    private String subtitle;

    /** Edition statement, e.g. "3-е изд., испр. и доп." */
    private String edition;

    // ── Publication data ──────────────────────────────────────────────────

    /** Place of publication, e.g. "М." / "Москва" / "СПб." */
    private String place;

    /** Publisher name. */
    private String publisher;

    /** Publication year. */
    private Integer year;

    // ── Physical characteristics ──────────────────────────────────────────

    /** Total number of pages. */
    private Integer pages;

    /** Illustration note, e.g. "ил." / "цв. ил." */
    private String illustrations;

    /** Physical size, e.g. "22 см" */
    private String size;

    // ── Series ────────────────────────────────────────────────────────────

    /** Series title, e.g. "Учебники для вузов" */
    private String series;

    /** Issue or volume number within the series. */
    private String seriesNumber;

    // ── Standard identifiers ──────────────────────────────────────────────

    /** ISBN (13-digit preferred, 10-digit for older records). */
    private String isbn;

    /** ISSN (for serials in analytical descriptions). */
    private String issn;

    // ── Classification codes ──────────────────────────────────────────────

    /** УДК (Universal Decimal Classification) index. */
    private String udk;

    /** ББК (Библиотечно-библиографическая классификация) index. */
    private String bbk;

    // ── Supplementary ─────────────────────────────────────────────────────

    /** Annotation / abstract text if present on the info page. */
    private String annotation;

    /** Raw OCR text that was fed into the parser (preserved for debugging). */
    private String raw;

    // ── Parser metadata ───────────────────────────────────────────────────

    /** Detected GOST dialect. */
    private GostDialect dialect = GostDialect.UNKNOWN;

    /**
     * Parse confidence: 0–100.
     * Incremented per successfully extracted field; computed by
     * {@link BibliographicParserService} after visitation.
     */
    private int confidence;

    /** Non-fatal parse warnings collected by BibRecordErrorListener. */
    private List<String> parseWarnings = new ArrayList<>();

    // ── Builder ───────────────────────────────────────────────────────────

    public BibRecord() {}

    /** Fluent builder entry point. */
    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final BibRecord r = new BibRecord();

        public Builder authors(List<String> v)    { r.authors = v;       return this; }
        public Builder addAuthor(String v)         { r.authors.add(v);    return this; }
        public Builder editors(List<String> v)     { r.editors = v;       return this; }
        public Builder addEditor(String v)         { r.editors.add(v);    return this; }
        public Builder title(String v)             { r.title = v;         return this; }
        public Builder subtitle(String v)          { r.subtitle = v;      return this; }
        public Builder edition(String v)           { r.edition = v;       return this; }
        public Builder place(String v)             { r.place = v;         return this; }
        public Builder publisher(String v)         { r.publisher = v;     return this; }
        public Builder year(Integer v)             { r.year = v;          return this; }
        public Builder pages(Integer v)            { r.pages = v;         return this; }
        public Builder illustrations(String v)     { r.illustrations = v; return this; }
        public Builder size(String v)              { r.size = v;          return this; }
        public Builder series(String v)            { r.series = v;        return this; }
        public Builder seriesNumber(String v)      { r.seriesNumber = v;  return this; }
        public Builder isbn(String v)              { r.isbn = v;          return this; }
        public Builder issn(String v)              { r.issn = v;          return this; }
        public Builder udk(String v)               { r.udk = v;           return this; }
        public Builder bbk(String v)               { r.bbk = v;           return this; }
        public Builder annotation(String v)        { r.annotation = v;    return this; }
        public Builder raw(String v)               { r.raw = v;           return this; }
        public Builder dialect(GostDialect v)      { r.dialect = v;       return this; }
        public Builder confidence(int v)           { r.confidence = v;    return this; }
        public Builder addWarning(String w)        { r.parseWarnings.add(w); return this; }
        public BibRecord build()                   { return r; }
    }

    // ── Utility ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if at least the title was successfully extracted.
     */
    public boolean hasTitle() {
        return title != null && !title.isBlank();
    }

    /**
     * Counts the number of non-null primary fields to compute a rough
     * quality score (used by {@link BibliographicParserService}).
     */
    public int fieldCount() {
        int n = 0;
        if (hasTitle())                                    n++;
        if (authors != null && !authors.isEmpty())        n++;
        if (publisher != null && !publisher.isBlank())    n++;
        if (year     != null)                             n++;
        if (isbn     != null && !isbn.isBlank())          n++;
        if (udk      != null && !udk.isBlank())           n++;
        if (bbk      != null && !bbk.isBlank())           n++;
        if (place    != null && !place.isBlank())         n++;
        if (pages    != null)                             n++;
        if (series   != null && !series.isBlank())        n++;
        return n;
    }

    // ── Generated getters / setters ───────────────────────────────────────

    public List<String> getAuthors()           { return authors; }
    public void setAuthors(List<String> v)     { this.authors = Objects.requireNonNullElseGet(v, ArrayList::new); }

    public List<String> getEditors()           { return editors; }
    public void setEditors(List<String> v)     { this.editors = Objects.requireNonNullElseGet(v, ArrayList::new); }

    public String getTitle()                   { return title; }
    public void setTitle(String v)             { this.title = v; }

    public String getSubtitle()                { return subtitle; }
    public void setSubtitle(String v)          { this.subtitle = v; }

    public String getEdition()                 { return edition; }
    public void setEdition(String v)           { this.edition = v; }

    public String getPlace()                   { return place; }
    public void setPlace(String v)             { this.place = v; }

    public String getPublisher()               { return publisher; }
    public void setPublisher(String v)         { this.publisher = v; }

    public Integer getYear()                   { return year; }
    public void setYear(Integer v)             { this.year = v; }

    public Integer getPages()                  { return pages; }
    public void setPages(Integer v)            { this.pages = v; }

    public String getIllustrations()           { return illustrations; }
    public void setIllustrations(String v)     { this.illustrations = v; }

    public String getSize()                    { return size; }
    public void setSize(String v)              { this.size = v; }

    public String getSeries()                  { return series; }
    public void setSeries(String v)            { this.series = v; }

    public String getSeriesNumber()            { return seriesNumber; }
    public void setSeriesNumber(String v)      { this.seriesNumber = v; }

    public String getIsbn()                    { return isbn; }
    public void setIsbn(String v)              { this.isbn = v; }

    public String getIssn()                    { return issn; }
    public void setIssn(String v)              { this.issn = v; }

    public String getUdk()                     { return udk; }
    public void setUdk(String v)               { this.udk = v; }

    public String getBbk()                     { return bbk; }
    public void setBbk(String v)               { this.bbk = v; }

    public String getAnnotation()              { return annotation; }
    public void setAnnotation(String v)        { this.annotation = v; }

    public String getRaw()                     { return raw; }
    public void setRaw(String v)               { this.raw = v; }

    public GostDialect getDialect()            { return dialect; }
    public void setDialect(GostDialect v)      { this.dialect = v; }

    public int getConfidence()                 { return confidence; }
    public void setConfidence(int v)           { this.confidence = v; }

    public List<String> getParseWarnings()     { return parseWarnings; }
    public void setParseWarnings(List<String> v) { this.parseWarnings = Objects.requireNonNullElseGet(v, ArrayList::new); }

    @Override
    public String toString() {
        return "BibRecord{title='" + title + "', authors=" + authors
               + ", year=" + year + ", publisher='" + publisher
               + "', place='" + place + "', isbn='" + isbn
               + "', dialect=" + dialect + ", confidence=" + confidence + "}";
    }
}
