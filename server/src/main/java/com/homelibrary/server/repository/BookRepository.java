package com.homelibrary.server.repository;

import com.homelibrary.server.domain.Book;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {
}
