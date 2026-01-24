package com.homelibrary.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    /**
     * Single-threaded executor for book processing.
     * Ensures books are processed one at a time in queue order.
     * Prevents overwhelming the Ollama LLM service with parallel requests.
     */
    @Bean(name = "bookProcessingExecutor")
    public Executor bookProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Single thread - process one book at a time
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);

        // Queue capacity - allow up to 100 books to be queued
        executor.setQueueCapacity(100);

        // Thread naming for easier debugging
        executor.setThreadNamePrefix("BookProcessor-");

        // Graceful shutdown - wait for current task to complete
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300); // 5 minutes max wait

        // Log rejected tasks (when queue is full)
        executor.setRejectedExecutionHandler((r, exec) -> {
            log.error("Book processing task rejected - queue is full (100 books). Please try again later.");
        });

        executor.initialize();
        log.info("Book processing executor initialized: single-threaded queue with capacity 100");

        return executor;
    }
}
