package com.homelibrary.server.controller;

import com.homelibrary.server.domain.Book;
import com.homelibrary.server.domain.Image;
import com.homelibrary.server.repository.BookRepository;
import com.homelibrary.server.service.BookProcessingService;
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
    public String listBooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String view,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            Model model
    ) {
        // Build sort object based on parameters
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
    public ResponseEntity<String> reprocessBook(@PathVariable UUID id, @RequestParam(defaultValue = "rus") String language) {
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
            @RequestParam(defaultValue = "rus") String language) {
        log.info("Batch reprocessing {} books with language: {}", ids.size(), language);
        for (String id : ids) {
            try {
                UUID uuid = UUID.fromString(id);
                bookProcessingService.processBookAsync(uuid, language);
                log.info("Started reprocessing book with ID: {}", uuid);
            } catch (IllegalArgumentException e) {
                log.error("Invalid UUID format: {}", id);
            }
        }
        log.info("Batch reprocess initiated for {} books", ids.size());
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
        
        
        // Update simple fields
        book.setTitle(title);
        book.setPublicationYear(year);
        book.setIsbn(isbn.equals("unknown") || isbn.trim().isEmpty() ? null : isbn);
        book.setUdk(udk.equals("unknown") || udk.trim().isEmpty() ? null : udk);
        book.setBbk(bbk.equals("unknown") || bbk.trim().isEmpty() ? null : bbk);
        
        // Handle publisher
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
        
        // Handle authors - clear and create new
        book.getAuthors().clear();
        if (!author.equals("unknown") && !author.trim().isEmpty()) {
            String[] authorNames = author.split(",");
            for (String authorName : authorNames) {
                com.homelibrary.server.domain.Author authorEntity = new com.homelibrary.server.domain.Author();
                authorEntity.setName(authorName.trim());
                book.getAuthors().add(authorEntity);
            }
        }
        
        bookRepository.save(book);
        log.info("Updated book {}", id);
        
        return "redirect:/books/" + id;
    }
}
