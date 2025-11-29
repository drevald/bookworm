package com.homelibrary.server.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BookParser {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    // Simple ISBN-10 or ISBN-13 regex
    private static final Pattern ISBN_PATTERN = Pattern.compile("\\b(?:97[89][- ]?)?(?:[0-9][- ]?){9}[0-9X]\\b");

    public ParsedBookData parse(String coverText, String backText, String infoText) {
        ParsedBookData data = new ParsedBookData();
        
        // Title usually on cover, take the first non-empty line or longest line?
        // Simple heuristic: First line of cover text
        if (coverText != null && !coverText.isBlank()) {
            data.setTitle(coverText.lines().findFirst().orElse("Unknown Title").trim());
        }

        // ISBN usually on back or info
        String combinedForIsbn = (backText + "\n" + infoText);
        Matcher isbnMatcher = ISBN_PATTERN.matcher(combinedForIsbn);
        if (isbnMatcher.find()) {
            data.setIsbn(isbnMatcher.group());
        }

        // Year usually on info page
        if (infoText != null) {
            Matcher yearMatcher = YEAR_PATTERN.matcher(infoText);
            if (yearMatcher.find()) {
                data.setPublicationYear(Integer.parseInt(yearMatcher.group()));
            }
        }

        // Publisher - naive implementation, would need a dictionary or NER
        // For now, leave empty or try to find a line with "Publisher"
        
        return data;
    }

    @lombok.Data
    public static class ParsedBookData {
        private String title;
        private String isbn;
        private Integer publicationYear;
        private String publisher;
        private List<String> authors = new ArrayList<>();
    }
}
