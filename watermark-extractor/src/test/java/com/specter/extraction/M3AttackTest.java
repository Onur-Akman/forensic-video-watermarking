package com.specter.extraction;

import com.specter.extraction.config.AppConfig;
import com.specter.extraction.config.SpectreKeyProvider;
import com.specter.extraction.config.WatermarkConfig;
import com.specter.extraction.model.ExtractionResult;
import com.specter.extraction.model.FrameData;
import com.specter.extraction.service.impl.ConfidenceScoreServiceImpl;
import com.specter.extraction.service.impl.DctWatermarkExtractionServiceImpl;
import com.specter.extraction.service.impl.FrameSynchronizationServiceImpl;
import com.specter.extraction.service.impl.VideoDecoderServiceImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 Adversarial Robustness Test Runner.
 *
 * Contract §10.3 — Dört FFmpeg manipülasyonu ayrı ayrı test edilir:
 *   - Bitrate %50 düşürme      → min confidence 0.85
 *   - 1080p → 720p scale       → min confidence 0.85
 *   - %5 border crop           → min confidence 0.80
 *   - Brightness/contrast ±10% → min confidence 0.85
 *
 * Test seed ID: 0x5C2A91FE
 */
public class M3AttackTest {

    private static final Logger log = LoggerFactory.getLogger(M3AttackTest.class);
    private static final long EXPECTED_WATERMARK_ID = 0x5C2A91FEL;

    // Minimum confidence thresholds per contract §10.3
    private static final double MIN_CONFIDENCE_CROP = 0.80;
    private static final double MIN_CONFIDENCE_OTHER = 0.85;

    // Test results for report
    private static final Map<String, TestResult> RESULTS = new LinkedHashMap<>();

    static class TestResult {
        final String video;
        final double minConfidence;
        double actualConfidence;
        long extractedId;
        boolean authValid;
        int framesScanned;
        String status; // "PASS" or "FAIL"
        String error;

        TestResult(String video, double minConfidence) {
            this.video = video;
            this.minConfidence = minConfidence;
        }
    }

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

    private DctWatermarkExtractionServiceImpl createExtractionService() {
        SpectreKeyProvider keyProvider = new SpectreKeyProvider();
        keyProvider.init();
        assertTrue(keyProvider.isKeyAvailable(), "SPECTER_WM_KEY must be available");

        WatermarkConfig watermarkConfig = new WatermarkConfig();
        AppConfig appConfig = new AppConfig();
        VideoDecoderServiceImpl videoDecoderService = new VideoDecoderServiceImpl(appConfig);
        FrameSynchronizationServiceImpl frameSyncService = new FrameSynchronizationServiceImpl(watermarkConfig);
        ConfidenceScoreServiceImpl confidenceService = new ConfidenceScoreServiceImpl();

        return new DctWatermarkExtractionServiceImpl(
                videoDecoderService, frameSyncService, confidenceService, keyProvider, watermarkConfig, appConfig);
    }

    private File findVideo(String fileName) {
        String[] possiblePaths = {
                fileName,
                Paths.get("..", fileName).toString(),
                Paths.get(".", fileName).toString(),
                Paths.get(System.getProperty("user.dir"), "..", fileName).toString(),
        };

        for (String path : possiblePaths) {
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                return f;
            }
        }
        return null;
    }

    @Test
    void testAttackBitrate(TestInfo testInfo) {
        runAttackTest("attack_bitrate.mp4", MIN_CONFIDENCE_OTHER);
    }

    @Test
    void testAttackScale(TestInfo testInfo) {
        runAttackTest("attack_scale.mp4", MIN_CONFIDENCE_OTHER);
    }

    @Test
    void testAttackCrop(TestInfo testInfo) {
        runAttackTest("attack_crop.mp4", MIN_CONFIDENCE_CROP);
    }

    @Test
    void testAttackColor(TestInfo testInfo) {
        runAttackTest("attack_color.mp4", MIN_CONFIDENCE_OTHER);
    }

    private void runAttackTest(String videoFileName, double minConfidence) {
        TestResult result = new TestResult(videoFileName, minConfidence);
        RESULTS.put(videoFileName, result);

        File videoFile = findVideo(videoFileName);
        assertNotNull(videoFile, "Video file not found: " + videoFileName +
                "\nSearched in: " + System.getProperty("user.dir"));

        log.info("=== Testing: {} (min confidence: {}) ===", videoFile.getAbsolutePath(), minConfidence);

        try {
            DctWatermarkExtractionServiceImpl service = createExtractionService();

            byte[] fileBytes = Files.readAllBytes(videoFile.toPath());
            MockMultipartFile mockFile = new MockMultipartFile(
                    "file", videoFileName, "video/mp4", fileBytes);

            ExtractionResult extractionResult = service.extract(mockFile, null);

            long extractedId = extractionResult.getExtractedUuid().getLeastSignificantBits() & 0xFFFFFFFFL;
            result.actualConfidence = extractionResult.getConfidenceScore();
            result.extractedId = extractedId;
            result.authValid = extractionResult.isValid();
            result.framesScanned = extractionResult.getFramesAnalyzed();

            log.info("  watermark_id: {} (expected: {})", String.format("0x%08X", extractedId), String.format("0x%08X", EXPECTED_WATERMARK_ID));
            log.info("  confidence: {} (min: {})", String.format("%.4f", extractionResult.getConfidenceScore()), String.format("%.2f", minConfidence));
            log.info("  auth_tag_valid: {}", extractionResult.isValid());
            log.info("  frames_analyzed: {}", extractionResult.getFramesAnalyzed());

            // Assertions
            assertEquals(EXPECTED_WATERMARK_ID, extractedId,
                    "Watermark ID mismatch for " + videoFileName);
            assertTrue(extractionResult.isValid(),
                    "Auth tag must be valid for " + videoFileName);
            assertTrue(extractionResult.getConfidenceScore() >= minConfidence,
                    String.format("Confidence %.4f below minimum %.2f for %s",
                            extractionResult.getConfidenceScore(), minConfidence, videoFileName));

            result.status = "PASS";
            log.info("✅ PASS: {} — confidence={}", videoFileName, String.format("%.4f", extractionResult.getConfidenceScore()));

        } catch (AssertionError e) {
            result.status = "FAIL";
            result.error = e.getMessage();
            log.error("❌ FAIL: {} — {}", videoFileName, e.getMessage());
            throw e;
        } catch (Exception e) {
            result.status = "FAIL";
            result.error = e.getMessage();
            log.error("❌ FAIL: {} — {}", videoFileName, e.getMessage(), e);
            fail("Extraction failed for " + videoFileName + ": " + e.getMessage());
        }
    }
}
