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

    private final BookRepository bookRepository;
    private final BookProcessingService bookProcessingService;

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
                    // 1. Save Book and Images immediately (Synchronous)
                    Book savedBook = saveBookInitial(imageBuffers);

                    // 2. Trigger Async Processing (OCR & Parsing)
                    String language = (metadata != null) ? metadata.getLanguage() : null;
                    bookProcessingService.processBookAsync(savedBook.getId(), language);

                    // 3. Return immediate response
                    UploadBookResponse.Builder response = UploadBookResponse.newBuilder()
                            .setSuccess(true)
                            .setBookId(savedBook.getId().toString())
                            .setTitle("") // Title will be populated later
                            .setIsbn("")
                            .setPublicationYear(0);

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
    protected Book saveBookInitial(Map<ImageType, ByteArrayOutputStream> imageBuffers) {
        Book book = new Book();

        for (Map.Entry<ImageType, ByteArrayOutputStream> entry : imageBuffers.entrySet()) {
            byte[] data = entry.getValue().toByteArray();
            ImageType type = entry.getKey();

            // Log image size when received and before storing
            log.info("[IMAGE_SIZE] Server received - Type: {}, Size: {} bytes", type, data.length);
            log.info("[IMAGE_SIZE] Before storing in DB - Type: {}, Size: {} bytes", type, data.length);

            // Save Image Entity
            Image image = new Image();
            image.setBook(book);
            image.setData(data);
            image.setType(Image.ImageType.valueOf(type.name())); // Map proto enum to domain enum
            book.getImages().add(image);
        }

        return bookRepository.save(book);
    }
}
