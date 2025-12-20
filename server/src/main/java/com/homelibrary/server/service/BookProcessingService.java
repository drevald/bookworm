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

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookProcessingService {

    private final OCRService ocrService;
    private final BookParser bookParser;
    private final LLMMetadataExtractor llmMetadataExtractor;
    private final BookRepository bookRepository;
    private final PublisherRepository publisherRepository;
    private final AuthorRepository authorRepository;
    private final ImageProcessingService imageProcessingService;

    @Async
    @Transactional
    public void processBookAsync(UUID bookId, String language) {
        log.info("Starting async processing for book ID: {} with language: {}", bookId, language);
        try {
            Book book = bookRepository.findById(bookId)
                    .orElseThrow(() -> new RuntimeException("Book not found: " + bookId));

            String coverText = "";
            String backText = "";
            String infoText = "";

            // Perform OCR on all images
            for (Image image : book.getImages()) {
                try {
                    // Log image size after retrieving from DB
                    log.info("[IMAGE_SIZE] After retrieving from DB - Type: {}, Size: {} bytes", image.getType(), image.getData().length);

                    // Cropping disabled - processing original image

                    String text = ocrService.extractText(image.getData(), language);
                    log.info("OCR extracted from {} for book {}: \n{}", image.getType(), bookId, text);
                    switch (image.getType()) {
                        case COVER:
                            coverText = text;
                            break;
                        case BACK:
                            backText = text;
                            break;
                        case INFO_PAGE:
                            infoText = text;
                            break;
                    }
                } catch (Exception e) {
                    log.error("OCR failed for image type {} on book {}", image.getType(), bookId, e);
                }
            }

            // Parse Metadata
            // Try LLM extraction first (more intelligent, handles OCR errors)
            BookParser.ParsedBookData parsedData = llmMetadataExtractor.extractMetadata(coverText, backText, infoText);

            // Fallback to regex parsing if LLM fails
            if (parsedData == null) {
                log.info("LLM extraction failed or disabled, falling back to regex parsing");
                parsedData = bookParser.parse(coverText, backText, infoText);
            } else {
                log.info("Successfully extracted metadata via LLM");
            }

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

            bookRepository.save(book);
            log.info("Finished async processing for book ID: {}", bookId);

        } catch (Exception e) {
            log.error("Error during async book processing for ID: {}", bookId, e);
        }
    }
}
