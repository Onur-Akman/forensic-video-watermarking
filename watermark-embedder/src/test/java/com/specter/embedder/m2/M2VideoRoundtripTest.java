package com.specter.embedder.m2;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.config.EmbedderProperties;
import com.specter.embedder.dto.EmbedMetrics;
import com.specter.embedder.m1.Roundtrip;
import com.specter.embedder.service.DctEmbedder;
import com.specter.embedder.service.KeyManager;
import com.specter.embedder.service.PayloadEncoder;
import com.specter.embedder.service.PsnrCalculator;
import com.specter.embedder.service.VideoEmbedder;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 2 — 30s 1080p30 video embed → extract roundtrip
 * (contract sections 4.3, 4.4, 10.0, 10.2 acceptance).
 */
class M2VideoRoundtripTest {

    private static final String DUMMY_MASTER_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final long TEST_ID = 0x5C2A91FEL;
    private static final Path INPUT = Paths.get(
            "..", "shared", "test-vectors", "m2_sample", "clean_30s_1080p30.mp4");

    @Test
    @EnabledIf(value = "inputExists",
            disabledReason = "Run M2SampleGenerator first to produce shared/test-vectors/m2_sample/clean_30s_1080p30.mp4")
    void videoEmbedExtract_30s1080p30_meetsContractSection10_2() throws IOException {
        EmbedderProperties props = new EmbedderProperties(
                DUMMY_MASTER_HEX, ".", ContractConstants.H264_CRF_DEFAULT, ContractConstants.CONTRACT_VERSION);
        KeyManager keyManager = new KeyManager(props);
        DctEmbedder dctEmbedder = new DctEmbedder(keyManager, new PsnrCalculator());
        VideoEmbedder videoEmbedder = new VideoEmbedder(
                keyManager, new PayloadEncoder(keyManager), dctEmbedder, new PsnrCalculator());

        Path output = Files.createTempFile("m2-out-", ".mp4");
        output.toFile().deleteOnExit();

        VideoEmbedder.VideoEmbedResult result = videoEmbedder.embed(INPUT, output, TEST_ID);
        EmbedMetrics m = result.metrics();

        // M2 acceptance #1 — frames_psnr_violation == 0 (contract section 10.0 / 10.2 mandatory)
        assertEquals(0, m.framesPsnrViolation(),
                "frames_psnr_violation must be 0 (contract section 10.0/10.2 mandatory)");

        // M2 acceptance #2 — processing_sec ≤ 2× duration_sec (spec §2.1, contract §10.0)
        assertTrue(m.processingSec() <= 2.0 * m.durationSec(),
                String.format("processing %.2fs > 2× duration %.2fs", m.processingSec(), m.durationSec()));

        // M2 acceptance #3 — frames_skipped < ~5% (informational; not §6.1 schema)
        double skipRatio = m.framesProcessed() == 0
                ? 0.0 : (double) result.framesSkipped() / m.framesProcessed();
        assertTrue(skipRatio < 0.05,
                String.format("frames_skipped %d / %d (%.1f%%) ≥ 5%% — synthetic asset content issue, "
                                + "not algorithm bug", result.framesSkipped(), m.framesProcessed(), skipRatio * 100));

        // Output validates as H.264 / yuv420p / mp4 (contract §4.4)
        verifyOutputFormat(output);

        // Roundtrip: bit-perfect ID + auth_tag_valid + confidence ≥ 0.90 (§10.0 / §10.2)
        Roundtrip.Result extracted = Roundtrip.extractFromVideo(output, keyManager);
        assertTrue(extracted.authTagValid(), "auth_tag_valid must be true");
        assertEquals(TEST_ID, extracted.watermarkId(),
                String.format("expected ID 0x%08X, got 0x%08X", TEST_ID, extracted.watermarkId()));
        assertTrue(extracted.bitConfidence() >= 0.90,
                String.format("bit confidence %.4f < 0.90 (contract §10.2 minimum)", extracted.bitConfidence()));
    }

    private static void verifyOutputFormat(Path output) throws IOException {
        try (FFmpegFrameGrabber g = new FFmpegFrameGrabber(output.toFile())) {
            g.setImageMode(FrameGrabber.ImageMode.RAW);
            g.start();
            assertEquals(AV_CODEC_ID_H264, g.getVideoCodec(),
                    "output codec must be H.264 (contract §4.4)");
            assertEquals(AV_PIX_FMT_YUV420P, g.getPixelFormat(),
                    "output pixel format must be yuv420p (contract §4.4)");
            String format = g.getFormat();
            assertNotNull(format, "container format must not be null");
            assertTrue(format.contains("mp4"),
                    "output container must be mp4 (got '" + format + "')");
        }
    }

    @SuppressWarnings("unused")  // referenced via @EnabledIf
    static boolean inputExists() {
        return Files.exists(INPUT);
    }
}
