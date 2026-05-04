package com.specter.embedder.controller;

import com.specter.embedder.dto.EmbedResponse;
import com.specter.embedder.exception.EmbedException;
import com.specter.embedder.exception.ErrorCode;
import com.specter.embedder.service.EmbeddingService;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class EmbedController {

    private final EmbeddingService embeddingService;

    public EmbedController(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostMapping(value = "/embed", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmbedResponse embed(
            @RequestPart("file") MultipartFile file,
            @RequestParam("watermark_id") String watermarkIdRaw,
            @RequestParam(value = "request_id", required = false) String requestIdRaw) {

        UUID requestId = parseRequestId(requestIdRaw);
        MDC.put("requestId", requestId.toString());
        try {
            if (file == null || file.isEmpty()) {
                throw new EmbedException(ErrorCode.MISSING_FIELD, "file is required");
            }
            long watermarkId = parseWatermarkId(watermarkIdRaw);
            return embeddingService.embed(file, watermarkId, requestId);
        } finally {
            MDC.remove("requestId");
        }
    }

    private static UUID parseRequestId(String raw) {
        if (raw == null || raw.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new EmbedException(ErrorCode.MISSING_FIELD, "request_id is not a valid UUID");
        }
    }

    private static long parseWatermarkId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new EmbedException(ErrorCode.MISSING_FIELD, "watermark_id is required");
        }
        String s = raw.trim().toLowerCase();
        try {
            long value = s.startsWith("0x")
                    ? Long.parseUnsignedLong(s.substring(2), 16)
                    : Long.parseUnsignedLong(s, 10);
            if (value < 0L || value > 0xFFFFFFFFL) {
                throw new EmbedException(ErrorCode.INVALID_WATERMARK_ID,
                        "watermark_id must be a 32-bit unsigned integer (0..0xFFFFFFFF)");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new EmbedException(ErrorCode.INVALID_WATERMARK_ID,
                    "watermark_id must be hex (0x...) or decimal");
        }
    }
}
