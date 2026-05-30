package com.homelibrary.server.repository;

import com.homelibrary.server.domain.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface BookRepository extends JpaRepository<Book, UUID> {

    /** The book added just before this one (older, next in DESC list). */
    @Query("SELECT b FROM Book b WHERE b.createdAt < :createdAt ORDER BY b.createdAt DESC LIMIT 1")
    Optional<Book> findPrevBook(@Param("createdAt") LocalDateTime createdAt);

    /** The book added just after this one (newer, previous in DESC list). */
    @Query("SELECT b FROM Book b WHERE b.createdAt > :createdAt ORDER BY b.createdAt ASC LIMIT 1")
    Optional<Book> findNextBook(@Param("createdAt") LocalDateTime createdAt);

    /** All books belonging to a specific group. */
    @Query("SELECT b FROM Book b JOIN b.groups g WHERE g.id = :groupId")
    Page<Book> findByGroupId(@Param("groupId") UUID groupId, Pageable pageable);
}
