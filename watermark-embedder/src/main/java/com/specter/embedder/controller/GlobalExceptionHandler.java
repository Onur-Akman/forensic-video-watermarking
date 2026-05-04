package com.specter.embedder.controller;

import com.specter.embedder.dto.ErrorResponse;
import com.specter.embedder.exception.EmbedException;
import com.specter.embedder.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EmbedException.class)
    public ResponseEntity<ErrorResponse> handleEmbed(EmbedException ex) {
        if (ex.errorCode().httpStatus() >= 500) {
            log.error("Embed error {}", ex.errorCode(), ex);
        } else {
            log.warn("Embed error {}: {}", ex.errorCode(), ex.getMessage());
        }
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(ErrorResponse.of(ex.errorCode().name(), ex.getMessage(), MDC.get("requestId")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload too large: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.httpStatus())
                .body(ErrorResponse.of(ErrorCode.FILE_TOO_LARGE.name(),
                        "uploaded file exceeds the configured limit", MDC.get("requestId")));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleMissing(Exception ex) {
        return ResponseEntity.status(ErrorCode.MISSING_FIELD.httpStatus())
                .body(ErrorResponse.of(ErrorCode.MISSING_FIELD.name(), ex.getMessage(), MDC.get("requestId")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR.name(),
                        "internal error; check logs for correlation id", MDC.get("requestId")));
    }
}
