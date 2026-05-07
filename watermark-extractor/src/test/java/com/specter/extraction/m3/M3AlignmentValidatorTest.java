package com.specter.extraction.m3;

import com.specter.extraction.model.ExtractionResult;
import com.specter.extraction.service.WatermarkExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * M3 alignment validator: runs the live {@link WatermarkExtractionService} against
 * the four FFmpeg-attacked clips produced by the embedder side
 * (output/m3_attacks/attack_*.mp4). Auto-skips when SPECTER_WM_KEY is not exported.
 *
 * <p>Run from the extractor module: {@code mvn -pl watermark-extractor -Dtest=M3AlignmentValidatorTest test}.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "SPECTER_WM_KEY", matches = "[0-9a-fA-F]{64}")
class M3AlignmentValidatorTest {

    private static final long EXPECTED_ID = 0x5C2A91FEL;

    private static final List<String> ATTACK_FILES = List.of(
            "attack_bitrate.mp4",
            "attack_scale.mp4",
            "attack_crop.mp4",
            "attack_color.mp4"
    );

    @Autowired
    WatermarkExtractionService extractionService;

    @Test
    void reportConfidencePerAttack() throws IOException {
        Path attackDir = Paths.get("..", "output", "m3_attacks").toAbsolutePath().normalize();
        System.out.println("[m3-validator] attack dir: " + attackDir);

        StringBuilder summary = new StringBuilder("\n=== M3 alignment validator (Yaren extractor) ===\n");
        for (String name : ATTACK_FILES) {
            Path file = attackDir.resolve(name);
            if (!Files.exists(file)) {
                summary.append(String.format("%-22s: missing (%s)%n", name, file));
                continue;
            }
            byte[] bytes = Files.readAllBytes(file);
            MockMultipartFile mf = new MockMultipartFile("file", name, "video/mp4", bytes);

            long t0 = System.nanoTime();
            ExtractionResult r = extractionService.extract(mf, null);
            double sec = (System.nanoTime() - t0) / 1e9;

            long extractedId = r.getExtractedUuid() == null
                    ? 0L
                    : r.getExtractedUuid().getLeastSignificantBits() & 0xFFFFFFFFL;
            boolean idMatch = extractedId == EXPECTED_ID;
            summary.append(String.format(
                    "%-22s: id=0x%08X (%s) conf=%.4f auth=%s frames=%d time=%.2fs%n",
                    name, extractedId, idMatch ? "OK" : "MISMATCH",
                    r.getConfidenceScore(), r.isValid(),
                    r.getFramesAnalyzed(), sec));
        }
        System.out.println(summary);
    }
}
