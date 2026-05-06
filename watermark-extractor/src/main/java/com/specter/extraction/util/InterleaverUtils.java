package com.specter.extraction.util;

import com.specter.extraction.config.WatermarkConfig;

/**
 * Bit interleaver/deinterleaver for the 84-bit watermark codeword.
 *
 * Contract §1 step 5:
 *   "The 84-bit codeword is reordered via a PRNG-derived permutation to prevent
 *    burst errors from concentrating in a single nibble."
 *
 * Both embedder and extractor call buildPermutation() with the same key
 * and get an identical perm[] array (HMAC-SHA256 is deterministic).
 *
 * Embedder:   interleaved[perm[i]] = codeword[i]
 * Extractor:  codeword[i] = received_interleaved[perm[i]]  (same perm, same operation)
 */
public final class InterleaverUtils {

    private InterleaverUtils() {}

    /**
     * Build the 84-element Fisher-Yates permutation from the interleave key.
     * Context string is frozen in contract §2.4: "specter-v1/interleaver/84"
     *
     * @param interleaverKey 32-byte key from HKDF (INFO_INTERLEAVER)
     * @return perm[] of length 84; perm[i] = destination position for source index i
     */
    public static int[] buildPermutation(byte[] interleaverKey) {
        SpectralPrng prng = new SpectralPrng(interleaverKey, WatermarkConfig.CTX_INTERLEAVER);
        return prng.generatePermutation(WatermarkConfig.CODEWORD_BITS); // 84
    }

    /**
     * Interleave: scatter codeword bits into interleaved positions.
     * Embedder uses this: interleaved[perm[i]] = src[i]
     *
     * @param src  84-bit source (codeword in natural order)
     * @param perm permutation from buildPermutation()
     * @return 84-bit interleaved array
     */
    public static int[] interleave(int[] src, int[] perm) {
        int[] out = new int[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[perm[i]];
        return out;
    }

    /**
     * Deinterleave: recover original codeword order from received bits.
     * Extractor uses this: codeword_soft[perm[i]] = received[i]
     *
     * Mathematical proof: if interleaved[i] = codeword[perm[i]],
     * then codeword[perm[i]] = interleaved[i] → reverses the gather.
     *
     * @param received 84 accumulated soft votes in interleaved order
     * @param perm     same permutation from buildPermutation()
     * @return 84 soft values in original codeword (natural nibble) order
     */
    public static double[] deinterleave(double[] received, int[] perm) {
        double[] out = new double[received.length];
        for (int i = 0; i < received.length; i++) out[perm[i]] = received[i];
        return out;
    }
}
