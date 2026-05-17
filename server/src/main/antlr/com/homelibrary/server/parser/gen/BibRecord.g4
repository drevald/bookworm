/**
 * BibRecord.g4 — ANTLR4 grammar for Russian bibliographic info-page text.
 *
 * Covers all four GOST standards:
 *   GOST 7.1-84        — pre-2003 books (no comma after surname)
 *   GOST 7.1-2003      — 2003-2017 books (abbreviated city names)
 *   GOST R 7.0.100-2018 — 2018+ books (full city names, comma after surname)
 *   GOST R 7.0.5-2008  — bibliographic-reference/inline-citation style
 *
 * Design principles
 * ─────────────────
 * 1. TextNormalizer.java runs BEFORE this grammar.  It converts all ". —" / ".—" /
 *    ". -" area-separator variants to U+001E (ASCII Record Separator), which is the
 *    SEP token here.  After normalisation the lexer is deterministic.
 *
 * 2. The grammar is deliberately permissive: every token inside an area is kept as
 *    raw content.  Semantic interpretation (title vs. publisher vs. edition) is done
 *    by AreaClassifier + BibRecordBuilderVisitor in Java, where full Unicode regex
 *    and GOST-dialect awareness are available.
 *
 * 3. Error recovery: BibRecordErrorListener replaces the default console reporter;
 *    the parser never throws on noise input — it just collects warnings.
 *
 * Generated artefacts (build/generated-src/antlr/main/…/gen/):
 *   BibRecordLexer.java  BibRecordParser.java
 *   BibRecordVisitor.java  BibRecordBaseVisitor.java
 */
grammar BibRecord;

@header {
package com.homelibrary.server.parser.gen;
}

// ══════════════════════════════════════════════════════════════════════════════
// PARSER RULES
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Top-level rule: an info page consists of zero or more logical lines
 * (blank lines are simply NL tokens between items).
 */
infoPage
    : (NL | item)* EOF
    ;

/**
 * A single recognised item on the info page.
 *
 * Priority order matters: classDecl and isbnDecl are tried first because
 * they start with unmistakable keywords; bibRecord is the catch-all.
 */
item
    : classDecl   // УДК … or ББК …
    | isbnDecl    // ISBN …
    | bibRecord   // main bibliographic record (one or more areas)
    ;

// ── Classification declarations (УДК / ББК) ──────────────────────────────

/**
 * A classification-code declaration: keyword followed by its value on the
 * same logical line.
 *
 * Examples:
 *   УДК 512.817
 *   ББК 22.14я73
 *   УДК 681.3+519.688
 */
classDecl
    : CLASS_KW lineContent NL
    ;

// ── ISBN declaration ──────────────────────────────────────────────────────

/**
 * Standalone ISBN line.  The ISBN_KW token matches the keyword itself
 * (including common OCR misreads); lineContent captures the digit string.
 *
 * Examples (after TextNormalizer normalises ISBN OCR variants):
 *   ISBN 978-5-534-01070-5
 *   ISBN 5-02-001234-9
 *   (OCR) 15ВМ 978-5-534-01070-5  → normalised to ISBN before reaching grammar
 */
isbnDecl
    : ISBN_KW lineContent NL?
    ;

// ── Bibliographic record ──────────────────────────────────────────────────

/**
 * The main bibliographic description.  After TextNormalizer runs, area
 * boundaries are U+001E (SEP token).  A record has one or more areas.
 *
 * Single-line example (GOST 7.1-2003):
 *   Иванов В.А., Петров Б.В. Алгебра<SEP>3-е изд.<SEP>М. : Наука, 2009<SEP>312 с.
 *
 * The trailing PERIOD? handles the final "." that ends a full record.
 */
bibRecord
    : area (SEP area)* PERIOD? NL?
    ;

// ── Area (content between two SEP markers) ───────────────────────────────

/**
 * An area is either a series area (content in parentheses) or a flat area
 * (any sequence of content tokens).
 *
 * Note: a seriesArea can also appear nested inside a flat area in some
 * OCR outputs; the visitor handles that case by re-scanning flatArea text.
 */
area
    : seriesArea
    | flatArea
    ;

/**
 * Series information enclosed in round brackets.
 * Examples: (Учебники для вузов)   (Серия «Наука»; Вып. 5)
 */
seriesArea
    : LPAREN flatArea RPAREN
    ;

