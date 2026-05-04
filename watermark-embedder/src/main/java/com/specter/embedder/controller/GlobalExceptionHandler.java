package com.specter.embedder.controller;

import com.specter.embedder.controller.support.RequestContext;
import com.specter.embedder.dto.ErrorResponse;
import com.specter.embedder.exception.EmbedException;
import com.specter.embedder.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Contract section 6.3 — tum hatalari ortak {@link ErrorResponse} formatina cevirir.
 * Her handler iki seyle ilgilenir: dogru log seviyesi + dogru ErrorCode mapping.
 * Response build'i {@link #respond(ErrorCode, String)} helper'inda toplanir.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EmbedException.class)
    public ResponseEntity<ErrorResponse> handleEmbed(EmbedException ex) {
        ErrorCode code = ex.errorCode();
        if (code.httpStatus() >= 500) {
            log.error("Embed error {}", code, ex);
        } else {
            log.warn("Embed error {}: {}", code, ex.getMessage());
        }
        return respond(code, ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("Upload too large: {}", ex.getMessage());
        return respond(ErrorCode.FILE_TOO_LARGE, "uploaded file exceeds the configured limit");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ErrorResponse> handleMissing(Exception ex) {
        return respond(ErrorCode.MISSING_FIELD, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled error", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "internal error; check logs for correlation id");
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus())
                .body(ErrorResponse.of(code.name(), message, RequestContext.currentRequestId()));
    }
}
