package com.homelibrary.server.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "images")
@Data
@NoArgsConstructor
public class Image {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "book_id")
    private Book book;

    @Enumerated(EnumType.STRING)
    private ImageType type;

    // Storing image as byte array for simplicity as per requirements
    // In production, this should be a path to S3/File System
    @Lob
    private byte[] data;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum ImageType {
        COVER, BACK, INFO_PAGE
    }
}
