package com.specter.embedder.dto;

/** Contract section 6.1 / 6.2 - GET /api/v1/health gövdesi. */
public record HealthResponse(
        String status,
        String version,
        String contractVersion
) {
}
