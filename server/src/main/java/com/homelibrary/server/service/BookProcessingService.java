package com.homelibrary.server.service;

import com.homelibrary.server.domain.Author;
import com.homelibrary.server.domain.Book;
import com.homelibrary.server.domain.Image;
import com.homelibrary.server.domain.Publisher;
import com.homelibrary.server.repository.AuthorRepository;
import com.homelibrary.server.repository.BookRepository;
import com.homelibrary.server.repository.PublisherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookProcessingService {

    private final PythonOCRService pythonOCRService;
    private final BookRepository bookRepository;
    private final PublisherRepository publisherRepository;
    private final AuthorRepository authorRepository;

    @Async
    @Transactional
    public void processBookAsync(UUID bookId, String language) {
        log.info("Starting async processing for book ID: {} with language: {}", bookId, language);
        try {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("Book not found: " + bookId));

            byte[] coverImage = null;
            byte[] backImage = null;
            List<byte[]> infoImages = new ArrayList<>();

            // Collect images by type
            for (Image image : book.getImages()) {
                switch (image.getType()) {
                    case COVER:
                        coverImage = image.getData();
                        break;
                    case BACK:
                        backImage = image.getData();
                        break;
                    case INFO_PAGE:
                        infoImages.add(image.getData());
                        break;
                }
            }

            log.info("Collected images for book {}: cover={}, info_pages={}, back={}",
                    bookId, coverImage != null, infoImages.size(), backImage != null);

            // Call Python OCR service (does OCR + regex + LLM in one call)
            log.info("Calling Python OCR service for book {}", bookId);
            PythonOCRService.ParsedBookData parsedData = pythonOCRService.extractMetadata(
                    coverImage, infoImages, backImage, language);

            if (parsedData == null) {
                log.error("Python OCR service failed for book {}", bookId);
                return;
            }

            log.info("Python OCR service returned metadata for book {}: title={}, author={}, isbn={}",
                    bookId, parsedData.getTitle(), parsedData.getAuthor(), parsedData.getIsbn());

            // Update Book
            if (parsedData.getTitle() != null && !parsedData.getTitle().isEmpty()) {
                book.setTitle(parsedData.getTitle());
            }
            if (parsedData.getIsbn() != null && !parsedData.getIsbn().isEmpty()) {
                book.setIsbn(parsedData.getIsbn());
            }
            if (parsedData.getPublicationYear() != null) {
                book.setPublicationYear(parsedData.getPublicationYear());
            }
            if (parsedData.getUdk() != null && !parsedData.getUdk().isEmpty() && !parsedData.getUdk().equals("unknown")) {
                book.setUdk(parsedData.getUdk());
            }
            if (parsedData.getBbk() != null && !parsedData.getBbk().isEmpty() && !parsedData.getBbk().equals("unknown")) {
                book.setBbk(parsedData.getBbk());
            }
            if (parsedData.getAnnotation() != null && !parsedData.getAnnotation().isEmpty() && !parsedData.getAnnotation().equals("unknown")) {
                book.setAnnotation(parsedData.getAnnotation());
            }

            // Handle Publisher
            if (parsedData.getPublisher() != null) {
                String pubName = parsedData.getPublisher();
                Publisher publisher = publisherRepository.findByName(pubName)
                        .orElseGet(() -> {
                            Publisher p = new Publisher(pubName);
                            return publisherRepository.save(p);
                        });
                book.setPublisher(publisher);
            }

            // Handle Authors
            if (parsedData.getAuthors() != null && !parsedData.getAuthors().isEmpty()) {
                book.getAuthors().clear(); // Clear existing authors
                for (String authorName : parsedData.getAuthors()) {
                    Author author = authorRepository.findByName(authorName)
                            .orElseGet(() -> {
                                Author a = new Author(authorName);
                                return authorRepository.save(a);
                            });
                    book.getAuthors().add(author);
                }
            }

            log.info("About to save book {} with title: '{}'", bookId, book.getTitle());
            bookRepository.save(book);
            log.info("Finished async processing for book ID: {}", bookId);

        } catch (Exception e) {
            log.error("Error during async book processing for ID: {}", bookId, e);
        }
    }
}