/**
 * Any sequence of content tokens.  The visitor inspects this text to
 * determine which bibliographic field it represents.
 */
flatArea
    : contentToken+
    ;

// ── Content token — everything that can appear inside an area ─────────────

/**
 * All terminal tokens that may appear inside a bibliographic area.
 * This rule is intentionally exhaustive so that OCR noise (unexpected
 * characters) is captured rather than causing parse errors.
 */
contentToken
    : WORD
    | NUMBER
    | PERIOD
    | COMMA
    | COLON
    | SEMICOLON
    | SLASH
    | HYPHEN
    | EM_DASH
    | EQUALS
    | PLUS
    | SP
    | DQUOTE
    | SQUOTE
    | LBRACKET
    | RBRACKET
    | ASTERISK
    | AMPERSAND
    | AT
    | HASH
    | PERCENT
    | ISBN_KW   // ISBN appearing mid-record (e.g. "…—ISBN 978-…")
    | CLASS_KW  // УДК/ББК appearing mid-record (rare but possible in notes)
    | OTHER
    ;

/**
 * Content from the current position to the end of the current logical line
 * (used for classDecl and isbnDecl values).
 */
lineContent
    : (~(NL | SEP))*
    ;


// ══════════════════════════════════════════════════════════════════════════════
// LEXER RULES
// ══════════════════════════════════════════════════════════════════════════════

// ── Keywords (higher priority than WORD — must come first) ────────────────

/**
 * Classification-code keywords.  Combined into one token so the parser
 * can match either with a single alternative.
 */
CLASS_KW
    : 'УДК'
    | 'ББК'
    ;

/**
 * ISBN keyword including the most frequent Tesseract OCR misreads in
 * Russian-language mode:
 *   I → 1, S → 5 or S, B → В (Cyrillic), N → М (Cyrillic) or № (numero)
 *
 * TextNormalizer normalises these to plain "ISBN" before lexing, but we
 * keep the variants here as a second line of defence.
 */
ISBN_KW
    : [Ii][Ss][Bb][Nn]
    | '1' [5Ss] [Bb\u0042\u0412\u0432] [Nn\u004E\u004D\u041C\u043C\u2116]
    | [Ii][Ss][Bb] [Nn\u041C\u043C\u004D]
    ;

// ── Area separator — the canonical structural token ───────────────────────

/**
 * U+001E = ASCII Record Separator.  TextNormalizer replaces every ". —" /
 * ".—" / ". –" / ".- " variant with this single character before the input
 * reaches the lexer, making area boundaries unambiguous.
 */
SEP : '\u001E' ;

// ── Whitespace and newlines ───────────────────────────────────────────────

NL  : [\r\n]+ ;
SP  : [ \t]+ ;

// ── Structural punctuation ────────────────────────────────────────────────

PERIOD    : '.' ;
COMMA     : ',' ;
COLON     : ':' ;
SEMICOLON : ';' ;
SLASH     : '/' ;
EQUALS    : '=' ;
HYPHEN    : '-' | '\u2010' | '\u2011' | '\u2012' ;
EM_DASH   : '\u2014' | '\u2013' | '\u2015' | '\u2212' ;
PLUS      : '+' ;
LPAREN    : '(' ;
RPAREN    : ')' ;
LBRACKET  : '[' ;
RBRACKET  : ']' ;
DQUOTE    : '"' | '\u00AB' | '\u00BB' | '\u201C' | '\u201D' ;
SQUOTE    : '\'' | '\u2018' | '\u2019' ;
ASTERISK  : '*' ;
AMPERSAND : '&' ;
AT        : '@' ;
HASH      : '#' ;
PERCENT   : '%' ;

// ── Content tokens ────────────────────────────────────────────────────────

/**
 * Decimal digit sequences.  Kept separate from WORD so the visitor can
 * quickly identify year literals, page counts, etc.
 */
NUMBER : [0-9]+ ;

/**
 * Letter sequences (Cyrillic, Latin, extended Latin / diacritics).
 * The range \u0400-\u04FF covers the full Cyrillic block.
 * \u00C0-\u02FF covers Latin extended-A/B and IPA extensions.
 */
WORD
    : [\u0400-\u04FF\u0041-\u005A\u0061-\u007A\u00C0-\u02FF]+
    ;

/** Catch-all for any character not matched by the rules above. */
OTHER : . ;
