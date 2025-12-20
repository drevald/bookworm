package com.homelibrary.server.service;

import com.google.protobuf.ByteString;
import com.homelibrary.api.*;
import com.homelibrary.server.domain.Book;
import com.homelibrary.server.repository.BookRepository;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "TESSDATA_PREFIX", matches = ".*")
class BookGrpcServiceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private BookRepository bookRepository;
    
    @Autowired
    private PlatformTransactionManager transactionManager;

    private ManagedChannel channel;
    private BookServiceGrpc.BookServiceStub stub;

    @BeforeEach
    void setUp() {
        // Connect to the gRPC server. 
        // Note: In a real CI environment, we should ensure the port is free or use InProcessServer.
        // For this test on your local machine, we'll try connecting to localhost:9090 
        // assuming the test context starts the gRPC server there.
        channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()
                .build();
        stub = BookServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdown();
        }
    }

    @Test
    void uploadBook_WithCoverAndPage_ShouldProcessAsynchronously() throws InterruptedException, IOException {
        // Arrange
        CountDownLatch latch = new CountDownLatch(1);
        final String[] bookIdRef = new String[1];
        final Throwable[] errorRef = new Throwable[1];

        StreamObserver<UploadBookResponse> responseObserver = new StreamObserver<>() {
            @Override
            public void onNext(UploadBookResponse response) {
                if (response.getSuccess()) {
                    bookIdRef[0] = response.getBookId();
                } else {
                    errorRef[0] = new RuntimeException(response.getErrorMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                errorRef[0] = t;
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                latch.countDown();
            }
        };

        StreamObserver<UploadBookRequest> requestObserver = stub.uploadBook(responseObserver);

        // 1. Send Cover Image
        byte[] coverBytes = Files.readAllBytes(new ClassPathResource("cover.jpg").getFile().toPath());
        requestObserver.onNext(UploadBookRequest.newBuilder()
                .setImageChunk(ImageChunk.newBuilder()
                        .setType(ImageType.COVER)
                        .setData(ByteString.copyFrom(coverBytes))
                        .build())
                .build());

        // 2. Send Info Page Image
        byte[] pageBytes = Files.readAllBytes(new ClassPathResource("page.jpg").getFile().toPath());
        requestObserver.onNext(UploadBookRequest.newBuilder()
                .setImageChunk(ImageChunk.newBuilder()
                        .setType(ImageType.INFO_PAGE)
                        .setData(ByteString.copyFrom(pageBytes))
                        .build())
                .build());

        // 3. Complete Request
        requestObserver.onCompleted();

        // Wait for response
        boolean received = latch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Response timeout");
        assertNull(errorRef[0], "Upload failed: " + (errorRef[0] != null ? errorRef[0].getMessage() : ""));
        assertNotNull(bookIdRef[0], "Book ID should not be null");

        System.out.println("Upload successful. Book ID: " + bookIdRef[0]);

        // 4. Verify Async Processing
        // Wait for OCR to complete (poll database)
        UUID bookId = UUID.fromString(bookIdRef[0]);
        final Book[] bookRef = new Book[1];
        
        // Poll for up to 60 seconds
        for (int i = 0; i < 60; i++) {
            // Fetch in a new transaction to ensure we see latest committed data
            new TransactionTemplate(transactionManager).execute(status -> {
                Book b = bookRepository.findById(bookId).orElse(null);
                if (b != null && b.getTitle() != null && !b.getTitle().isEmpty()) {
                    bookRef[0] = b;
                }
                return null;
            });
            
            if (bookRef[0] != null) {
                System.out.println("Async processing completed after " + i + " seconds");
                break;
            }
            Thread.sleep(1000);
        }

        // Assertions after processing
        // Fetch final state within transaction to avoid LazyInitializationException
        new TransactionTemplate(transactionManager).execute(status -> {
            Book book = bookRepository.findById(bookId).orElse(null);
            assertNotNull(book, "Book should exist in DB");
            
            if (book.getTitle() == null || book.getTitle().isEmpty()) {
                System.out.println("Test failed. Book state: " + book);
            }
            assertNotNull(book.getTitle(), "Title should be populated after async processing");
            assertFalse(book.getTitle().isEmpty(), "Title should not be empty");
            
            System.out.println("Processed Book Title: " + book.getTitle());
            System.out.println("Processed Book ISBN: " + book.getIsbn());
            
            // Verify images were saved (accessing lazy collection inside transaction)
            assertEquals(2, book.getImages().size(), "Should have 2 images saved");
            return null;
        });
    }
}
