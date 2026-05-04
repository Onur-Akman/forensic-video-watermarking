package com.specter.embedder.config;

import java.nio.charset.StandardCharsets;

/**
 * Contract v1 (docs/contract-new.md, section 12) frozen constants.
 * Bu degerleri degistirmek icin kontrat v2 surumune cikarilmalidir.
 */
public final class ContractConstants {

    private ContractConstants() {
    }

    public static final String CONTRACT_VERSION = "v1";

    // Section 1 - payload
    public static final int WATERMARK_ID_BITS = 32;
    public static final int AUTH_TAG_BITS = 16;
    public static final int RAW_PACKET_BITS = WATERMARK_ID_BITS + AUTH_TAG_BITS; // 48
    public static final int CODEWORD_BITS = 84;
    public static final int REPEAT_PER_FRAME = 3;
    public static final int EMBED_POINTS_PER_FRAME = CODEWORD_BITS * REPEAT_PER_FRAME; // 252

    // Section 2 - keys
    public static final String KEY_ENV = "SPECTER_WM_KEY";
    public static final int KEY_BYTES = 32;
    public static final int KEY_HEX_LENGTH = 64;
    public static final byte[] HKDF_SALT = "project-specter-v1".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] HKDF_INFO_AUTH = "payload-auth".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] HKDF_INFO_PRNG = "block-selection".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] HKDF_INFO_INTERLEAVER = "bit-interleaver".getBytes(StandardCharsets.US_ASCII);

    public static final String PRNG_CTX_INTERLEAVER = "specter-v1/interleaver/84";
    public static final String PRNG_CTX_CELL_MAP = "specter-v1/cell-map/48x27/252";
    public static final String PRNG_CTX_PAIR_MAP = "specter-v1/pair-map/252";

    // Section 3 - grid
    public static final int GRID_COLS = 48;
    public static final int GRID_ROWS = 27;
    public static final int GRID_CELLS = GRID_COLS * GRID_ROWS; // 1296
    public static final double SAFE_MARGIN = 0.10;

    // Section 4 - DCT
    public static final int DCT_BLOCK_SIZE = 8;
    public static final double DELTA = 12.0;
    public static final double DELTA_FLOOR = 8.0; // section 10.2 adaptif alt sinir
    public static final double PSNR_FLOOR_DB = 40.0;
    public static final double PSNR_VIOLATION_RATIO_LIMIT = 0.05; // section 6.3 PSNR_VIOLATION esigi

    /** 0-indexed (row, col) DCT pair koordinatlari, 8x8 blok icinde. Section 4.1. */
    public static final int[][][] DCT_PAIRS = {
            {{2, 3}, {3, 2}},
            {{1, 4}, {4, 1}},
            {{2, 4}, {4, 2}},
            {{3, 4}, {4, 3}}
    };

    // Section 4.4 - output
    public static final int H264_CRF_MIN = 16;
    public static final int H264_CRF_MAX = 22;
    public static final int H264_CRF_DEFAULT = 18;
}
