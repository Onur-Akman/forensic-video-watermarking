package com.specter.extraction.model;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO representing the result of a watermark extraction operation.
 * Contains the extracted UUID and a statistical confidence score.
 */
public class ExtractionResult {

    private UUID extractedUuid;
    private double confidenceScore;
    private boolean valid;
    private int framesAnalyzed;
    private int framesWithWatermark;
    private long processingTimeMs;
    private LocalDateTime timestamp;
    private String message;

    public ExtractionResult() {}

    private ExtractionResult(Builder builder) {
        this.extractedUuid = builder.extractedUuid;
        this.confidenceScore = builder.confidenceScore;
        this.valid = builder.valid;
        this.framesAnalyzed = builder.framesAnalyzed;
        this.framesWithWatermark = builder.framesWithWatermark;
        this.processingTimeMs = builder.processingTimeMs;
        this.timestamp = builder.timestamp;
        this.message = builder.message;
    }

    public static Builder builder() { return new Builder(); }

    public UUID getExtractedUuid() { return extractedUuid; }
    public void setExtractedUuid(UUID extractedUuid) { this.extractedUuid = extractedUuid; }

    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; }

    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }

    public int getFramesAnalyzed() { return framesAnalyzed; }
    public void setFramesAnalyzed(int framesAnalyzed) { this.framesAnalyzed = framesAnalyzed; }

    public int getFramesWithWatermark() { return framesWithWatermark; }
    public void setFramesWithWatermark(int framesWithWatermark) { this.framesWithWatermark = framesWithWatermark; }

    public long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(long processingTimeMs) { this.processingTimeMs = processingTimeMs; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public static class Builder {
        private UUID extractedUuid;
        private double confidenceScore;
        private boolean valid;
        private int framesAnalyzed;
        private int framesWithWatermark;
        private long processingTimeMs;
        private LocalDateTime timestamp;
        private String message;

        public Builder extractedUuid(UUID val) { extractedUuid = val; return this; }
        public Builder confidenceScore(double val) { confidenceScore = val; return this; }
        public Builder valid(boolean val) { valid = val; return this; }
        public Builder framesAnalyzed(int val) { framesAnalyzed = val; return this; }
        public Builder framesWithWatermark(int val) { framesWithWatermark = val; return this; }
        public Builder processingTimeMs(long val) { processingTimeMs = val; return this; }
        public Builder timestamp(LocalDateTime val) { timestamp = val; return this; }
        public Builder message(String val) { message = val; return this; }
        public ExtractionResult build() { return new ExtractionResult(this); }
    }
}
