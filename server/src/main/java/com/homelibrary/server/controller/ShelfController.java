package com.homelibrary.server.controller;

import com.homelibrary.server.domain.Shelf;
import com.homelibrary.server.repository.ShelfRepository;
import com.homelibrary.server.service.ImageProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@Controller
@Transactional
@Slf4j
public class ShelfController {

    @Autowired
    private ShelfRepository shelfRepository;

    @Autowired
    private ImageProcessingService imageProcessingService;

    private static final int WEB_DISPLAY_MAX_DIMENSION = 800;
    private static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;

    @GetMapping("/shelves/{id}")
    public String shelfDetail(@PathVariable UUID id, Model model) {
        try {
            Shelf shelf = shelfRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Shelf not found"));

            // Force initialization of books collection
            int bookCount = shelf.getBooks().size();

            log.info("Loading shelf detail for {}, name: {}, books: {}",
                id, shelf.getName(), bookCount);

            model.addAttribute("shelf", shelf);
            return "shelf-detail";
        } catch (Exception e) {
            log.error("Error loading shelf detail for {}", id, e);
            throw e;
        }
    }

    @GetMapping("/shelves/{id}/photo")
    public ResponseEntity<byte[]> getShelfPhoto(@PathVariable UUID id) {
        try {
            Shelf shelf = shelfRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("Shelf not found"));

            byte[] photoData = shelf.getPhoto();
            if (photoData == null || photoData.length == 0) {
                log.warn("No photo found for shelf {}", id);
                return ResponseEntity.notFound().build();
            }

            log.debug("Loading shelf photo for {}, size: {} bytes", id, photoData.length);

            if (photoData.length > MAX_IMAGE_SIZE) {
                log.warn("Shelf photo too large for shelf {}: {} bytes", id, photoData.length);
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
            }

            byte[] resizedImage = imageProcessingService.resizeForDisplay(
                photoData, WEB_DISPLAY_MAX_DIMENSION);
            return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(resizedImage);

        } catch (Exception e) {
            log.error("Error retrieving shelf photo for {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
