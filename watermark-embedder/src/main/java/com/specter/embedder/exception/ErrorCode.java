package com.specter.embedder.exception;

/** Contract section 6.3 - HTTP status -> error_code esleme tablosu. */
public enum ErrorCode {

    INVALID_WATERMARK_ID(400),
    INVALID_VIDEO(400),
    MISSING_FIELD(400),
    FILE_TOO_LARGE(413),
    UNSUPPORTED_MEDIA(415),
    PSNR_VIOLATION(422),
    INTERNAL_ERROR(500),
    KEY_UNAVAILABLE(503);

    private final int httpStatus;

    ErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
