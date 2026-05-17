package com.homelibrary.server.service;

import java.util.Optional;

/**
 * Strategy interface for ISBN-based book metadata providers.
 * Implementations are tried in order of priority until one succeeds.
 */
public interface IsbnLookupService {

    /**
     * Look up book metadata by ISBN-10 or ISBN-13.
     *
     * @param isbn the ISBN string (digits only, no hyphens)
     * @return populated DTO if the provider found the book, empty otherwise
     */
    Optional<BookMetadataDto> lookup(String isbn);

    /** Human-readable provider name used in logs. */
    String providerName();

    /**
     * Lower value = tried first.
     * РГБ = 1, НЭБ = 2, Google Books = 3, Open Library = 4.
     */
    int priority();
}
