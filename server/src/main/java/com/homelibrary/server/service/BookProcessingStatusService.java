package com.homelibrary.server.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks in-memory processing status for books currently being processed.
 * Used by the web UI to show progress bars via polling.
 */
@Service
public class BookProcessingStatusService {

    public enum Stage {
        PENDING, OCR_RUNNING, PROVIDER_LOOKUP, SAVING, DONE, FAILED
    }

    @Data
    public static class ProcessingStatus {
        private Stage stage;
        private int progressPercent;
        private String message;
        private Instant updatedAt;

        public boolean isActive() {
            return stage != Stage.DONE && stage != Stage.FAILED;
        }
    }

    private final ConcurrentHashMap<UUID, ProcessingStatus> statusMap = new ConcurrentHashMap<>();

    public void update(UUID bookId, Stage stage, int progress, String message) {
        ProcessingStatus status = new ProcessingStatus();
        status.setStage(stage);
        status.setProgressPercent(progress);
        status.setMessage(message);
        status.setUpdatedAt(Instant.now());
        statusMap.put(bookId, status);
    }

    public ProcessingStatus get(UUID bookId) {
        return statusMap.get(bookId);
    }

    public void remove(UUID bookId) {
        statusMap.remove(bookId);
    }
}
