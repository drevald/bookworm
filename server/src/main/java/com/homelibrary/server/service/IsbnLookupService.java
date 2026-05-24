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
     * Russian:     РГБ = 1, НЭБ = 2.
     * Non-Russian: Amazon PA API = 1, Amazon Scraper = 2, Google Books = 3, Open Library = 4.
     */
    int priority();

    /**
     * Whether this provider should be tried for the given book language.
     *
     * <p>Russian catalog providers (РГБ, НЭБ) only handle Russian-language books.
     * Foreign providers (Amazon, Google Books, Open Library) are used for
     * everything else.  Returning {@code true} from the default means any new
     * provider that doesn't override this is tried for all languages.</p>
     *
     * @param language ISO language code from the UI ("rus", "eng", …) or null
     */
    default boolean supports(String language) { return true; }
}
