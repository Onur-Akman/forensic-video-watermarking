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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Test-scope real-content M2 validation runner. This intentionally stays out of
 * the REST surface and production extractor ownership.
 */
public final class M2RealWorldValidator {

    private static final long DEFAULT_WATERMARK_ID = 0x5C2A91FEL;

    private M2RealWorldValidator() {
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path repoRoot = findRepoRoot();
        Path input = args.length > 0
                ? Path.of(args[0])
                : repoRoot.resolve("input/deneme.mp4");
        Path output = args.length > 1
                ? Path.of(args[1])
                : repoRoot.resolve("output/deneme_watermarked.mp4");
        long watermarkId = args.length > 2
                ? Long.decode(args[2]) & 0xFFFFFFFFL
                : DEFAULT_WATERMARK_ID;

        String wmKey = readEnvExampleKey(repoRoot.resolve(".env.example"));
        EmbedderProperties props = new EmbedderProperties(
                wmKey, repoRoot.resolve("output").toString(),
                ContractConstants.H264_CRF_DEFAULT, ContractConstants.CONTRACT_VERSION);
        KeyManager keyManager = new KeyManager(props);
        PsnrCalculator psnrCalculator = new PsnrCalculator();
        VideoEmbedder videoEmbedder = new VideoEmbedder(
                keyManager,
                new PayloadEncoder(keyManager),
                new DctEmbedder(keyManager, psnrCalculator),
                psnrCalculator);

        System.out.printf("input=%s%n", input);
        System.out.printf("output=%s%n", output);
        System.out.printf("watermark_id=0x%08X%n", watermarkId);

        VideoEmbedder.VideoEmbedResult result = videoEmbedder.embed(input, output, watermarkId);
        EmbedMetrics metrics = result.metrics();
        double skipRatio = metrics.framesProcessed() == 0
                ? 0.0
                : (double) result.framesSkipped() / metrics.framesProcessed();
        double processingRatio = metrics.durationSec() == 0.0
                ? Double.POSITIVE_INFINITY
                : metrics.processingSec() / metrics.durationSec();

        Roundtrip.Result extracted = Roundtrip.extractFromVideo(output, keyManager);

        System.out.printf("frames_processed=%d%n", metrics.framesProcessed());
        System.out.printf("frames_psnr_violation=%d%n", metrics.framesPsnrViolation());
        System.out.printf("frames_skipped=%d%n", result.framesSkipped());
        System.out.printf("frames_skipped_ratio=%.6f%n", skipRatio);
        System.out.printf("psnr_avg_db=%.6f%n", metrics.psnrAvgDb());
        System.out.printf("psnr_min_db=%.6f%n", metrics.psnrMinDb());
        System.out.printf("mse_avg=%.6f%n", metrics.mseAvg());
        System.out.printf("mse_max=%.6f%n", metrics.mseMax());
        System.out.printf("duration_sec=%.6f%n", metrics.durationSec());
        System.out.printf("processing_sec=%.6f%n", metrics.processingSec());
        System.out.printf("processing_ratio=%.6f%n", processingRatio);
        System.out.printf("extracted_watermark_id=0x%08X%n", extracted.watermarkId());
        System.out.printf("auth_tag_valid=%s%n", extracted.authTagValid());
        System.out.printf("confidence=%.6f%n", extracted.bitConfidence());

        boolean pass = metrics.framesPsnrViolation() == 0
                && metrics.psnrMinDb() > ContractConstants.PSNR_FLOOR_DB
                && processingRatio < 2.0
                && result.framesSkipped() <= metrics.framesProcessed() * 0.15
                && extracted.authTagValid()
                && extracted.watermarkId() == watermarkId
                && extracted.bitConfidence() >= 0.90;
        System.out.printf("internal_m2_acceptance=%s%n", pass ? "PASS" : "FAIL");
        if (!pass) {
            System.exit(2);
        }
    }

    private static Path findRepoRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(".env.example"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("could not find repo root containing .env.example");
    }

    private static String readEnvExampleKey(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        for (String line : lines) {
            if (line.startsWith(ContractConstants.KEY_ENV + "=")) {
                return line.substring((ContractConstants.KEY_ENV + "=").length()).trim();
            }
        }
        throw new IOException(ContractConstants.KEY_ENV + " not found in " + path);
    }
}
