package com.homelibrary.server.parser;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ANTLR4 error listener that silently collects parse errors as warning strings
 * instead of writing to {@code System.err} (the ANTLR4 default).
 *
 * <p>Attach one instance to both the lexer and the parser so that all
 * tokenisation and parse errors are captured in a single list:
 *
 * <pre>{@code
 *   BibRecordErrorListener errors = new BibRecordErrorListener();
 *   lexer.removeErrorListeners();
 *   lexer.addErrorListener(errors);
 *   parser.removeErrorListeners();
 *   parser.addErrorListener(errors);
 * }</pre>
 *
 * <p>Retrieve warnings via {@link #getWarnings()} after parsing completes.
 */
public class BibRecordErrorListener extends BaseErrorListener {

    private final List<String> warnings = new ArrayList<>();

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer,
                            Object offendingSymbol,
                            int line, int charPositionInLine,
                            String msg,
                            RecognitionException e) {
        warnings.add("L" + line + ":" + charPositionInLine + " — " + msg);
    }

    /** Returns an unmodifiable view of all collected warnings. */
    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** {@code true} when no errors were recorded. */
    public boolean isClean() {
        return warnings.isEmpty();
    }
}
