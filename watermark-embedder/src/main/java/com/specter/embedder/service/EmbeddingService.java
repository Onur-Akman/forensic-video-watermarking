package com.specter.embedder.service;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.config.EmbedderProperties;
import com.specter.embedder.dto.EmbedMetrics;
import com.specter.embedder.dto.EmbedResponse;
import com.specter.embedder.exception.EmbedException;
import com.specter.embedder.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;


/**
 * Top-level orkestratör: payload encode + video pipeline + metric assembly.
 * REST controller bu sinifi cagirir; dahili pipeline hatalari EmbedException
 * olarak yukseltilir ve GlobalExceptionHandler kontrat section 6.3 formatina cevirir.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final KeyManager keyManager;
    private final VideoIO videoIO;
    private final VideoEmbedder videoEmbedder;
    private final EmbedderProperties props;

    public EmbeddingService(KeyManager keyManager,
                            VideoIO videoIO,
                            VideoEmbedder videoEmbedder,
                            EmbedderProperties props) {
        this.keyManager = keyManager;
        this.videoIO = videoIO;
        this.videoEmbedder = videoEmbedder;
        this.props = props;
    }

    public EmbedResponse embed(MultipartFile file, long watermarkId, UUID requestId) {
        log.info("embed start request_id={} watermark_id=0x{} bytes={}",
                requestId, String.format("%08X", watermarkId), file.getSize());

        if (!keyManager.isReady()) {
            throw new EmbedException(ErrorCode.KEY_UNAVAILABLE,
                    ContractConstants.KEY_ENV + " is not configured");
        }

        Path input;
        try {
            input = videoIO.saveUpload(file, requestId);
        } catch (IOException e) {
            throw new EmbedException(ErrorCode.INVALID_VIDEO, "could not read uploaded file", e);
        }

        Path outputDir = Paths.get(props.outputDir());
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new EmbedException(ErrorCode.INTERNAL_ERROR, "could not prepare output directory", e);
        }
        Path output = outputDir.resolve(requestId + ".mp4");

        VideoEmbedder.VideoEmbedResult result;
        try {
            result = videoEmbedder.embed(input, output, watermarkId);
        } catch (IOException e) {
            throw new EmbedException(ErrorCode.INVALID_VIDEO, "video pipeline failed: " + e.getMessage(), e);
        }
        EmbedMetrics metrics = result.metrics();

        double psnrViolationRatio = metrics.framesProcessed() == 0
                ? 0.0
                : (double) metrics.framesPsnrViolation() / metrics.framesProcessed();
        if (psnrViolationRatio > ContractConstants.PSNR_VIOLATION_RATIO_LIMIT) {
            throw new EmbedException(ErrorCode.PSNR_VIOLATION,
                    String.format("PSNR < %.1f dB on %d/%d frames (%.1f%%)",
                            ContractConstants.PSNR_FLOOR_DB,
                            metrics.framesPsnrViolation(),
                            metrics.framesProcessed(),
                            psnrViolationRatio * 100.0));
        }

        log.info("embed done request_id={} frames={} psnr_avg_db={} processing_sec={}",
                requestId, metrics.framesProcessed(), metrics.psnrAvgDb(), metrics.processingSec());

        return EmbedResponse.success(
                requestId.toString(),
                String.format("0x%08X", watermarkId),
                output.toString(),
                metrics
        );
    }
}
