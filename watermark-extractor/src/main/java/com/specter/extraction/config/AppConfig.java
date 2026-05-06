package com.specter.extraction.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * General application configuration for the Extraction Service.
 *
 * Note: ffmpegPath has been removed — JavaCV bundles its own FFmpeg
 * native binaries and no longer requires a system-level ffmpeg binary.
 */
@Configuration
@ConfigurationProperties(prefix = "extraction")
public class AppConfig {

    /** Directory for temporary upload files (if any). */
    private String uploadDir = "./uploads";

    /** Maximum number of frames to decode and analyze per request. */
    private int maxFrames = 90;

    /** Maximum number of concurrent extraction tasks. Used by Semaphore in the service layer. */
    private int maxConcurrentTasks = 4;

    @PostConstruct
    public void init() throws IOException {
        Path uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
    }

    public String getUploadDir() { return uploadDir; }
    public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }

    public int getMaxFrames() { return maxFrames; }
    public void setMaxFrames(int maxFrames) { this.maxFrames = maxFrames; }

    public int getMaxConcurrentTasks() { return maxConcurrentTasks; }
    public void setMaxConcurrentTasks(int maxConcurrentTasks) { this.maxConcurrentTasks = maxConcurrentTasks; }
}
