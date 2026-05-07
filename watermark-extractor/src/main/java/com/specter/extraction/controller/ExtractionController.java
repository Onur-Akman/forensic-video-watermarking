package com.specter.extraction.controller;

import com.specter.extraction.exception.ExtractionException;
import com.specter.extraction.model.ExtractionResponse;
import com.specter.extraction.model.ExtractionResult;
import com.specter.extraction.service.WatermarkExtractionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for watermark extraction operations.
 * Contract §9: Exposed on /api/v1/extract (POST).
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class ExtractionController {

    private static final Logger log = LoggerFactory.getLogger(ExtractionController.class);

    private final WatermarkExtractionService extractionService;

    public ExtractionController(WatermarkExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    /**
     * Extract watermark from an uploaded video file.
     * Contract §9: Field name is "file".
     */
    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ExtractionResponse> extractWatermark(
            @RequestParam("file") MultipartFile videoFile,
            @RequestParam(value = "secretKey", required = false) String secretKey) {

        log.info("Received extraction request — file: {}, size: {} bytes",
                videoFile.getOriginalFilename(), videoFile.getSize());

        try {
            ExtractionResult result = extractionService.extract(videoFile, secretKey);

            // Build success response based on Contract §9
            ExtractionResponse.ResponseData data = new ExtractionResponse.ResponseData(
                    String.format("0x%08X", result.getExtractedUuid().getLeastSignificantBits()),
                    result.isValid(),
                    result.getConfidenceScore(),
                    "M2", // Default to M2 for now; M3 will be implemented in Phase 6
                    result.getFramesAnalyzed(),
                    result.getProcessingTimeMs()
            );

            ExtractionResponse response = new ExtractionResponse("success", data, null);

            return ResponseEntity.ok(response);

        } catch (ExtractionException e) {
            log.error("Extraction failed: {}", e.getMessage());

            // Handle 503 KEY_UNAVAILABLE
            if (e.getMessage() != null && e.getMessage().contains("503 KEY_UNAVAILABLE")) {
                ExtractionResponse errorResponse = new ExtractionResponse(
                        "error", 
                        null, 
                        new ExtractionResponse.ErrorData("KEY_UNAVAILABLE", "Service cannot start without the master watermark key")
                );
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
            }

            // General error
            ExtractionResponse errorResponse = new ExtractionResponse(
                    "error",
                    null,
                    new ExtractionResponse.ErrorData("EXTRACTION_FAILED", e.getMessage())
            );
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get information about the extraction service configuration.
     * Updated to reflect v1 Contract.
     */
    @GetMapping("/info")
    public ResponseEntity<?> getServiceInfo() {
        return ResponseEntity.ok(java.util.Map.of(
                "service", "Specter Extraction Service",
                "contract_version", "v1.0",
                "status", "healthy"
        ));
    }
}
