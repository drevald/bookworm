package com.homelibrary.server.controller;

import com.homelibrary.server.domain.Book;
import com.homelibrary.server.domain.Image;
import com.homelibrary.server.repository.BookRepository;
import com.homelibrary.server.service.BookProcessingService;
import com.homelibrary.server.service.ImageProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
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
    private ImageProcessingService imageProcessingService;

    // Maximum dimension for web display (width or height) - 3x smaller
    private static final int WEB_DISPLAY_MAX_DIMENSION = 300;

    // Maximum image size to process (10MB)
    private static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;

    @GetMapping("/")
    public String listBooks(Model model) {
        List<Book> books = bookRepository.findAll();
        // Force initialization of images while in transaction (fixes LOB access error)
        books.forEach(b -> b.getImages().size());
        model.addAttribute("books", books);
        return "books";
    }

    @GetMapping("/books/{id}")
    public String bookDetail(@PathVariable UUID id, Model model) {
        try {
            Book book = bookRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Book not found"));

            // Force initialization of images collection (but NOT the binary data)
            // to prevent LazyInitializationException
            int imageCount = book.getImages().size();

            log.info("Loading book detail for {}, title: {}, images: {}",
                id, book.getTitle(), imageCount);

            model.addAttribute("book", book);
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

            // Force load image data within transaction
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
            // Force load image data within transaction
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

            // Return ORIGINAL image without resizing
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
    public ResponseEntity<String> reprocessBook(@PathVariable UUID id, @RequestParam(defaultValue = "rus+eng") String language) {
        log.info("=== REPROCESS REQUEST ===");
        log.info("Book ID: {}", id);
        log.info("Language parameter received: '{}'", language);
        log.info("Language length: {}", language.length());
        log.info("Language bytes: {}", Arrays.toString(language.getBytes()));
        log.info("========================");

        Book book = bookRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Book not found"));

        bookProcessingService.processBookAsync(id, language);
        return ResponseEntity.ok("Re-processing started for book: " + id);
    }

    @PostMapping("/books/{id}/delete")
    public String deleteBook(@PathVariable UUID id) {
        log.info("Deleting book with ID: {}", id);
        bookRepository.deleteById(id);
        log.info("Book deleted successfully, redirecting to books list");
        return "redirect:/";
    }
}
