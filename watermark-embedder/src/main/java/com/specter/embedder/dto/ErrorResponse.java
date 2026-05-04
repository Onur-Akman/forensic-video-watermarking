package com.specter.embedder.dto;

/** Contract section 6.3 - tum 4xx/5xx hatalarda donulen ortak govde. */
public record ErrorResponse(
        String status,
        String errorCode,
        String message,
        String requestId
) {
    public static ErrorResponse of(String errorCode, String message, String requestId) {
        return new ErrorResponse("error", errorCode, message, requestId);
    }
}
