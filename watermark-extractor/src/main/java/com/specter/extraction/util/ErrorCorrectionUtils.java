package com.specter.extraction.util;

import com.specter.extraction.config.WatermarkConfig;

/**
 * Hamming(7,4) + interleaver full pipeline for the 84-bit watermark codeword.
 *
 * Extraction flow (contract §5.1 steps 5-7):
 *   1. softVotes252 → combine 3 repetitions → 84 combined soft values
 *   2. Deinterleave the 84 soft values (using interleave_key PRNG)
 *   3. Hamming(7,4) hard-decode 12 nibbles → 48-bit raw_packet
 *   4. Split: watermark_id (32 bit) || auth_tag (16 bit)
 *
 * Embedding flow (used by M1TestVector only — embedder is Onur's service):
 *   1. Build 48-bit raw_packet from watermark_id + auth_tag
 *   2. Hamming(7,4) encode 12 nibbles → 84-bit codeword
 *   3. Interleave → broadcast to 3 repetitions = 252 bits per frame
 */
public final class ErrorCorrectionUtils {

    private ErrorCorrectionUtils() {}

    // ─────────────────────────────────────────────────────────────────
    // Extraction (decode) path
    // ─────────────────────────────────────────────────────────────────

    /**
     * Full FEC decode: 252 accumulated soft votes → RawPacket.
     *
     * @param softVotes252   252 summed soft votes (across all scanned frames).
     *                       Layout: [rep0_b0..rep0_b83 | rep1_b0..rep1_b83 | rep2_b0..rep2_b83]
     * @param interleaverKey 32-byte key derived from SPECTER_WM_KEY (INFO_INTERLEAVER)
     * @return decoded RawPacket containing watermarkId and authTag
     */
    public static RawPacket decode(double[] softVotes252, byte[] interleaverKey) {
        if (softVotes252.length != WatermarkConfig.EMBED_POINTS_PER_FRAME) {
            throw new IllegalArgumentException(
                    "Expected " + WatermarkConfig.EMBED_POINTS_PER_FRAME +
                    " soft votes, got " + softVotes252.length);
        }
        // Step 1: Combine 3 repetition slots → 84 combined soft values
        double[] combined = combineRepetitions(softVotes252);

        // Step 2: Deinterleave
        int[] perm = InterleaverUtils.buildPermutation(interleaverKey);
        double[] codewordSoft = InterleaverUtils.deinterleave(combined, perm);

        // Step 3: Hamming(7,4) decode 12 nibbles
        int[] nibbles = hammingDecode84(codewordSoft);

        // Step 4: Assemble 48-bit raw_packet → watermarkId + authTag
        return assemblePacket(nibbles);
    }

    // ─────────────────────────────────────────────────────────────────
    // Embedding (encode) path — used by M1TestVector and tests only
    // ─────────────────────────────────────────────────────────────────

    /**
     * Encode a raw packet to an 84-bit interleaved codeword.
     * Used by M1TestVector to simulate the embedding side without Onur's service.
     *
     * @param watermarkId    unsigned 32-bit ID (stored in long)
     * @param authTag        16-bit auth tag (lower 16 bits used)
     * @param interleaverKey 32-byte key from HKDF
     * @return 84-bit interleaved codeword ready to embed (repeated 3x = 252 bits per frame)
     */
    public static int[] encode(long watermarkId, int authTag, byte[] interleaverKey) {
        int[] rawBits    = buildRawPacketBits(watermarkId, authTag);
        int[] codeword84 = hammingEncode48(rawBits);
        int[] perm       = InterleaverUtils.buildPermutation(interleaverKey);
        return InterleaverUtils.interleave(codeword84, perm);
    }

    // ─────────────────────────────────────────────────────────────────
    // Internal — repetition combining
    // ─────────────────────────────────────────────────────────────────

    static double[] combineRepetitions(double[] softVotes252) {
        int cw = WatermarkConfig.CODEWORD_BITS; // 84
        double[] combined = new double[cw];
        for (int rep = 0; rep < WatermarkConfig.REPEAT_PER_FRAME; rep++) {
            int offset = rep * cw;
            for (int i = 0; i < cw; i++) combined[i] += softVotes252[offset + i];
        }
        return combined;
    }

    // ─────────────────────────────────────────────────────────────────
    // Internal — Hamming encode path (48 → 84 bits)
    // ─────────────────────────────────────────────────────────────────

    static int[] buildRawPacketBits(long watermarkId, int authTag) {
        int[] bits = new int[WatermarkConfig.RAW_PACKET_BITS]; // 48
        // watermark_id bits 0..31 (MSB first, big-endian)
        for (int i = 0; i < 32; i++) bits[i]      = (int) ((watermarkId >> (31 - i)) & 1L);
        // auth_tag bits 32..47 (MSB first)
        for (int i = 0; i < 16; i++) bits[32 + i] = (authTag >> (15 - i)) & 1;
        return bits;
    }

