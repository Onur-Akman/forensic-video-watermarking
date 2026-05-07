package com.specter.extraction.exception;

/**
 * Exception thrown when video processing (decode, FFmpeg) fails.
 */
public class VideoProcessingException extends RuntimeException {

    public VideoProcessingException(String message) {
        super(message);
    }

    public VideoProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
