package com.specter.extraction.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Deterministic PRNG based on HMAC-SHA256 counter stream.
 *
 * Contract §2.3:
 *   prng_block(key, context, counter) =
 *       HMAC-SHA256(key, context_bytes || uint32_be(counter))
 *
 * Rules:
 * - Each call to the internal block function produces 32 bytes.
 * - Counter starts at 0 and increments once per block consumed.
 * - Modulo bias is eliminated with rejection sampling.
 * - Permutation is generated with Fisher-Yates.
 *
 * Why HMAC-SHA256 instead of java.util.Random?
 * - java.util.Random and SecureRandom(SHA1PRNG) are JVM-specific.
 *   The same seed produces different output on Python, Go, C++.
 * - HMAC-SHA256 counter stream is language-agnostic and bit-exact
 *   across all implementations, which the contract requires.
 */
public final class SpectralPrng {

    private final byte[] key;
    private final byte[] contextBytes;
    private int    counter   = 0;
    private byte[] buffer    = new byte[0]; // current unconsumed PRNG block
    private int    bufferPos = 0;

    // ─────────────────────────────────────────────────────────────────
    // Construction
    // ─────────────────────────────────────────────────────────────────

    /**
     * Create a new PRNG for a specific (key, context) pair.
     *
     * Each distinct context string produces an independent pseudorandom stream
     * from the same key, preventing stream-reuse across different usages.
     *
     * @param key     32-byte PRNG key (derived from SPECTER_WM_KEY via HKDF)
     * @param context ASCII context string, e.g. "specter-v1/cell-map/48x27/252"
     */
    public SpectralPrng(byte[] key, String context) {
        this.key          = Arrays.copyOf(key, key.length);
        this.contextBytes = context.getBytes(StandardCharsets.US_ASCII);
    }

    // ─────────────────────────────────────────────────────────────────
    // Core stream access
    // ─────────────────────────────────────────────────────────────────

    /**
     * Read the next byte from the pseudorandom stream (unsigned, 0-255).
     */
    public int nextByte() {
        if (bufferPos >= buffer.length) {
            refillBuffer();
        }
        return buffer[bufferPos++] & 0xFF;
    }

    /**
     * Read the next 4 bytes as an unsigned 32-bit integer (big-endian).
     */
    public long nextUint32() {
        long b0 = nextByte();
        long b1 = nextByte();
        long b2 = nextByte();
        long b3 = nextByte();
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    // ─────────────────────────────────────────────────────────────────
    // Uniform integer (rejection sampling — no modulo bias)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Return a uniform random integer in [0, n) with no modulo bias.
     *
     * Contract §2.3: "uint32 is drawn; if >= threshold M = 2^32 - (2^32 mod n), reject."
     *
     * @param n upper bound (exclusive), must be ≥ 1
     * @return random value in [0, n)
     */
    public int nextInt(int n) {
        if (n <= 0) throw new IllegalArgumentException("n must be positive, got: " + n);
        if (n == 1) return 0;

        // threshold = 2^32 - (2^32 mod n)
        // Use longs to avoid overflow: 2^32 = 4294967296L
        long threshold = 4294967296L - (4294967296L % n);

        while (true) {
            long val = nextUint32(); // 0 .. 2^32-1
            if (val < threshold) {
                return (int) (val % n);
            }
            // Reject and retry — prevents bias for non-power-of-2 n values
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Fisher-Yates permutation and distinct selection
    // ─────────────────────────────────────────────────────────────────

    /**
     * Generate a Fisher-Yates shuffle of indices [0, size).
     *
     * Contract §2.3: "Permutation is generated with Fisher-Yates."
     * Used for the interleaver and cell selection.
     *
     * @param size number of elements to permute
     * @return permuted index array of length `size`
     */
    public int[] generatePermutation(int size) {
        int[] perm = new int[size];
        for (int i = 0; i < size; i++) perm[i] = i;

        // Fisher-Yates: iterate backwards, swap each element with a random one
        // at or before its current position
        for (int i = size - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            int tmp = perm[i];
            perm[i] = perm[j];
            perm[j] = tmp;
        }
        return perm;
    }

    /**
     * Select `count` distinct indices from [0, totalItems) in PRNG order.
     *
     * Runs Fisher-Yates on the full range, returns the first `count` elements.
     * This is equivalent to sampling without replacement.
     *
     * @param totalItems pool size (e.g. 1296 for 48×27 grid)
     * @param count      number of distinct items to select (e.g. 252)
     * @return array of `count` distinct indices in PRNG-determined order
     */
    public int[] selectDistinct(int totalItems, int count) {
        if (count > totalItems) {
            throw new IllegalArgumentException(
                    "Cannot select " + count + " distinct items from pool of " + totalItems);
        }
        int[] perm = generatePermutation(totalItems);
        return Arrays.copyOf(perm, count);
    }

    // ─────────────────────────────────────────────────────────────────
    // Internal PRNG block generation
    // ─────────────────────────────────────────────────────────────────

    /**
     * Fill internal buffer with the next PRNG block.
     *
     * prng_block(key, context, counter) = HMAC-SHA256(key, context || uint32_be(counter))
     */
    private void refillBuffer() {
        byte[] counterBytes = HmacAuthUtils.uint32Be(counter);
        byte[] input = new byte[contextBytes.length + 4];
        System.arraycopy(contextBytes, 0, input, 0,                contextBytes.length);
        System.arraycopy(counterBytes, 0, input, contextBytes.length, 4);

        buffer    = KeyDerivationUtils.hmacSha256(key, input);
        bufferPos = 0;
        counter++;
    }
}
