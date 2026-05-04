package com.specter.embedder.exception;

/** Servis ic hatalarini tasiyan unchecked exception; GlobalExceptionHandler dogru HTTP status'a cevirir. */
public class EmbedException extends RuntimeException {

    private final ErrorCode errorCode;

    public EmbedException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EmbedException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
