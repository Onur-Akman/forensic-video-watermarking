package com.specter.extraction.service;

import com.specter.extraction.model.ExtractionResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * Core service interface for watermark extraction.
 * Orchestrates the full extraction pipeline:
 * video decode → frame sync → DCT extraction → error correction → UUID reconstruction.
 */
public interface WatermarkExtractionService {

    /**
     * Extract the embedded watermark UUID from a video file.
     *
     * @param videoFile the uploaded suspicious video file
     * @param secretKey the shared secret key for watermark location (PRNG seed)
     * @return ExtractionResult containing the extracted UUID and confidence score
     */
    ExtractionResult extract(MultipartFile videoFile, String secretKey);
}
