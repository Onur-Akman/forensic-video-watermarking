package com.specter.embedder.dto;

/** Contract section 6.1 - `POST /api/v1/embed` 200 OK gövdesi. */
public record EmbedResponse(
        String status,
        String requestId,
        String watermarkId,
        String outputPath,
        EmbedMetrics metrics
) {
    public static EmbedResponse success(String requestId, String watermarkId, String outputPath, EmbedMetrics metrics) {
        return new EmbedResponse("success", requestId, watermarkId, outputPath, metrics);
    }
}