    static int[] hammingEncode48(int[] rawBits48) {
        if (rawBits48.length != WatermarkConfig.RAW_PACKET_BITS)
            throw new IllegalArgumentException("Expected 48 raw bits");
        int[] codeword = new int[WatermarkConfig.CODEWORD_BITS]; // 84
        for (int n = 0; n < WatermarkConfig.RAW_PACKET_NIBBLES; n++) { // 12 nibbles
            int nibble = (rawBits48[n * 4]     << 3)
                       | (rawBits48[n * 4 + 1] << 2)
                       | (rawBits48[n * 4 + 2] << 1)
                       |  rawBits48[n * 4 + 3];
            System.arraycopy(HammingCodec.encodeNibble(nibble), 0, codeword, n * 7, 7);
        }
        return codeword;
    }

    // ─────────────────────────────────────────────────────────────────
    // Internal — Hamming decode path (84 → 48 bits)
    // ─────────────────────────────────────────────────────────────────

    static int[] hammingDecode84(double[] codewordSoft84) {
        if (codewordSoft84.length != WatermarkConfig.CODEWORD_BITS)
            throw new IllegalArgumentException("Expected 84 codeword soft values");
        // Soft maximum-likelihood decode: for each 7-bit nibble slot, score all 16
        // candidate codewords against the soft vote vector and pick the highest. This
        // beats hard-threshold + single-bit Hamming syndrome correction whenever two
        // or more bits are weakly voted in the same nibble (e.g. M3 crop after
        // re-encode), which the syndrome approach mis-corrects to a neighbouring
        // codeword.
        int[] nibbles = new int[WatermarkConfig.RAW_PACKET_NIBBLES]; // 12
        for (int n = 0; n < WatermarkConfig.RAW_PACKET_NIBBLES; n++) {
            int bestNibble = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int candidate = 0; candidate < 16; candidate++) {
                int[] encoded = HammingCodec.encodeNibble(candidate);
                double score = 0.0;
                for (int b = 0; b < 7; b++) {
                    double vote = codewordSoft84[n * 7 + b];
                    score += encoded[b] == 1 ? vote : -vote;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestNibble = candidate;
                }
            }
            nibbles[n] = bestNibble;
        }
        return nibbles;
    }

    static RawPacket assemblePacket(int[] nibbles12) {
        if (nibbles12.length != WatermarkConfig.RAW_PACKET_NIBBLES)
            throw new IllegalArgumentException("Expected 12 nibbles");
        long packet48 = 0L;
        for (int n : nibbles12) packet48 = (packet48 << 4) | (n & 0xF);
        // Upper 32 bits = watermark_id, lower 16 bits = auth_tag
        long watermarkId = (packet48 >> 16) & 0xFFFFFFFFL;
        int  authTag     = (int) (packet48 & 0xFFFFL);
        return new RawPacket(watermarkId, authTag);
    }

    // ─────────────────────────────────────────────────────────────────
    // Backward-compat stub (used by old pipeline until Faz 3 removes it)
    // ─────────────────────────────────────────────────────────────────

    /**
     * @deprecated Will be removed in Faz 3 when DctWatermarkExtractionServiceImpl is rewritten.
     */
    @Deprecated
    public static int[] decodeSoft(double[] softBits, int payloadSize) {
        int size = Math.min(payloadSize, softBits.length);
        int[] bits = new int[size];
        for (int i = 0; i < size; i++) bits[i] = softBits[i] >= 0 ? 1 : 0;
        return bits;
    }

    // ─────────────────────────────────────────────────────────────────
    // RawPacket — decoded 48-bit packet result
    // ─────────────────────────────────────────────────────────────────

    /**
     * Result of a successful FEC decode.
     *
     * watermarkId : unsigned 32-bit (range 0x00000000..0xFFFFFFFF, stored in long)
     * authTag     : 16-bit HMAC tag (stored in int, lower 16 bits)
     */
    public static final class RawPacket {
        public final long watermarkId;
        public final int  authTag;

        public RawPacket(long watermarkId, int authTag) {
            this.watermarkId = watermarkId & 0xFFFFFFFFL;
            this.authTag     = authTag & 0xFFFF;
        }

        /** Hex string representation, e.g. "0xA3F21B04" */
        public String watermarkIdHex() {
            return String.format("0x%08X", watermarkId);
        }

        @Override
        public String toString() {
            return String.format("RawPacket{watermarkId=%s, authTag=0x%04X}", watermarkIdHex(), authTag);
        }
    }
}
