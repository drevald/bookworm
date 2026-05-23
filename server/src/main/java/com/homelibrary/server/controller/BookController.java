package com.homelibrary.server.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homelibrary.server.domain.Book;
import com.homelibrary.server.domain.Image;
import com.homelibrary.server.repository.BookRepository;
import com.homelibrary.server.service.BookProcessingService;
import com.homelibrary.server.service.BookProcessingStatusService;
import com.homelibrary.server.service.ImageProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
@Transactional
@Slf4j
public class BookController {

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private BookProcessingService bookProcessingService;

    @Autowired
    private BookProcessingStatusService bookProcessingStatusService;

    @Autowired
    private ImageProcessingService imageProcessingService;

    @Autowired
    private com.homelibrary.server.service.PythonOCRService pythonOCRService;

    @Autowired
    private ObjectMapper objectMapper;

    // Maximum dimension for web display (width or height) - 3x smaller
    private static final int WEB_DISPLAY_MAX_DIMENSION = 300;

    // Maximum image size to process (10MB)
    private static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;

    @GetMapping("/")
    public String listBooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String view,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            Model model
    ) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
            ? Sort.by(sortBy).ascending()
            : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<Book> bookPage = bookRepository.findAll(pageable);

        // Force initialization of images while in transaction (fixes LOB access error)
        bookPage.getContent().forEach(b -> b.getImages().size());

        model.addAttribute("books", bookPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", bookPage.getTotalPages());
        model.addAttribute("totalItems", bookPage.getTotalElements());
        model.addAttribute("pageSize", size);
        model.addAttribute("view", view != null ? view : "grid");
        model.addAttribute("sortBy", sortBy);
        model.addAttribute("sortDir", sortDir);
        model.addAttribute("ocrServiceDown", !pythonOCRService.isHealthy());

        return "books";
    }

    @GetMapping("/books/{id}")
    public String bookDetail(@PathVariable UUID id, Model model) {
        try {
            Book book = bookRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Book not found"));

            int imageCount = book.getImages().size();
            log.info("Loading book detail for {}, title: {}, images: {}",
                id, book.getTitle(), imageCount);

            model.addAttribute("book", book);

            // Prev / Next navigation (by createdAt; list is sorted DESC so
            // "previous in list" = newer = findNextBook, "next in list" = older = findPrevBook)
            bookRepository.findNextBook(book.getCreatedAt())
                    .ifPresent(b -> model.addAttribute("prevBookId", b.getId()));
            bookRepository.findPrevBook(book.getCreatedAt())
                    .ifPresent(b -> model.addAttribute("nextBookId", b.getId()));

            // Parse per-field source history for display
            if (book.getFieldSourcesJson() != null) {
                try {
                    Map<String, String> fieldSources = objectMapper.readValue(
                        book.getFieldSourcesJson(),
                        new TypeReference<Map<String, String>>() {});
                    model.addAttribute("fieldSources", fieldSources);
                } catch (Exception e) {
                    log.warn("Failed to parse fieldSourcesJson for book {}", id, e);
                }
            }

            return "book-detail";
        } catch (Exception e) {
            log.error("Error loading book detail for {}", id, e);
            throw e;
        }
    }

    @GetMapping("/api/books")
    @ResponseBody
    public List<Book> getAllBooks() {
        return bookRepository.findAll();
    }

    @GetMapping("/api/books/{id}")
    @ResponseBody
    public Book getBook(@PathVariable UUID id) {
        return bookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Book not found"));
    }

    @GetMapping("/api/books/{id}/raw-ocr")
    @ResponseBody
    public ResponseEntity<String> getRawOcr(@PathVariable UUID id) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Book not found"));
        String text = book.getRawOcrText();
        if (text == null || text.isBlank()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(text);
    }

    @GetMapping("/api/books/{id}/processing-status")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getProcessingStatus(@PathVariable UUID id) {
        BookProcessingStatusService.ProcessingStatus status = bookProcessingStatusService.get(id);
        Map<String, Object> result = new LinkedHashMap<>();
        if (status == null) {
            result.put("stage", "DONE");
            result.put("progress", 100);
            result.put("message", "Processing complete");
            result.put("active", false);
        } else {
            result.put("stage", status.getStage().name());
            result.put("progress", status.getProgressPercent());
            result.put("message", status.getMessage());
            result.put("active", status.isActive());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/images/{bookId}/cover")
    public ResponseEntity<byte[]> getCoverImage(@PathVariable UUID bookId) {
        try {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("Book not found"));

            Image coverImage = book.getImages().stream()
                    .filter(img -> img.getType() == Image.ImageType.COVER)
                    .findFirst()
                    .orElse(null);

            if (coverImage == null) {
                log.warn("No cover image found for book {}", bookId);
                return ResponseEntity.notFound().build();
            }

            byte[] imageData = coverImage.getData();
            if (imageData == null || imageData.length == 0) {
                log.warn("Cover image data is null or empty for book {}", bookId);
                return ResponseEntity.notFound().build();
            }

            log.debug("Loading cover image for book {}, size: {} bytes", bookId, imageData.length);

            if (imageData.length > MAX_IMAGE_SIZE) {
                log.warn("Cover image too large for book {}: {} bytes", bookId, imageData.length);
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
            }

            byte[] resizedImage = imageProcessingService.resizeForDisplay(
                imageData, WEB_DISPLAY_MAX_DIMENSION);
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(resizedImage);

        } catch (Exception e) {
            log.error("Error retrieving cover image for book {}", bookId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/images/{bookId}/{index}")
    public ResponseEntity<byte[]> getImage(@PathVariable UUID bookId, @PathVariable int index) {
        try {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("Book not found"));

            List<Image> images = book.getImages().stream().toList();
            if (index < 0 || index >= images.size()) {
                log.warn("Image index {} out of bounds for book {} (total images: {})",
                    index, bookId, images.size());
                return ResponseEntity.notFound().build();
            }

            Image image = images.get(index);
            byte[] imageData = image.getData();

            if (imageData == null || imageData.length == 0) {
                log.warn("Image data at index {} is null or empty for book {}", index, bookId);
                return ResponseEntity.notFound().build();
            }

            log.debug("Loading image {} for book {}, size: {} bytes", index, bookId, imageData.length);

            if (imageData.length > MAX_IMAGE_SIZE) {
                log.warn("Image at index {} too large for book {}: {} bytes", index, bookId, imageData.length);
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
            }

            byte[] resizedImage = imageProcessingService.resizeForDisplay(
                imageData, WEB_DISPLAY_MAX_DIMENSION);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(resizedImage);
        } catch (Exception e) {
            log.error("Error retrieving image at index {} for book {}", index, bookId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/images/{bookId}/{index}/preprocessed")
    public ResponseEntity<byte[]> getPreprocessedImage(@PathVariable UUID bookId, @PathVariable int index) {
        try {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("Book not found"));
            List<Image> images = book.getImages().stream().toList();
            if (index < 0 || index >= images.size()) return ResponseEntity.notFound().build();
            byte[] raw = images.get(index).getData();
            if (raw == null || raw.length == 0) return ResponseEntity.notFound().build();
            byte[] processed = pythonOCRService.preprocessImage(raw);
            byte[] resized   = imageProcessingService.resizeForDisplay(processed, WEB_DISPLAY_MAX_DIMENSION);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(resized);
        } catch (Exception e) {
            log.error("Error preprocessing image {} for book {}", index, bookId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/images/{bookId}/{index}/full")
    public ResponseEntity<byte[]> getFullImage(@PathVariable UUID bookId, @PathVariable int index) {
        try {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("Book not found"));

            List<Image> images = book.getImages().stream().toList();
            if (index < 0 || index >= images.size()) {
                return ResponseEntity.notFound().build();
            }

            Image image = images.get(index);
            byte[] imageData = image.getData();

            if (imageData == null || imageData.length == 0) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(imageData);
        } catch (Exception e) {
            log.error("Error retrieving full image at index {} for book {}", index, bookId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/books/{id}/reprocess")
    @ResponseBody
    public ResponseEntity<String> reprocessBook(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "rus") String language,
            @RequestParam(defaultValue = "auto") String source) {
        log.info("Reprocess request: book={}, language={}, source={}", id, language, source);
        bookRepository.findById(id).orElseThrow(() -> new RuntimeException("Book not found"));
        bookProcessingStatusService.update(id, BookProcessingStatusService.Stage.PENDING, 5, "Queued...");
        bookProcessingService.processBookAsync(id, language, source);
        return ResponseEntity.ok("Re-processing started for book: " + id);
    }

    @PostMapping("/books/{id}/clean-metadata")
    @ResponseBody
    public ResponseEntity<String> cleanBookMetadata(@PathVariable UUID id) {
        log.info("Clearing metadata for book {}", id);
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Book not found"));
        clearMetadata(book);
        bookRepository.save(book);
        return ResponseEntity.ok("Metadata cleared");
    }

    @PostMapping("/books/bulk-clean-metadata")
    public String bulkCleanMetadata(@RequestParam("ids") List<String> ids) {
        log.info("Bulk clearing metadata for {} books", ids.size());
        for (String id : ids) {
            try {
                UUID uuid = UUID.fromString(id);
                bookRepository.findById(uuid).ifPresent(book -> {
                    clearMetadata(book);
                    bookRepository.save(book);
                });
            } catch (IllegalArgumentException e) {
                log.error("Invalid UUID format: {}", id);
            }
        }
        return "redirect:/";
    }

    private void clearMetadata(Book book) {
        book.setTitle(null);
        book.setIsbn(null);
        book.setPublicationYear(null);
        book.setUdk(null);
        book.setBbk(null);
        book.setAnnotation(null);
        book.setMetadataSource(null);
        book.setFieldSourcesJson(null);
        book.getAuthors().clear();
        book.setPublisher(null);
    }

    @PostMapping("/books/{id}/delete")
    public String deleteBook(@PathVariable UUID id) {
        log.info("Deleting book with ID: {}", id);
        bookRepository.deleteById(id);
        log.info("Book deleted successfully, redirecting to books list");
        return "redirect:/";
    }

    @PostMapping("/books/bulk-delete")
    public String bulkDeleteBooks(@RequestParam("ids") List<String> ids) {
        log.info("Bulk deleting {} books", ids.size());
        for (String id : ids) {
            try {
                UUID uuid = UUID.fromString(id);
                bookRepository.deleteById(uuid);
                log.info("Deleted book with ID: {}", uuid);
            } catch (IllegalArgumentException e) {
                log.error("Invalid UUID format: {}", id);
            }
        }
        log.info("Bulk delete completed, redirecting to books list");
        return "redirect:/";
    }

    @PostMapping("/books/bulk-reprocess")
    public String bulkReprocessBooks(
            @RequestParam("ids") List<String> ids,
            @RequestParam(defaultValue = "rus") String language,
            @RequestParam(defaultValue = "auto") String source) {
        log.info("Batch reprocessing {} books with language={}, source={}", ids.size(), language, source);
        for (String id : ids) {
            try {
                UUID uuid = UUID.fromString(id);
                bookProcessingStatusService.update(uuid, BookProcessingStatusService.Stage.PENDING, 5, "Queued...");
                bookProcessingService.processBookAsync(uuid, language, source);
                log.info("Started reprocessing book with ID: {}", uuid);
            } catch (IllegalArgumentException e) {
                log.error("Invalid UUID format: {}", id);
            }
        }
        return "redirect:/";
    }

    @GetMapping("/books/{id}/edit")
    public String editBookForm(@PathVariable UUID id, Model model) {
        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Book not found"));
        model.addAttribute("book", book);
        return "book-edit";
    }

    @PostMapping("/books/{id}/edit")
    public String updateBook(
            @PathVariable UUID id,
            @RequestParam String title,
            @RequestParam String author,
            @RequestParam String publisher,
            @RequestParam(required = false) Integer year,
            @RequestParam String isbn,
            @RequestParam String udk,
            @RequestParam String bbk) {

        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Book not found"));

        book.setTitle(title);
        book.setPublicationYear(year);
        book.setIsbn(isbn.equals("unknown") || isbn.trim().isEmpty() ? null : isbn);
        book.setUdk(udk.equals("unknown") || udk.trim().isEmpty() ? null : udk);
        book.setBbk(bbk.equals("unknown") || bbk.trim().isEmpty() ? null : bbk);

        if (publisher.equals("unknown") || publisher.trim().isEmpty()) {
            book.setPublisher(null);
        } else {
            if (book.getPublisher() == null) {
                com.homelibrary.server.domain.Publisher pub = new com.homelibrary.server.domain.Publisher();
                pub.setName(publisher);
                book.setPublisher(pub);
            } else {
                book.getPublisher().setName(publisher);
            }
        }

        book.getAuthors().clear();
        if (!author.equals("unknown") && !author.trim().isEmpty()) {
            String[] authorNames = author.split(",");
            for (String authorName : authorNames) {
                com.homelibrary.server.domain.Author authorEntity = new com.homelibrary.server.domain.Author();
                authorEntity.setName(authorName.trim());
                book.getAuthors().add(authorEntity);
            }
        }

        // Manual edit clears source tracking
        book.setMetadataSource("Manual");
        book.setFieldSourcesJson(null);

        bookRepository.save(book);
        log.info("Updated book {}", id);

        return "redirect:/books/" + id;
    }
}
