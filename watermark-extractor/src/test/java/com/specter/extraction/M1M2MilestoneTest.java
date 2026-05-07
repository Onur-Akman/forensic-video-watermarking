package com.specter.extraction;

import com.specter.extraction.config.AppConfig;
import com.specter.extraction.config.SpectreKeyProvider;
import com.specter.extraction.config.WatermarkConfig;
import com.specter.extraction.model.FrameData;
import com.specter.extraction.service.impl.ConfidenceScoreServiceImpl;
import com.specter.extraction.service.impl.DctWatermarkExtractionServiceImpl;
import com.specter.extraction.service.impl.FrameSynchronizationServiceImpl;
import com.specter.extraction.service.impl.VideoDecoderServiceImpl;
import com.specter.extraction.util.ColorSpaceUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;
import com.specter.extraction.model.ExtractionResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1 & M2 Milestone Validation Tests.
 *
 * M1 — Static Image Extraction (PNG):
 *   Input: 1080testimage_watermarked.png (1920×1080 RGB)
 *   Expected: watermark_id=0x5C2A91FE, auth_tag_valid=true, confidence ≥ 0.95
 *   Method: PNG RGB → BT.601 Y conversion → DCT extraction
 *
 * M2 — Clean Video Extraction (MP4):
 *   Input: deneme_watermarked.mp4 (original watermarked video, no attacks)
 *   Expected: watermark_id=0x5C2A91FE, auth_tag_valid=true, confidence ≥ 0.90
 *   Method: GRAY8 Y-plane extraction → DCT extraction
 */
public class M1M2MilestoneTest {

    private static final Logger log = LoggerFactory.getLogger(M1M2MilestoneTest.class);
    private static final long EXPECTED_WATERMARK_ID = 0x5C2A91FEL;

