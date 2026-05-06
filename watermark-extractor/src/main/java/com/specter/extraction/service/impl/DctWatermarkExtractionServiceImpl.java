package com.specter.extraction.service.impl;

import com.specter.extraction.config.AppConfig;
import com.specter.extraction.config.SpectreKeyProvider;
import com.specter.extraction.config.WatermarkConfig;
import com.specter.extraction.exception.ExtractionException;
import com.specter.extraction.model.*;
import com.specter.extraction.service.*;
import com.specter.extraction.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * DCT-domain watermark extraction — Multi-mode forensic alignment search.
 */
@Service
public class DctWatermarkExtractionServiceImpl implements WatermarkExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DctWatermarkExtractionServiceImpl.class);

    private static final double[] SEARCH_SCALES = { 0.85, 0.90, 0.95, 1.00, 1.05, 1.10 };
    
    private static final int SEARCH_FRAMES = 15;
    private static final int REFINE_FRAMES = 45;

    private final VideoDecoderService         videoDecoderService;
    private final FrameSynchronizationService  frameSyncService;
    private final ConfidenceScoreService       confidenceScoreService;
    private final SpectreKeyProvider           spectreKeyProvider;
    private final WatermarkConfig              watermarkConfig;
    private final Semaphore                    semaphore;

    private volatile int[] cellIndices;
    private volatile int[] pairIndices;

    public DctWatermarkExtractionServiceImpl(
            VideoDecoderService videoDecoderService,
            FrameSynchronizationService frameSyncService,
            ConfidenceScoreService confidenceScoreService,
            SpectreKeyProvider spectreKeyProvider,
            WatermarkConfig watermarkConfig,
            AppConfig appConfig) {
        this.videoDecoderService    = videoDecoderService;
        this.frameSyncService       = frameSyncService;
        this.confidenceScoreService = confidenceScoreService;
        this.spectreKeyProvider     = spectreKeyProvider;
        this.watermarkConfig        = watermarkConfig;
        this.semaphore              = new Semaphore(appConfig.getMaxConcurrentTasks());
    }

    @Override
    public ExtractionResult extract(MultipartFile videoFile, String secretKey) {
        if (!spectreKeyProvider.isKeyAvailable()) throw new ExtractionException("Key unavailable");
        try {
            if (!semaphore.tryAcquire(300, TimeUnit.SECONDS)) throw new ExtractionException("Server busy");
            try {
                return process(videoFile);
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("Interrupted");
        }
    }

    private ExtractionResult process(MultipartFile videoFile) {
        long startMs = System.currentTimeMillis();

        List<FrameData> allFrames  = videoDecoderService.decodeToFrames(videoFile);
        List<FrameData> syncFrames = frameSyncService.synchronize(allFrames);
        ensureIndices();

        double[][] block = new double[8][8];
        double[][] dct   = new double[8][8];

        List<FrameData> sFrames = syncFrames.size() > SEARCH_FRAMES ? syncFrames.subList(0, SEARCH_FRAMES) : syncFrames;
        List<FrameData> rFrames = syncFrames.size() > REFINE_FRAMES ? syncFrames.subList(0, REFINE_FRAMES) : syncFrames;

        // --- Multi-Mode Search ---
        Alignment best = searchForensic(sFrames, rFrames, block, dct);
        
        if (best == null || !best.valid) {
            log.warn("Forensic search failed. Forcing EXTRACT_SNAPPED identity.");
            best = probe(syncFrames, 1.0, 0.0, 0.0, GridUtils.MappingMode.EXTRACT_SNAPPED, block, dct);
        }

        log.info("Extraction Alignment: scale={} ox={:.1f} oy={:.1f} mode={} valid={} conf={:.4f}",
                best.scale, best.ox, best.oy, best.mode, best.valid, best.conf);

        // --- Final Extraction ---
        SoftResult sr = extractSoftBilinear(syncFrames, best.scale, best.ox, best.oy, best.mode, block, dct);
        double finalConf = calcConf(sr, syncFrames.size());

        ErrorCorrectionUtils.RawPacket packet = ErrorCorrectionUtils.decode(sr.soft, spectreKeyProvider.getInterleaverKey());
        boolean valid = HmacAuthUtils.validateAuthTag(spectreKeyProvider.getAuthKey(), packet.watermarkId, packet.authTag);

        return ExtractionResult.builder()
                .extractedUuid(new UUID(0L, packet.watermarkId))
                .confidenceScore(finalConf)
                .valid(valid)
                .framesAnalyzed(syncFrames.size())
                .framesWithWatermark(syncFrames.size())
                .processingTimeMs(System.currentTimeMillis() - startMs)
                .timestamp(LocalDateTime.now())
                .message(valid ? "Success" : "Auth failed (ID: 0x" + packet.watermarkIdHex() + ")")
                .build();
    }

    private Alignment searchForensic(List<FrameData> sFrames, List<FrameData> rFrames, double[][] block, double[][] dct) {
        Alignment best = null;

        // Tier 1: Identity checks
        for (GridUtils.MappingMode mode : GridUtils.MappingMode.values()) {
            Alignment id = probe(rFrames, 1.0, 0.0, 0.0, mode, block, dct);
            if (id.valid && id.conf >= 0.85) return id;
            if (better(id, best)) best = id;
        }

        // Tier 2: Search across BOTH modes (covers pure crop and scale-then-crop)
        for (GridUtils.MappingMode mode : GridUtils.MappingMode.values()) {
            for (double scale : SEARCH_SCALES) {
                // Search ±160 pixels with step 16 (covers up to 16% crop/shift)
                for (double ox = -160; ox <= 160; ox += 16) {
                    for (double oy = -160; oy <= 160; oy += 16) {
                        if (scale == 1.0 && ox == 0 && oy == 0) continue;
                        
                        Alignment cand = probe(sFrames, scale, ox, oy, mode, block, dct);
                        if (better(cand, best)) {
                            best = cand;
                            if (best.valid && best.conf >= 0.50) {
                                Alignment ref = refine(rFrames, best, block, dct);
                                if (ref.valid && ref.conf >= 0.85) return ref;
                                if (better(ref, best)) best = ref;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private Alignment refine(List<FrameData> frames, Alignment base, double[][] block, double[][] dct) {
        Alignment best = base;
        // Refine ±12px step 4, then ±4px step 1
        for (double dx = -12; dx <= 12; dx += 4) {
            for (double dy = -12; dy <= 12; dy += 4) {
                Alignment cand = probe(frames, base.scale, base.ox + dx, base.oy + dy, base.mode, block, dct);
                if (better(cand, best)) best = cand;
            }
        }
        Alignment fBest = best;
        for (double dx = -3; dx <= 3; dx += 1) {
            for (double dy = -3; dy <= 3; dy += 1) {
                Alignment cand = probe(frames, fBest.scale, fBest.ox + dx, fBest.oy + dy, fBest.mode, block, dct);
                if (better(cand, fBest)) fBest = cand;
            }
        }
        return fBest;
    }

    private Alignment probe(List<FrameData> frames, double scale, double ox, double oy, GridUtils.MappingMode mode, double[][] block, double[][] dct) {
        SoftResult sr = extractSoftBilinear(frames, scale, ox, oy, mode, block, dct);
        double conf = calcConf(sr, frames.size());
        ErrorCorrectionUtils.RawPacket pkt = ErrorCorrectionUtils.decode(sr.soft, spectreKeyProvider.getInterleaverKey());
        boolean valid = HmacAuthUtils.validateAuthTag(spectreKeyProvider.getAuthKey(), pkt.watermarkId, pkt.authTag);
        return new Alignment(scale, ox, oy, mode, conf, valid);
    }

    private SoftResult extractSoftBilinear(List<FrameData> frames,
                                            double scale, double ox, double oy,
                                            GridUtils.MappingMode mode,
                                            double[][] block, double[][] dct) {
        int N = WatermarkConfig.EMBED_POINTS_PER_FRAME;
        double[] soft    = new double[N];
        double[] absSoft = new double[N];

        for (FrameData frame : frames) {
            double[][] lum = frame.getLuminanceChannel();
            if (lum == null) continue;
            int fw = frame.getWidth();
            int fh = frame.getHeight();

            for (int i = 0; i < N; i++) {
                double[] coords = GridUtils.getBlockCoordinates(cellIndices[i], fw, fh, scale, ox, oy, mode);
                GridUtils.extractBlockBilinear(lum, coords[0], coords[1], block);
                DctUtils.forwardDct8x8(block, dct);
                int[][] pair = WatermarkConfig.DCT_PAIRS[pairIndices[i]];
                double v = dct[pair[0][0]][pair[0][1]] - dct[pair[1][0]][pair[1][1]];
                soft[i]    += v;
                absSoft[i] += Math.abs(v);
            }
        }
        return new SoftResult(soft, absSoft);
    }

    private double calcConf(SoftResult sr, int framesUsed) {
        return confidenceScoreService.calculate(
                WatermarkPayload.builder()
                        .softBits(sr.soft)
                        .absSoftBits(sr.absSoft)
                        .framesUsed(framesUsed)
                        .build());
    }

    private static boolean better(Alignment cand, Alignment cur) {
        if (cur == null) return true;
        if (cand.valid && !cur.valid) return true;
        if (!cand.valid && cur.valid) return false;
        return cand.conf > cur.conf;
    }

    private void ensureIndices() {
        if (cellIndices != null) return;
        synchronized (this) {
            if (cellIndices != null) return;
            int N = WatermarkConfig.EMBED_POINTS_PER_FRAME;
            SpectralPrng cp = new SpectralPrng(spectreKeyProvider.getPrngKey(), WatermarkConfig.CTX_CELL_MAP);
            SpectralPrng pp = new SpectralPrng(spectreKeyProvider.getPrngKey(), WatermarkConfig.CTX_PAIR_MAP);
            cellIndices = cp.selectDistinct(WatermarkConfig.GRID_TOTAL_CELLS, N);
            pairIndices = new int[N];
            for (int i = 0; i < N; i++) pairIndices[i] = pp.nextInt(4);
        }
    }

    private static final class SoftResult {
        final double[] soft, absSoft;
        SoftResult(double[] s, double[] a) { soft = s; absSoft = a; }
    }

    private static final class Alignment {
        final double scale, ox, oy;
        final GridUtils.MappingMode mode;
        final double conf;
        final boolean valid;
        Alignment(double s, double ox, double oy, GridUtils.MappingMode m, double c, boolean v) {
            scale = s; this.ox = ox; this.oy = oy; mode = m; conf = c; valid = v;
        }
    }
}
