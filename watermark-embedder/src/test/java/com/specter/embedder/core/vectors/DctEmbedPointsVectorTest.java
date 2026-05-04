package com.specter.embedder.core.vectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.config.EmbedderProperties;
import com.specter.embedder.core.dct.Dct2D;
import com.specter.embedder.core.dct.PairModulator;
import com.specter.embedder.core.grid.GridMapper;
import com.specter.embedder.service.DctEmbedder;
import com.specter.embedder.service.KeyManager;
import com.specter.embedder.service.PayloadEncoder;
import com.specter.embedder.service.PsnrCalculator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * `dct_embed_points.json` fixture'ini DctEmbedder'a karsi dogrular: hem frame-level
 * (frame_skipped, psnr_db) hem de varsa per-point trace (cell, pair_index, bit, before, after).
 * Yaren tarafi ayni JSON'la kendi DCT + modulator + IDCT zincirini bit-mukemmel verifie eder.
 */
class DctEmbedPointsVectorTest {

    private static final String DUMMY_MASTER_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final double PSNR_TOLERANCE_DB = 1e-6;
    private static final double COEF_TOLERANCE = 1e-9;

    static Stream<Arguments> dctEmbedPointVectors() {
        return VectorLoader.vectors("dct_embed_points")
                .map(v -> Arguments.of(v.path("id").asText(), v));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dctEmbedPointVectors")
    void embedIntoYPlane_matchesFixture(String id, JsonNode v) {
        EmbedderProperties props = new EmbedderProperties(
                DUMMY_MASTER_HEX, ".", ContractConstants.H264_CRF_DEFAULT, ContractConstants.CONTRACT_VERSION);
        KeyManager keyManager = new KeyManager(props);
        DctEmbedder embedder = new DctEmbedder(keyManager, new PsnrCalculator());
        PayloadEncoder payloadEncoder = new PayloadEncoder(keyManager);

        int W = v.path("input").path("frame_width").asInt();
        int H = v.path("input").path("frame_height").asInt();
        int yFill = v.path("input").path("y_fill").asInt();
        long watermarkId = Long.parseUnsignedLong(
                v.path("input").path("watermark_id_hex").asText().substring(2), 16);
        double delta = v.path("input").path("delta").asDouble();

        byte[] codeword = payloadEncoder.encode(watermarkId);

        // Frame-level: PSNR + frame_skipped
        byte[] y = new byte[W * H];
        Arrays.fill(y, (byte) yFill);
        DctEmbedder.EmbedResult result = embedder.embedIntoYPlane(y, W, H, codeword, delta);

        assertEquals(v.path("expected").path("frame_skipped").asBoolean(), !result.embedded(),
                id + ": frame_skipped");
        assertEquals(v.path("expected").path("psnr_db").asDouble(), result.psnrDb(), PSNR_TOLERANCE_DB,
                id + ": psnr_db");

        // Point-level: per-modulation trace dogrulamasi (sadece happy-path vektorlerinde)
        JsonNode points = v.path("expected").path("points");
        if (!points.isArray() || points.isEmpty()) {
            return;
        }
        int[] cells = embedder.planCells();
        int[] pairs = embedder.planPairs();
        byte[] bits = DctEmbedder.repeatCodeword(codeword);
        GridMapper grid = new GridMapper(W, H);
        Dct2D dct = new Dct2D(ContractConstants.DCT_BLOCK_SIZE);

        byte[] replay = new byte[W * H];
        Arrays.fill(replay, (byte) yFill);
        for (int i = 0; i < points.size(); i++) {
            JsonNode pt = points.get(i);
            int[] xy = grid.cellToBlock(cells[i]);
            double[] block = DctEmbedder.extractBlock(replay, W, xy[0], xy[1]);
            dct.forward(block);
            int[][] pair = ContractConstants.DCT_PAIRS[pairs[i]];
            int idxA = pair[0][0] * ContractConstants.DCT_BLOCK_SIZE + pair[0][1];
            int idxB = pair[1][0] * ContractConstants.DCT_BLOCK_SIZE + pair[1][1];
            double aBefore = block[idxA];
            double bBefore = block[idxB];
            PairModulator.embedBit(block, pairs[i], bits[i] & 1, delta);
            double aAfter = block[idxA];
            double bAfter = block[idxB];
            dct.inverse(block);
            DctEmbedder.writeBlock(replay, W, xy[0], xy[1], block);

            String prefix = id + " point[" + i + "]";
            assertEquals(pt.path("i").asInt(), i, prefix + ".i");
            assertEquals(pt.path("cell").asInt(), cells[i], prefix + ".cell");
            assertEquals(pt.path("pair_index").asInt(), pairs[i], prefix + ".pair_index");
            assertEquals(pt.path("bit").asInt(), bits[i] & 1, prefix + ".bit");
            assertEquals(pt.path("before").get(0).asDouble(), aBefore, COEF_TOLERANCE, prefix + ".before[a]");
            assertEquals(pt.path("before").get(1).asDouble(), bBefore, COEF_TOLERANCE, prefix + ".before[b]");
            assertEquals(pt.path("after").get(0).asDouble(), aAfter, COEF_TOLERANCE, prefix + ".after[a]");
            assertEquals(pt.path("after").get(1).asDouble(), bAfter, COEF_TOLERANCE, prefix + ".after[b]");
        }
    }
}
