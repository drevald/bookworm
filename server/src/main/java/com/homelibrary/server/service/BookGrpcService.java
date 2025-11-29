package com.homelibrary.server.service;

import com.google.protobuf.ByteString;
import com.homelibrary.api.*;
import com.homelibrary.server.domain.Author;
import com.homelibrary.server.domain.Book;
import com.homelibrary.server.domain.Image;
import com.homelibrary.server.domain.Publisher;
import com.homelibrary.server.repository.AuthorRepository;
import com.homelibrary.server.repository.BookRepository;
import com.homelibrary.server.repository.PublisherRepository;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@GrpcService
@Slf4j
@RequiredArgsConstructor
public class BookGrpcService extends BookServiceGrpc.BookServiceImplBase {

    private final OCRService ocrService;
    private final BookParser bookParser;
    private final BookRepository bookRepository;
    private final AuthorRepository authorRepository;
    private final PublisherRepository publisherRepository;

    @Override
    public StreamObserver<UploadBookRequest> uploadBook(StreamObserver<UploadBookResponse> responseObserver) {
        return new StreamObserver<>() {
            private final Map<ImageType, ByteArrayOutputStream> imageBuffers = new HashMap<>();
            private BookMetadata metadata;

            @Override
            public void onNext(UploadBookRequest request) {
                if (request.hasMetadata()) {
                    this.metadata = request.getMetadata();
                } else if (request.hasImageChunk()) {
                    ImageChunk chunk = request.getImageChunk();
                    imageBuffers.computeIfAbsent(chunk.getType(), k -> new ByteArrayOutputStream());
                    try {
                        chunk.getData().writeTo(imageBuffers.get(chunk.getType()));
                    } catch (IOException e) {
                        log.error("Error writing image chunk", e);
                        onError(e);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error("Upload failed", t);
            }

            @Override
            public void onCompleted() {
                try {
                    Book savedBook = processAndSaveBook(imageBuffers);
                    
                    UploadBookResponse.Builder response = UploadBookResponse.newBuilder()
                            .setSuccess(true)
                            .setBookId(savedBook.getId().toString())
                            .setTitle(savedBook.getTitle() != null ? savedBook.getTitle() : "")
                            .setIsbn(savedBook.getIsbn() != null ? savedBook.getIsbn() : "")
                            .setPublicationYear(savedBook.getPublicationYear() != null ? savedBook.getPublicationYear() : 0);
                            
                    if (savedBook.getPublisher() != null) {
                        response.setPublisher(savedBook.getPublisher().getName());
                    }
                    
                    responseObserver.onNext(response.build());
                    responseObserver.onCompleted();
                } catch (Exception e) {
                    log.error("Processing failed", e);
                    responseObserver.onNext(UploadBookResponse.newBuilder()
                            .setSuccess(false)
                            .setErrorMessage(e.getMessage())
                            .build());
                    responseObserver.onCompleted();
                }
            }
        };
    }

    @Transactional
    protected Book processAndSaveBook(Map<ImageType, ByteArrayOutputStream> imageBuffers) throws Exception {
        Book book = new Book();
        
        // 1. Save Images and Perform OCR
        String coverText = "";
        String backText = "";
        String infoText = "";

        for (Map.Entry<ImageType, ByteArrayOutputStream> entry : imageBuffers.entrySet()) {
            byte[] data = entry.getValue().toByteArray();
            ImageType type = entry.getKey();
            
            // Save Image Entity
            Image image = new Image();
            image.setBook(book);
            image.setData(data);
            image.setType(Image.ImageType.valueOf(type.name())); // Map proto enum to domain enum
            book.getImages().add(image);

            // OCR
            String text = ocrService.extractText(data);
            if (type == ImageType.COVER) coverText = text;
            else if (type == ImageType.BACK) backText = text;
            else if (type == ImageType.INFO_PAGE) infoText = text;
        }

        // 2. Parse Metadata
        BookParser.ParsedBookData parsedData = bookParser.parse(coverText, backText, infoText);
        
        book.setTitle(parsedData.getTitle());
        book.setIsbn(parsedData.getIsbn());
        book.setPublicationYear(parsedData.getPublicationYear());

        // 3. Handle Publisher
        if (parsedData.getPublisher() != null) {
            String pubName = parsedData.getPublisher();
            Publisher publisher = publisherRepository.findByName(pubName)
                    .orElseGet(() -> new Publisher(pubName));
            book.setPublisher(publisher);
        }

        // 4. Handle Authors (TODO: Parse authors)
        
        return bookRepository.save(book);
    }
}
