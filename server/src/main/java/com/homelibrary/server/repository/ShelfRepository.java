package com.homelibrary.server.repository;

import com.homelibrary.server.domain.Shelf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ShelfRepository extends JpaRepository<Shelf, UUID> {
}
