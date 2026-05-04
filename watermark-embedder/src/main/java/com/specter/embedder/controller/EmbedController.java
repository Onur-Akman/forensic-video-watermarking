package com.specter.embedder.controller;

import com.specter.embedder.controller.support.EmbedRequestParser;
import com.specter.embedder.controller.support.RequestContext;
import com.specter.embedder.dto.EmbedResponse;
import com.specter.embedder.service.EmbeddingService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Contract section 6.1 — `POST /api/v1/embed` ucu. Controller sadece HTTP'den
 * domain'e cevirir ve {@link EmbeddingService}'i cagirir; parsing/validation
 * {@link EmbedRequestParser}'da, hata mapping'i
 * {@link GlobalExceptionHandler}'da, log correlation
 * {@link RequestContext} araciligiyla yapilir.
 */
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

        UUID requestId = EmbedRequestParser.resolveRequestId(requestIdRaw);
        RequestContext.putRequestId(requestId);
        try {
            EmbedRequestParser.requireFile(file);
            long watermarkId = EmbedRequestParser.parseWatermarkId(watermarkIdRaw);
            return embeddingService.embed(file, watermarkId, requestId);
        } finally {
            RequestContext.clearRequestId();
        }
    }
}
