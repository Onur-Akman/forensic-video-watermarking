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
    private final PayloadEncoder payloadEncoder;
    private final VideoIO videoIO;
    private final DctEmbedder dctEmbedder;
    private final EmbedderProperties props;

    public EmbeddingService(KeyManager keyManager,
                            PayloadEncoder payloadEncoder,
                            VideoIO videoIO,
                            DctEmbedder dctEmbedder,
                            EmbedderProperties props) {
        this.keyManager = keyManager;
        this.payloadEncoder = payloadEncoder;
        this.videoIO = videoIO;
        this.dctEmbedder = dctEmbedder;
        this.props = props;
    }

    public EmbedResponse embed(MultipartFile file, long watermarkId, UUID requestId) {
        long startNanos = System.nanoTime();
        log.info("embed start request_id={} watermark_id=0x{} bytes={}",
                requestId, String.format("%08X", watermarkId), file.getSize());

        if (!keyManager.isReady()) {
            throw new EmbedException(ErrorCode.KEY_UNAVAILABLE,
                    ContractConstants.KEY_ENV + " is not configured");
        }

        byte[] codeword84 = payloadEncoder.encode(watermarkId);

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

        int crf = clampCrf(props.h264Crf());
        VideoIO.EmbedRunResult run = videoIO.embedVideo(input, output, codeword84, dctEmbedder, crf);

        double psnrViolationRatio = run.framesProcessed() == 0
                ? 0.0
                : (double) run.framesPsnrViolation() / run.framesProcessed();
        if (psnrViolationRatio > ContractConstants.PSNR_VIOLATION_RATIO_LIMIT) {
            throw new EmbedException(ErrorCode.PSNR_VIOLATION,
                    String.format("PSNR < %.1f dB on %d/%d frames (%.1f%%)",
                            ContractConstants.PSNR_FLOOR_DB,
                            run.framesPsnrViolation(),
                            run.framesProcessed(),
                            psnrViolationRatio * 100.0));
        }

        double processingSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        EmbedMetrics metrics = new EmbedMetrics(
                run.psnrAvgDb(),
                run.psnrMinDb(),
                run.mseAvg(),
                run.mseMax(),
                run.framesProcessed(),
                run.framesPsnrViolation(),
                run.durationSec(),
                processingSec
        );

        log.info("embed done request_id={} frames={} psnr_avg_db={} processing_sec={}",
                requestId, run.framesProcessed(), run.psnrAvgDb(), processingSec);

        return EmbedResponse.success(
                requestId.toString(),
                String.format("0x%08X", watermarkId),
                output.toString(),
                metrics
        );
    }

    private static int clampCrf(int crf) {
        if (crf == 0) {
            return ContractConstants.H264_CRF_DEFAULT;
        }
        if (crf < ContractConstants.H264_CRF_MIN) {
            return ContractConstants.H264_CRF_MIN;
        }
        if (crf > ContractConstants.H264_CRF_MAX) {
            return ContractConstants.H264_CRF_MAX;
        }
        return crf;
    }
}
