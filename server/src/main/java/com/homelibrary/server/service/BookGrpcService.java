package com.homelibrary.server.service;

import com.homelibrary.api.*;
import com.homelibrary.server.domain.Book;
import com.homelibrary.server.domain.Image;
import com.homelibrary.server.repository.BookRepository;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.transaction.annotation.Transactional;

@GrpcService
@Slf4j
@RequiredArgsConstructor
public class BookGrpcService extends BookServiceGrpc.BookServiceImplBase {

    private final BookRepository bookRepository;
    private final BookProcessingService bookProcessingService;

    @Override
    public void uploadBook(UploadBookRequest request, StreamObserver<UploadBookResponse> responseObserver) {
        try {
            log.info("[IMAGE_UPLOAD] Received upload request with {} images", request.getImagesCount());

            // 1. Save Book and Images immediately (Synchronous)
            Book savedBook = saveBookInitial(request);

            // 2. Trigger Async Processing (OCR & Parsing)
            String language = request.hasMetadata() ? request.getMetadata().getLanguage() : null;
            bookProcessingService.processBookAsync(savedBook.getId(), language);

            // 3. Return immediate response
            UploadBookResponse response = UploadBookResponse.newBuilder()
                    .setSuccess(true)
                    .setBookId(savedBook.getId().toString())
                    .setTitle("") // Title will be populated later
                    .setIsbn("")
                    .setPublicationYear(0)
                    .build();

            responseObserver.onNext(response);
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

    @Transactional
    protected Book saveBookInitial(UploadBookRequest request) {
        Book book = new Book();

        for (int i = 0; i < request.getImagesCount(); i++) {
            PageImage pageImage = request.getImages(i);
            byte[] data = pageImage.getData().toByteArray();

            // Log image size when received and before storing
            log.info("[IMAGE_SIZE] Server received - Type: {}, Index: {}, Size: {} bytes",
                    pageImage.getType(), i, data.length);
            log.info("[IMAGE_SIZE] Before storing in DB - Type: {}, Index: {}, Size: {} bytes",
                    pageImage.getType(), i, data.length);

            // Save Image Entity
            Image image = new Image();
            image.setBook(book);
            image.setData(data);
            image.setType(Image.ImageType.valueOf(pageImage.getType().name())); // Map proto enum to domain enum
            book.getImages().add(image);
        }

        log.info("[IMAGE_SAVE] Saved book with {} total images", book.getImages().size());
        return bookRepository.save(book);
    }
}
