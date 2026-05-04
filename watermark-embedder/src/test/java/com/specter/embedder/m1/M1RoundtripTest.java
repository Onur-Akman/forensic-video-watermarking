package com.specter.embedder.m1;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.config.EmbedderProperties;
import com.specter.embedder.service.DctEmbedder;
import com.specter.embedder.service.KeyManager;
import com.specter.embedder.service.PayloadEncoder;
import com.specter.embedder.service.PsnrCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone 1 — Statik Kare PoC (contract section 10.1).
 * Tek 1080p Y duzlemi uzerinde embed -> internal extract roundtrip.
 *
 * <p>Acceptance kriterleri (section 10.1 + section 10.0 ozet tablosu):
 * <ul>
 *   <li>Bit-perfect ID geri okuma</li>
 *   <li>auth_tag_valid = true</li>
 *   <li>PSNR > 40 dB</li>
 * </ul>
 *
 * <p>BufferedImage/PNG katmani M1 senaryosu icin gereksiz; uniform-gray byte[]
 * Y duzlemi ayni rigor'u verir ve algoritma testini I/O katmanindan ayirir.
 */
class M1RoundtripTest {

    private static final String DUMMY_MASTER_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final int W = 1920;
    private static final int H = 1080;
    private static final long TEST_ID = 0x5C2A91FEL;

    private KeyManager keyManager;
    private DctEmbedder embedder;
    private PayloadEncoder payloadEncoder;

    @BeforeEach
    void setUp() {
        EmbedderProperties props = new EmbedderProperties(
                DUMMY_MASTER_HEX, ".", ContractConstants.H264_CRF_DEFAULT, ContractConstants.CONTRACT_VERSION);
        keyManager = new KeyManager(props);
        embedder = new DctEmbedder(keyManager, new PsnrCalculator());
        payloadEncoder = new PayloadEncoder(keyManager);
    }

    @Test
    void uniformGray1080p_embedExtract_bitPerfectId_authValid_psnrAboveFloor() {
        byte[] y = new byte[W * H];
        Arrays.fill(y, (byte) 128);

        byte[] codeword = payloadEncoder.encode(TEST_ID);
        DctEmbedder.EmbedResult result = embedder.embedIntoYPlane(y, W, H, codeword, ContractConstants.DELTA);

        // M1 acceptance #2: PSNR > 40 dB on single uncompressed frame
        assertTrue(result.embedded(), "uniform gray @ DELTA=12 must not skip");
        assertTrue(result.psnrDb() > ContractConstants.PSNR_FLOOR_DB,
                "PSNR " + result.psnrDb() + " dB must exceed floor " + ContractConstants.PSNR_FLOOR_DB);

        // M1 acceptance #1 + #3: bit-perfect ID + auth_tag_valid
        Roundtrip.Result extracted = Roundtrip.extract(y, W, H, keyManager);
        assertTrue(extracted.authTagValid(), "auth_tag_valid must be true");
        assertEquals(TEST_ID, extracted.watermarkId(),
                String.format("expected ID 0x%08X, got 0x%08X", TEST_ID, extracted.watermarkId()));
        // Section 10.0 hedef: M1 confidence >= 0.95 (statik kare, gurultusuz)
        assertTrue(extracted.bitConfidence() >= 0.95,
                "M1 bit confidence " + extracted.bitConfidence() + " must be >= 0.95");
    }

    @Test
    void psnrViolation_skipsFrame_andRevertsYPlane() {
        // Ekstrem delta ile PSNR < 40 dB tetiklenir; embedder revert + skip yapar.
        byte[] y = new byte[W * H];
        Arrays.fill(y, (byte) 128);
        byte[] originalY = y.clone();

        byte[] codeword = payloadEncoder.encode(TEST_ID);
        DctEmbedder.EmbedResult result = embedder.embedIntoYPlane(y, W, H, codeword, 800.0);

        assertFalse(result.embedded(), "extreme delta must trigger skip");
        assertTrue(result.psnrDb() < ContractConstants.PSNR_FLOOR_DB,
                "skip path PSNR " + result.psnrDb() + " must be below floor");
        assertArrayEquals(originalY, y, "yPlane must be reverted to original on skip");
    }
}
