package com.homelibrary.server.service;

import lombok.Data;

import java.util.List;

/**
 * Shared DTO for book metadata returned by ISBN lookup providers.
 */
@Data
public class BookMetadataDto {
    private String title;
    private List<String> authors;
    private String publisher;
    private Integer publicationYear;
    private String isbn;
    /** Provider-supplied description / annotation */
    private String description;
    /** Russian library UDK classification — from RSL catalog or OCR */
    private String udk;
    /** Russian library BBK classification — from RSL catalog or OCR */
    private String bbk;

    public boolean hasTitle() {
        return title != null && !title.isBlank() && !title.equals("unknown");
    }
}