    @BeforeAll
    static void setupEnv() {
        String key = System.getenv("SPECTER_WM_KEY");
        if (key == null || key.isBlank()) {
            key = System.getProperty("SPECTER_WM_KEY");
        }
        if (key == null || key.isBlank()) {
            System.setProperty("SPECTER_WM_KEY",
                    "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // M1 — Static PNG Image Extraction
    // ─────────────────────────────────────────────────────────────────

    @Test
    void testM1_StaticImageExtraction() throws Exception {
        File imageFile = findFile("1080testimage_watermarked.png");
        assertNotNull(imageFile, "PNG file not found: 1080testimage_watermarked.png");

        log.info("========================================");
        log.info("  M1 — Static Image Extraction Test");
        log.info("========================================");
        log.info("  Input: {}", imageFile.getAbsolutePath());

        // Load PNG via ImageIO and extract Y channel using BT.601
        BufferedImage image = ImageIO.read(imageFile);
        assertNotNull(image, "Failed to load PNG image");
        int w = image.getWidth();
        int h = image.getHeight();
        log.info("  Image: {}x{} RGB", w, h);

        int[] rgbPixels = image.getRGB(0, 0, w, h, null, 0, w);
        double[][] luminance = ColorSpaceUtils.extractLuminance(rgbPixels, w, h);

        // Build a single FrameData from the image
        FrameData frame = FrameData.builder()
                .frameIndex(0)
                .width(w)
                .height(h)
                .luminanceChannel(luminance)
                .timestampSeconds(0.0)
                .build();

        // Create extraction service and extract via internal pipeline
        SpectreKeyProvider keyProvider = new SpectreKeyProvider();
        keyProvider.init();
        assertTrue(keyProvider.isKeyAvailable(), "SPECTER_WM_KEY must be available");

        WatermarkConfig wc = new WatermarkConfig();
        AppConfig appConfig = new AppConfig();
        ConfidenceScoreServiceImpl confidenceService = new ConfidenceScoreServiceImpl();

        // Use the direct extraction logic with a single frame
        // We replicate the core extraction from DctWatermarkExtractionServiceImpl
        com.specter.extraction.util.SpectralPrng cp =
                new com.specter.extraction.util.SpectralPrng(keyProvider.getPrngKey(), WatermarkConfig.CTX_CELL_MAP);
        com.specter.extraction.util.SpectralPrng pp =
                new com.specter.extraction.util.SpectralPrng(keyProvider.getPrngKey(), WatermarkConfig.CTX_PAIR_MAP);

        int N = WatermarkConfig.EMBED_POINTS_PER_FRAME;
        int[] cellIndices = cp.selectDistinct(WatermarkConfig.GRID_TOTAL_CELLS, N);
        int[] pairIndices = new int[N];
        for (int i = 0; i < N; i++) pairIndices[i] = pp.nextInt(4);

        double[][] block = new double[8][8];
        double[][] dct = new double[8][8];
        double[] soft = new double[N];
        double[] absSoft = new double[N];

        for (int i = 0; i < N; i++) {
            double[] coords = com.specter.extraction.util.GridUtils.getBlockCoordinates(
                    cellIndices[i], w, h, 1.0, 0, 0,
                    com.specter.extraction.util.GridUtils.MappingMode.EMBED_SNAPPED);
            com.specter.extraction.util.GridUtils.extractBlockBilinear(luminance, coords[0], coords[1], block);
            com.specter.extraction.util.DctUtils.forwardDct8x8(block, dct);
            int[][] pair = WatermarkConfig.DCT_PAIRS[pairIndices[i]];
            double v = dct[pair[0][0]][pair[0][1]] - dct[pair[1][0]][pair[1][1]];
            soft[i] = v;
            absSoft[i] = Math.abs(v);
        }

        // Decode packet
        com.specter.extraction.util.ErrorCorrectionUtils.RawPacket packet =
                com.specter.extraction.util.ErrorCorrectionUtils.decode(soft, keyProvider.getInterleaverKey());

        // Validate auth tag
        boolean valid = com.specter.extraction.util.HmacAuthUtils.validateAuthTag(
                keyProvider.getAuthKey(), packet.watermarkId, packet.authTag);

        // Calculate confidence
        double confidence = confidenceService.calculate(
                com.specter.extraction.model.WatermarkPayload.builder()
                        .softBits(soft)
                        .absSoftBits(absSoft)
                        .framesUsed(1)
                        .build());

        if (!valid) confidence = 0.0;

        // Print results
        log.info("  ────────────────────────────────────");
        log.info("  status: {}", valid ? "success" : "FAILED");
        log.info("  watermark_id: {}", String.format("0x%08X", packet.watermarkId));
        log.info("  watermark_id_decimal: {}", packet.watermarkId);
        log.info("  auth_tag_valid: {}", valid);
        log.info("  confidence: {}", String.format("%.4f", confidence));
        log.info("  ────────────────────────────────────");

        // Assertions
        assertEquals(EXPECTED_WATERMARK_ID, packet.watermarkId,
                String.format("Watermark ID mismatch: expected 0x%08X, got 0x%08X",
                        EXPECTED_WATERMARK_ID, packet.watermarkId));
        assertTrue(valid, "Auth tag must be valid");
        assertTrue(confidence >= 0.95,
                String.format("M1 confidence %.4f below minimum 0.95", confidence));

        log.info("  ✅ M1 PASS — confidence={}", String.format("%.4f", confidence));
        log.info("========================================");
    }

    // ─────────────────────────────────────────────────────────────────
    // M2 — Clean Video Extraction
    // ─────────────────────────────────────────────────────────────────

    @Test
    void testM2_CleanVideoExtraction() throws Exception {
        File videoFile = findFile("deneme_watermarked.mp4");
        assertNotNull(videoFile, "Video file not found: deneme_watermarked.mp4");

        log.info("========================================");
        log.info("  M2 — Clean Video Extraction Test");
        log.info("========================================");
        log.info("  Input: {}", videoFile.getAbsolutePath());

        SpectreKeyProvider keyProvider = new SpectreKeyProvider();
        keyProvider.init();
        assertTrue(keyProvider.isKeyAvailable(), "SPECTER_WM_KEY must be available");

        WatermarkConfig wc = new WatermarkConfig();
        AppConfig appConfig = new AppConfig();
        VideoDecoderServiceImpl videoDecoderService = new VideoDecoderServiceImpl(appConfig);
        FrameSynchronizationServiceImpl frameSyncService = new FrameSynchronizationServiceImpl(wc);
        ConfidenceScoreServiceImpl confidenceService = new ConfidenceScoreServiceImpl();

        DctWatermarkExtractionServiceImpl service = new DctWatermarkExtractionServiceImpl(
                videoDecoderService, frameSyncService, confidenceService, keyProvider, wc, appConfig);

        byte[] fileBytes = Files.readAllBytes(videoFile.toPath());
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "deneme_watermarked.mp4", "video/mp4", fileBytes);

        ExtractionResult result = service.extract(mockFile, null);

        long extractedId = result.getExtractedUuid().getLeastSignificantBits() & 0xFFFFFFFFL;

        // Print results
        log.info("  ────────────────────────────────────");
        log.info("  status: {}", result.isValid() ? "success" : "FAILED");
        log.info("  watermark_id: {}", String.format("0x%08X", extractedId));
        log.info("  watermark_id_decimal: {}", extractedId);
        log.info("  auth_tag_valid: {}", result.isValid());
        log.info("  confidence: {}", String.format("%.4f", result.getConfidenceScore()));
        log.info("  frames_analyzed: {}", result.getFramesAnalyzed());
        log.info("  ────────────────────────────────────");

        // Assertions
        assertEquals(EXPECTED_WATERMARK_ID, extractedId,
                String.format("Watermark ID mismatch: expected 0x%08X, got 0x%08X",
                        EXPECTED_WATERMARK_ID, extractedId));
        assertTrue(result.isValid(), "Auth tag must be valid");
        assertTrue(result.getConfidenceScore() >= 0.90,
                String.format("M2 confidence %.4f below minimum 0.90", result.getConfidenceScore()));

        log.info("  ✅ M2 PASS — confidence={}", String.format("%.4f", result.getConfidenceScore()));
        log.info("========================================");
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private File findFile(String fileName) {
        String[] paths = {
                fileName,
                Paths.get("..", fileName).toString(),
                Paths.get(".", fileName).toString(),
                Paths.get(System.getProperty("user.dir"), "..", fileName).toString(),
        };
        for (String p : paths) {
            File f = new File(p);
            if (f.exists() && f.isFile()) return f;
        }
        return null;
    }
}
