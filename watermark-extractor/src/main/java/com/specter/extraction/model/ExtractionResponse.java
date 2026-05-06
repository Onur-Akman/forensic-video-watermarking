package com.specter.extraction.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Standard API response format defined in the contract §9.
 */
public class ExtractionResponse {
    
    @JsonProperty("status")
    private String status;

    @JsonProperty("data")
    private ResponseData data;

    @JsonProperty("error")
    private ErrorData error;

    public ExtractionResponse() {}

    public ExtractionResponse(String status, ResponseData data, ErrorData error) {
        this.status = status;
        this.data = data;
        this.error = error;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public ResponseData getData() { return data; }
    public void setData(ResponseData data) { this.data = data; }

    public ErrorData getError() { return error; }
    public void setError(ErrorData error) { this.error = error; }

    public static class ResponseData {
        @JsonProperty("watermark_id")
        private String watermarkId;

        @JsonProperty("auth_tag_valid")
        private boolean authTagValid;

        @JsonProperty("confidence_score")
        private double confidenceScore;

        @JsonProperty("alignment")
        private String alignment;

        @JsonProperty("processed_frames")
        private int processedFrames;

        @JsonProperty("extraction_time_ms")
        private long extractionTimeMs;

        public ResponseData() {}

        public ResponseData(String watermarkId, boolean authTagValid, double confidenceScore, 
                            String alignment, int processedFrames, long extractionTimeMs) {
            this.watermarkId = watermarkId;
            this.authTagValid = authTagValid;
            this.confidenceScore = confidenceScore;
            this.alignment = alignment;
            this.processedFrames = processedFrames;
            this.extractionTimeMs = extractionTimeMs;
        }

        public String getWatermarkId() { return watermarkId; }
        public void setWatermarkId(String watermarkId) { this.watermarkId = watermarkId; }

        public boolean isAuthTagValid() { return authTagValid; }
        public void setAuthTagValid(boolean authTagValid) { this.authTagValid = authTagValid; }

        public double getConfidenceScore() { return confidenceScore; }
        public void setConfidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; }

        public String getAlignment() { return alignment; }
        public void setAlignment(String alignment) { this.alignment = alignment; }

        public int getProcessedFrames() { return processedFrames; }
        public void setProcessedFrames(int processedFrames) { this.processedFrames = processedFrames; }

        public long getExtractionTimeMs() { return extractionTimeMs; }
        public void setExtractionTimeMs(long extractionTimeMs) { this.extractionTimeMs = extractionTimeMs; }
    }

    public static class ErrorData {
        @JsonProperty("code")
        private String code;

        @JsonProperty("message")
        private String message;

        public ErrorData() {}

        public ErrorData(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
