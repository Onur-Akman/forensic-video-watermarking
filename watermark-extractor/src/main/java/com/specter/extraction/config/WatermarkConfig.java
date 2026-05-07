package com.specter.extraction.config;

import org.springframework.stereotype.Component;

/**
 * Immutable watermark contract constants for the Extraction Service.
 *
 * Contract §12: These values are FROZEN in v1 and must not be changed
 * without bumping the contract version to v2 (requires both parties' approval).
 *
 * Do NOT expose these values via env var or application.yml — they are
 * part of the signed inter-service contract, not deployment configuration.
 */
@Component
public class WatermarkConfig {

    // ─────────────────────────────────────────────────────────────────
    // §12 — Immutable constants (contract-frozen)
    // ─────────────────────────────────────────────────────────────────

    /** Watermark ID size in bits (unsigned 32-bit integer). */
    public static final int WATERMARK_ID_BITS = 32;

    /** HMAC auth tag size in bits (first 16 bits of HMAC-SHA256). */
    public static final int AUTH_TAG_BITS = 16;

    /** Raw packet = watermark_id || auth_tag (32 + 16 = 48 bits). */
    public static final int RAW_PACKET_BITS = 48;

    /** Number of 4-bit nibbles in the raw packet (48 / 4 = 12). */
    public static final int RAW_PACKET_NIBBLES = RAW_PACKET_BITS / 4;

    /** Hamming(7,4) codeword bits per nibble. */
    public static final int HAMMING_CODEWORD_BITS = 7;

    /** Total FEC-encoded bits (12 nibbles × 7 bits = 84). */
    public static final int CODEWORD_BITS = RAW_PACKET_NIBBLES * HAMMING_CODEWORD_BITS; // 84

    /** Each eligible frame carries the 84-bit codeword 3 times. */
    public static final int REPEAT_PER_FRAME = 3;

    /** Total embedding points per frame (84 × 3 = 252). */
    public static final int EMBED_POINTS_PER_FRAME = CODEWORD_BITS * REPEAT_PER_FRAME; // 252

    /** Grid columns (horizontal cells). */
    public static final int GRID_COLS = 48;

    /** Grid rows (vertical cells). Total cells = 48 × 27 = 1296. */
    public static final int GRID_ROWS = 27;

    /** Total grid cells. */
    public static final int GRID_TOTAL_CELLS = GRID_COLS * GRID_ROWS; // 1296

    /** Safe margin fraction on each edge (10% of frame width/height). */
    public static final double SAFE_MARGIN = 0.10;

    /** DCT block size (8×8 pixels). */
    public static final int DCT_BLOCK_SIZE = 8;

    /**
     * Embedding strength (DELTA). Used by extraction to read the pair difference.
     * Bit 1 means (a - b) was set to >= DELTA; bit 0 means (b - a) >= DELTA.
     */
    public static final double DELTA = 12.0;

    /**
     * Minimum DELTA for adaptive embedding in the Embedding Service.
     * Extraction does not use this directly, but it defines the minimum
     * meaningful (a - b) difference that counts as a strong bit signal.
     */
    public static final double DELTA_MIN = 8.0;

    /**
     * DCT coefficient pairs used for embedding (0-indexed row, col within 8×8 block).
     * Contract §4.1 — four fixed mid-frequency pairs:
     *   P0 = ((2,3),(3,2))
     *   P1 = ((1,4),(4,1))
     *   P2 = ((2,4),(4,2))
     *   P3 = ((3,4),(4,3))
     *
     * Format: [pairIndex][0=u1/v1 or 1=u2/v2][0=row or 1=col]
     */
    public static final int[][][] DCT_PAIRS = {
        { {2, 3}, {3, 2} },  // P0
        { {1, 4}, {4, 1} },  // P1
        { {2, 4}, {4, 2} },  // P2
        { {3, 4}, {4, 3} }   // P3
    };

    /** Number of available DCT pair sets. */
    public static final int DCT_PAIR_COUNT = DCT_PAIRS.length; // 4

    // ─────────────────────────────────────────────────────────────────
    // §2.4 — PRNG context strings (must match Embedding Service exactly)
    // ─────────────────────────────────────────────────────────────────

    /** PRNG context for the 84-bit interleaver permutation. */
    public static final String CTX_INTERLEAVER = "specter-v1/interleaver/84";

    /** PRNG context for selecting 252 grid cells per frame. */
    public static final String CTX_CELL_MAP    = "specter-v1/cell-map/48x27/252";

    /** PRNG context for selecting the DCT pair index per cell. */
    public static final String CTX_PAIR_MAP    = "specter-v1/pair-map/252";

    // ─────────────────────────────────────────────────────────────────
    // §9 — Deployment-configurable values (via env var, not frozen)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Maximum number of frames to scan for watermark extraction.
     * Contract §9: EXTRACTOR_MAX_FRAMES (default 90).
     * Override via env var: EXTRACTOR_MAX_FRAMES
     */
    private final int maxFrames;

    /**
     * Minimum confidence threshold for marking a result as valid.
     * Not in the contract constants table; service-level decision.
     */
    private final double confidenceThreshold;

    public WatermarkConfig() {
        this.maxFrames           = readIntEnv("EXTRACTOR_MAX_FRAMES", 180);
        this.confidenceThreshold = 0.80; // slightly above the M2 minimum (0.90 target)
    }

    public int getMaxFrames() { return maxFrames; }
    public double getConfidenceThreshold() { return confidenceThreshold; }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    private static int readIntEnv(String name, int defaultValue) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
