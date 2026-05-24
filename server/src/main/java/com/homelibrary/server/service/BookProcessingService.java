package com.homelibrary.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelibrary.server.domain.Author;
import com.homelibrary.server.domain.Book;
import com.homelibrary.server.domain.Image;
import com.homelibrary.server.domain.Publisher;
import com.homelibrary.server.parser.BibRecord;
import com.homelibrary.server.parser.BibliographicParserService;
import com.homelibrary.server.repository.AuthorRepository;
import com.homelibrary.server.repository.BookRepository;
import com.homelibrary.server.repository.PublisherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookProcessingService {

    private final PythonOCRService pythonOCRService;
    private final BookRepository bookRepository;
    private final PublisherRepository publisherRepository;
    private final AuthorRepository authorRepository;
    private final List<IsbnLookupService> isbnProviders;
    private final BookProcessingStatusService statusService;
    private final ObjectMapper objectMapper;
    private final BibliographicParserService bibliographicParserService;

    /** Maps URL-safe source keys (from the UI) to provider display names. */
    private static final Map<String, String> SOURCE_KEY_MAP = Map.of(
            "rsl",             "РГБ",
            "neb",             "НЭБ",
            "amazon",          "Amazon",
            "amazon_scraper",  "Amazon (scraper)",
            "google",          "Google Books",
            "openlibrary",     "Open Library"
    );

    /** Source key that routes to the ANTLR4 GOST bibliographic parser. */
    private static final String SOURCE_GOST = "gost";
    /** Display name used in metadataSource / fieldSources. */
    public  static final String PROVIDER_GOST = "GOST";

    /**
     * Maps UI source keys for Python-side GOST parsers to:
     *   [0] = gost_parser value passed to Python OCR service
     *   [1] = display name shown in metadataSource / fieldSources
     */
    private static final Map<String, String[]> PYTHON_GOST_MAP = Map.of(
            "gost_2018", new String[]{"2018", "ГОСТ 2018"},
            "gost_2003", new String[]{"2003", "ГОСТ 2003"},
            "gost_84",   new String[]{"84",   "ГОСТ 84"},
            "gost_2008", new String[]{"2008", "ГОСТ 2008"}
    );

    private record ProviderLookupResult(BookMetadataDto dto, String providerName) {}

    @Async
    @Transactional
    public void processBookAsync(UUID bookId, String language, String source) {
        log.info("Starting async processing for book ID: {} with language: {}", bookId, language);
        statusService.update(bookId, BookProcessingStatusService.Stage.PENDING, 5, "Starting...");
        try {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("Book not found: " + bookId));

            // ── 1. Collect images by type ──────────────────────────────────────────
            byte[] coverImage = null;
            byte[] backImage = null;
            byte[] barcodeImage = null;
            List<byte[]> titleImages = new ArrayList<>();
            List<byte[]> infoImages = new ArrayList<>();

            for (Image image : book.getImages()) {
                switch (image.getType()) {
                    case COVER      -> coverImage = image.getData();
                    case BACK       -> backImage  = image.getData();
                    case TITLE_PAGE -> titleImages.add(image.getData());
                    case INFO_PAGE  -> infoImages.add(image.getData());
                    case BARCODE    -> barcodeImage = image.getData();
                }
            }

            log.info("Collected images for book {}: cover={}, title_pages={}, info_pages={}, back={}, barcode={}",
                    bookId, coverImage != null, titleImages.size(), infoImages.size(), backImage != null, barcodeImage != null);

            // ── 2. Call Python OCR service ─────────────────────────────────────────
            statusService.update(bookId, BookProcessingStatusService.Stage.OCR_RUNNING, 15, "Running OCR...");
            String[] pythonGost = source != null ? PYTHON_GOST_MAP.get(source.toLowerCase()) : null;
            String pythonGostParser = pythonGost != null ? pythonGost[0] : null;
            PythonOCRService.ParsedBookData ocrData = pythonOCRService.extractMetadata(
                    coverImage, titleImages, infoImages, backImage, barcodeImage, language, pythonGostParser);

            if (ocrData == null) {
                log.error("Python OCR service failed for book {}", bookId);
                statusService.update(bookId, BookProcessingStatusService.Stage.FAILED, 0, "OCR service failed");
                return;
            }

            statusService.update(bookId, BookProcessingStatusService.Stage.OCR_RUNNING, 45, "OCR complete");

            // ── 2b. Generate and store preprocessing stage images ──────────────────
            // Remove any previously stored stages, then regenerate for each INFO_PAGE.
            Set<Image.ImageType> stageTypes = EnumSet.of(
                    Image.ImageType.INFO_PAGE_PERSPECTIVE,
                    Image.ImageType.INFO_PAGE_DEWARPED);
            book.getImages().removeIf(img -> stageTypes.contains(img.getType()));

            for (Image img : book.getImages().stream()
                    .filter(i -> i.getType() == Image.ImageType.INFO_PAGE)
                    .toList()) {
                Map<String, byte[]> stages = pythonOCRService.preprocessImageStages(img.getData());
                addStageImage(book, stages.get("perspective"), Image.ImageType.INFO_PAGE_PERSPECTIVE);
                addStageImage(book, stages.get("dewarped"),    Image.ImageType.INFO_PAGE_DEWARPED);
            }
            log.info("Preprocessing stages generated for book {}", bookId);

            // ── 3. Store barcode value ─────────────────────────────────────────────
            String barcodeValue = ocrData.getBarcodeValue();
            if (barcodeValue != null && !barcodeValue.isBlank()) {
                book.setBarcodeValue(barcodeValue);
                log.info("Barcode ISBN for book {}: {}", bookId, barcodeValue);
            }

            // ── 4. ISBN provider lookup (primary source) ───────────────────────────
            String isbnForLookup = (barcodeValue != null && !barcodeValue.isBlank())
                    ? barcodeValue
                    : ocrData.getIsbn();
            if (isbnForLookup != null && !isbnForLookup.isBlank() && !isbnForLookup.equals(barcodeValue)) {
                log.info("No barcode for book {}, using OCR-extracted ISBN for lookup: {}", bookId, isbnForLookup);
            }

            // "Barcode" if barcode was detected, otherwise "OCR" (ISBN came from page text)
            String isbnSource = (barcodeValue != null && !barcodeValue.isBlank()) ? "Barcode" : "OCR";

            Optional<ProviderLookupResult> providerResult = Optional.empty();
            boolean forceOcr      = "ocr".equalsIgnoreCase(source);
            boolean forceGost     = SOURCE_GOST.equalsIgnoreCase(source);
            boolean forcePyGost   = pythonGost != null;
            // Python-GOST sources act like forceOcr: skip ISBN lookup, use OCR result directly
            if (forcePyGost) forceOcr = true;

            if (!forceOcr && !forceGost && isValidIsbn(isbnForLookup)) {
                statusService.update(bookId, BookProcessingStatusService.Stage.PROVIDER_LOOKUP, 55, "Looking up metadata...");
                if (source == null || "auto".equalsIgnoreCase(source)) {
                    providerResult = tryIsbnProviders(isbnForLookup, language);
                } else {
                    providerResult = trySpecificProvider(isbnForLookup, source);
                }
            } else if (!forceOcr && !forceGost) {
                log.info("Skipping ISBN lookup for book {} — no valid ISBN (value='{}')", bookId, isbnForLookup);
            }

            // ── 5. Resolve final metadata ──────────────────────────────────────────
            statusService.update(bookId, BookProcessingStatusService.Stage.SAVING, 80, "Applying metadata...");
            Map<String, String> fieldSources;
            boolean gostWasPrimary = false;

            if (forceGost && ocrData.getRawOcrText() != null && !ocrData.getRawOcrText().isBlank()) {
                // GOST parser as explicit primary source
                log.info("Using GOST bibliographic parser as primary source for book {}", bookId);
                statusService.update(bookId, BookProcessingStatusService.Stage.PROVIDER_LOOKUP, 55, "Parsing GOST record...");
                BibRecord bib = bibliographicParserService.parse(ocrData.getRawOcrText());
                log.info("GOST parse: title='{}', confidence={}, dialect={}",
                        bib.getTitle(), bib.getConfidence(), bib.getDialect());
                fieldSources = applyGostData(book, bib, isbnSource);
                book.setMetadataSource(PROVIDER_GOST);
                gostWasPrimary = true;
            } else if (providerResult.isPresent()) {
                String providerName = providerResult.get().providerName();
                log.info("Using provider '{}' as primary source for book {}", providerName, bookId);
                fieldSources = applyProviderData(book, providerResult.get().dto(), ocrData, providerName, isbnSource, language);
                book.setMetadataSource(providerName);
            } else {
                log.info("Using OCR data for book {} (source={})", bookId, forceOcr ? "ocr (forced)" : "auto (fallback)");
                fieldSources = applyOcrData(book, ocrData, isbnSource);
                book.setMetadataSource(forcePyGost ? pythonGost[1] : "OCR");
            }

            // ── 6. Store raw OCR text ──────────────────────────────────────────────
            if (ocrData.getRawOcrText() != null && !ocrData.getRawOcrText().isBlank()) {
                book.setRawOcrText(ocrData.getRawOcrText());
            }

            // ── 6b. Supplementary ANTLR4 bibliographic parse (fallback only) ────────
            // Skipped when GOST was the primary source — it already ran above.
            if (!gostWasPrimary && ocrData.getRawOcrText() != null && !ocrData.getRawOcrText().isBlank()) {
                try {
                    BibRecord bib = bibliographicParserService.parse(ocrData.getRawOcrText());
                    log.debug("ANTLR bib parse (fallback): title='{}', confidence={}, warnings={}",
                            bib.getTitle(), bib.getConfidence(), bib.getParseWarnings().size());
                    applyBibRecordFallback(book, bib);
                } catch (Exception bibEx) {
                    log.warn("ANTLR bibliographic parser failed for book {} — skipping", bookId, bibEx);
                }
            }

            // ── 7. Persist field sources ───────────────────────────────────────────
            try {
                book.setFieldSourcesJson(objectMapper.writeValueAsString(fieldSources));
            } catch (Exception e) {
                log.warn("Failed to serialize field sources for book {}", bookId, e);
            }

            bookRepository.save(book);
            statusService.update(bookId, BookProcessingStatusService.Stage.DONE, 100, "Done");
            log.info("Finished processing book ID: {}", bookId);

        } catch (Exception e) {
            log.error("Error during async book processing for ID: {}", bookId, e);
            statusService.update(bookId, BookProcessingStatusService.Stage.FAILED, 0, "Processing failed: " + e.getMessage());
        }
    }

    // ── Public helpers ─────────────────────────────────────────────────────────

    /**
     * Run the full provider chain for a given ISBN-10 or ISBN-13 and return
     * the best result, or empty when no provider has the book.
     * Used by the ISBN-preview endpoint for foreign-book lookup without OCR.
     */
    public Optional<BookMetadataDto> lookupByIsbn(String isbn, String language) {
        if (!isValidIsbn(isbn)) return Optional.empty();
        return tryIsbnProviders(isbn, language).map(ProviderLookupResult::dto);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private Optional<ProviderLookupResult> tryIsbnProviders(String isbn, String language) {
        List<IsbnLookupService> sorted = isbnProviders.stream()
                .filter(p -> p.supports(language))
                .sorted(Comparator.comparingInt(IsbnLookupService::priority))
                .toList();

        log.info("Provider chain for language='{}': {}",
                language, sorted.stream().map(IsbnLookupService::providerName).toList());

        for (IsbnLookupService provider : sorted) {
            log.info("Trying ISBN provider '{}' for ISBN {}", provider.providerName(), isbn);
            Optional<BookMetadataDto> result = provider.lookup(isbn);
            if (result.isPresent() && result.get().hasTitle()) {
                log.info("ISBN provider '{}' returned data for ISBN {}", provider.providerName(), isbn);
                return Optional.of(new ProviderLookupResult(result.get(), provider.providerName()));
            }
        }
        log.info("All ISBN providers failed for ISBN {}, will use OCR fallback", isbn);
        return Optional.empty();
    }

    private Optional<ProviderLookupResult> trySpecificProvider(String isbn, String sourceKey) {
        String targetName = SOURCE_KEY_MAP.get(sourceKey.toLowerCase());
        if (targetName == null) {
            log.warn("Unknown source key '{}', falling back to auto", sourceKey);
            return tryIsbnProviders(isbn, null);
        }
        return isbnProviders.stream()
                .filter(p -> p.providerName().equals(targetName))
                .findFirst()
                .flatMap(provider -> {
                    log.info("Trying specific provider '{}' for ISBN {}", provider.providerName(), isbn);
                    Optional<BookMetadataDto> result = provider.lookup(isbn);
                    if (result.isPresent() && result.get().hasTitle()) {
                        log.info("Provider '{}' returned data for ISBN {}", provider.providerName(), isbn);
                        return Optional.of(new ProviderLookupResult(result.get(), provider.providerName()));
                    }
                    log.info("Provider '{}' found no data for ISBN {}", provider.providerName(), isbn);
                    return Optional.empty();
                });
    }

    /**
     * Apply provider data as primary, fill udk/bbk/annotation from OCR where missing.
     * Returns a map of field name → source name for history tracking.
     *
     * <p>For Cyrillic-script books (language="rus"), provider text fields (title,
     * authors, publisher, annotation) are only used when they actually contain
     * Cyrillic characters. If a provider returns transliterated/English data for a
     * Russian book (e.g. Open Library), we fall back to OCR for those fields so
     * the user sees the native-language metadata.</p>
     */
    private Map<String, String> applyProviderData(Book book, BookMetadataDto provider,
                                                   PythonOCRService.ParsedBookData ocr,
                                                   String providerName, String isbnSource,
                                                   String language) {
        Map<String, String> sources = new LinkedHashMap<>();
        boolean requireCyrillic = "rus".equalsIgnoreCase(language);

        // Title: provider wins only if it has Cyrillic text (for Russian books), fall back to OCR
        String providerTitle = (requireCyrillic && !hasCyrillicText(provider.getTitle())) ? null : provider.getTitle();
        if (providerTitle != null && !providerTitle.equals(provider.getTitle())) {
            log.info("Provider title '{}' lacks Cyrillic — using OCR title instead", provider.getTitle());
        }
        if (setIfPresent(book::setTitle, providerTitle)) sources.put("title", providerName);
        else if (setIfPresent(book::setTitle, ocr.getTitle())) sources.put("title", "OCR");

        // ISBN: prefer provider isbn, fall back to OCR isbn (language-neutral)
        String isbn = provider.getIsbn() != null ? provider.getIsbn() : ocr.getIsbn();
        if (setIfPresent(book::setIsbn, isbn)) sources.put("isbn", isbnSource);

        // Publication year: provider wins, fall back to OCR (language-neutral)
        if (provider.getPublicationYear() != null) {
            book.setPublicationYear(provider.getPublicationYear());
            sources.put("year", providerName);
        } else if (ocr.getPublicationYear() != null) {
            book.setPublicationYear(ocr.getPublicationYear());
            sources.put("year", "OCR");
        }

        // UDK / BBK: prefer provider (RSL has these), fall back to OCR
        if (setIfMeaningful(book::setUdk, provider.getUdk())) sources.put("udk", providerName);
        else if (setIfMeaningful(book::setUdk, ocr.getUdk())) sources.put("udk", "OCR");
        if (setIfMeaningful(book::setBbk, provider.getBbk())) sources.put("bbk", providerName);
        else if (setIfMeaningful(book::setBbk, ocr.getBbk())) sources.put("bbk", "OCR");

        // Annotation: prefer provider description if it has Cyrillic (for Russian books), fall back to OCR
        String providerDesc = (requireCyrillic && !hasCyrillicText(provider.getDescription())) ? null : provider.getDescription();
        String annotation = providerDesc != null ? providerDesc : ocr.getAnnotation();
        if (setIfMeaningful(book::setAnnotation, annotation)) {
            sources.put("annotation", providerDesc != null ? providerName : "OCR");
        }

        // Publisher: provider wins if it has Cyrillic (for Russian books), fall back to OCR
        String providerPub = (requireCyrillic && !hasCyrillicText(provider.getPublisher())) ? null : provider.getPublisher();
        String pubName = providerPub != null ? providerPub : ocr.getPublisher();
        if (applyPublisher(book, pubName)) {
            sources.put("publisher", providerPub != null ? providerName : "OCR");
        }

        // Authors: provider wins if they have Cyrillic text (for Russian books), fall back to OCR
        boolean hasProviderAuthors = provider.getAuthors() != null && !provider.getAuthors().isEmpty();
        boolean providerAuthorsAreCyrillic = hasProviderAuthors &&
                provider.getAuthors().stream().anyMatch(this::hasCyrillicText);
        List<String> authorNames;
        boolean useProviderAuthors;
        if (hasProviderAuthors && (!requireCyrillic || providerAuthorsAreCyrillic)) {
            authorNames = provider.getAuthors();
            useProviderAuthors = true;
        } else {
            if (hasProviderAuthors) {
                log.info("Provider authors {} lack Cyrillic — using OCR authors instead", provider.getAuthors());
            }
            authorNames = new ArrayList<>(ocr.getAuthors());
            useProviderAuthors = false;
        }
        if (applyAuthors(book, authorNames)) {
            sources.put("authors", useProviderAuthors ? providerName : "OCR");
        }

        return sources;
    }

    /** Returns true if the string contains at least one Cyrillic character. */
    private boolean hasCyrillicText(String s) {
        if (s == null || s.isBlank()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) return true;
        }
        return false;
    }

    private Map<String, String> applyOcrData(Book book, PythonOCRService.ParsedBookData ocr, String isbnSource) {
        Map<String, String> sources = new LinkedHashMap<>();

        if (setIfPresent(book::setTitle, ocr.getTitle())) sources.put("title", "OCR");
        if (setIfPresent(book::setIsbn, ocr.getIsbn())) sources.put("isbn", isbnSource);
        if (ocr.getPublicationYear() != null) {
            book.setPublicationYear(ocr.getPublicationYear());
            sources.put("year", "OCR");
        }
        if (setIfMeaningful(book::setUdk, ocr.getUdk())) sources.put("udk", "OCR");
        if (setIfMeaningful(book::setBbk, ocr.getBbk())) sources.put("bbk", "OCR");
        if (setIfMeaningful(book::setAnnotation, ocr.getAnnotation())) sources.put("annotation", "OCR");
        if (applyPublisher(book, ocr.getPublisher())) sources.put("publisher", "OCR");
        if (applyAuthors(book, new ArrayList<>(ocr.getAuthors()))) sources.put("authors", "OCR");

        return sources;
    }

    /**
     * Applies a {@link BibRecord} produced by the GOST parser as the
     * <em>primary</em> metadata source (i.e. all present fields are written;
     * nothing is treated as a fallback).
     *
     * <p>Fields not extracted by the parser are simply left unset; they may
     * still be filled later by the supplementary OCR data or manual edits.
     */
    private Map<String, String> applyGostData(Book book, BibRecord bib, String isbnSource) {
        Map<String, String> sources = new LinkedHashMap<>();

        if (setIfPresent(book::setTitle, bib.getTitle()))               sources.put("title",      PROVIDER_GOST);
        if (bib.getYear() != null) {
            book.setPublicationYear(bib.getYear());                                                 sources.put("year",       PROVIDER_GOST);
        }
        // ISBN: prefer barcode (already stored), then GOST-parsed ISBN
        if (book.getIsbn() == null || book.getIsbn().isBlank()) {
            if (setIfPresent(book::setIsbn, bib.getIsbn()))             sources.put("isbn",       isbnSource);
        }
        if (setIfMeaningful(book::setUdk, bib.getUdk()))                sources.put("udk",        PROVIDER_GOST);
        if (setIfMeaningful(book::setBbk, bib.getBbk()))                sources.put("bbk",        PROVIDER_GOST);
        if (setIfMeaningful(book::setAnnotation, bib.getAnnotation()))  sources.put("annotation", PROVIDER_GOST);
        if (applyPublisher(book, bib.getPublisher()))                   sources.put("publisher",  PROVIDER_GOST);

        // Authors: prefer heading authors, then responsibility-statement authors
        List<String> authors = bib.getAuthors() != null && !bib.getAuthors().isEmpty()
                ? new ArrayList<>(bib.getAuthors())
                : (bib.getEditors() != null ? new ArrayList<>(bib.getEditors()) : List.of());
        if (applyAuthors(book, authors))                                sources.put("authors",    PROVIDER_GOST);

        return sources;
    }

    private boolean applyPublisher(Book book, String pubName) {
        if (pubName == null || pubName.isBlank() || pubName.equals("unknown")) return false;
        Publisher publisher = publisherRepository.findByName(pubName)
                .orElseGet(() -> publisherRepository.save(new Publisher(pubName)));
        book.setPublisher(publisher);
        return true;
    }

    private boolean applyAuthors(Book book, List<String> names) {
        if (names == null || names.isEmpty()) return false;
        List<String> meaningful = names.stream()
                .filter(n -> n != null && !n.isBlank() && !n.equals("unknown"))
                .toList();
        if (meaningful.isEmpty()) return false;
        book.getAuthors().clear();
        for (String name : meaningful) {
            Author author = authorRepository.findByName(name)
                    .orElseGet(() -> authorRepository.save(new Author(name)));
            book.getAuthors().add(author);
        }
        return true;
    }

    /**
     * Fills blank fields on {@code book} from a {@link BibRecord} produced by
     * the ANTLR4 parser. Only sets a field when the book's current value is
     * null/blank — it never overwrites data that was already populated by the
     * provider lookup or the primary OCR pass.
     */
    private void applyBibRecordFallback(Book book, BibRecord bib) {
        if (bib == null || bib.getConfidence() == 0) return;

        if (book.getTitle() == null || book.getTitle().isBlank()) {
            setIfPresent(book::setTitle, bib.getTitle());
        }
        if (book.getIsbn() == null || book.getIsbn().isBlank()) {
            setIfPresent(book::setIsbn, bib.getIsbn());
        }
        if (book.getPublicationYear() == null && bib.getYear() != null) {
            book.setPublicationYear(bib.getYear());
        }
        if (book.getUdk() == null || book.getUdk().isBlank()) {
            setIfMeaningful(book::setUdk, bib.getUdk());
        }
        if (book.getBbk() == null || book.getBbk().isBlank()) {
            setIfMeaningful(book::setBbk, bib.getBbk());
        }
        if (book.getAnnotation() == null || book.getAnnotation().isBlank()) {
            setIfMeaningful(book::setAnnotation, bib.getAnnotation());
        }
        if (book.getPublisher() == null) {
            applyPublisher(book, bib.getPublisher());
        }
        if (book.getAuthors().isEmpty() && bib.getAuthors() != null && !bib.getAuthors().isEmpty()) {
            applyAuthors(book, new ArrayList<>(bib.getAuthors()));
        }
    }

    private void addStageImage(Book book, byte[] data, Image.ImageType type) {
        if (data == null || data.length == 0) return;
        Image img = new Image();
        img.setBook(book);
        img.setType(type);
        img.setData(data);
        book.getImages().add(img);
    }

    private boolean setIfPresent(Consumer<String> setter, String value) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
            return true;
        }
        return false;
    }

    private boolean setIfMeaningful(Consumer<String> setter, String value) {
        if (value != null && !value.isBlank() && !value.equals("unknown")) {
            setter.accept(value);
            return true;
        }
        return false;
    }

    /**
     * Returns true only for a structurally valid ISBN-10 or ISBN-13
     * (correct length + correct check digit). Rejects null, blank, "unknown",
     * and any digit string that fails the checksum — e.g. BBK/UDK codes
     * that happen to be 13 characters long.
     */
    static boolean isValidIsbn(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("unknown")) return false;
        String digits = raw.replaceAll("[^0-9Xx]", "").toUpperCase();
        if (digits.length() == 13) return isValidIsbn13(digits);
        if (digits.length() == 10) return isValidIsbn10(digits);
        return false;
    }

    private static boolean isValidIsbn13(String digits) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = digits.charAt(i) - '0';
            sum += (i % 2 == 0) ? d : d * 3;
        }
        int check = (10 - (sum % 10)) % 10;
        return check == (digits.charAt(12) - '0');
    }

    private static boolean isValidIsbn10(String digits) {
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (digits.charAt(i) - '0') * (10 - i);
        }
        char last = digits.charAt(9);
        sum += (last == 'X') ? 10 : (last - '0');
        return sum % 11 == 0;
    }
}
