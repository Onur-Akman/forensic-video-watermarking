package com.specter.embedder.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * HMAC-SHA256 counter stream PRNG. Contract section 2.3.
 *
 * prng_block(key, context, counter) = HMAC-SHA256(key, context_bytes || uint32_be(counter))
 *
 * Permutation Fisher-Yates ile uretilir. Modulo bias rejection sampling ile elenir.
 * java.util.Random / Math.random() **kullanilamaz** (contract section 2.3 yasak).
 */
public final class Prng {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final long UINT32_RANGE = 1L << 32;

    private final SecretKeySpec key;
    private final byte[] contextBytes;

    private long counter = 0L;
    private byte[] currentBlock = new byte[0];
    private int blockOffset = 0;

    public Prng(byte[] keyBytes, String context) {
        this.key = new SecretKeySpec(keyBytes, HMAC_ALG);
        this.contextBytes = context.getBytes(StandardCharsets.US_ASCII);
    }

    public byte nextByte() {
        if (blockOffset >= currentBlock.length) {
            refill();
        }
        return currentBlock[blockOffset++];
    }

    public long nextUint32() {
        long v = 0L;
        for (int i = 0; i < 4; i++) {
            v = (v << 8) | (nextByte() & 0xFFL);
        }
        return v;
    }

    /** Uniform [0, n) rejection sampling ile. */
    public int boundedInt(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("bound must be > 0, got " + n);
        }
        long limit = UINT32_RANGE - (UINT32_RANGE % n);
        while (true) {
            long v = nextUint32();
            if (v < limit) {
                return (int) (v % n);
            }
        }
    }

    /** [0..n) uzerinde Fisher-Yates permutation. */
    public int[] permutation(int n) {
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) {
            perm[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = boundedInt(i + 1);
            int tmp = perm[i];
            perm[i] = perm[j];
            perm[j] = tmp;
        }
        return perm;
    }

    /** [0..n) icinden ilk k farkli eleman (degisimsiz ornekleme). */
    public int[] sampleWithoutReplacement(int n, int k) {
        if (k > n) {
            throw new IllegalArgumentException("k=" + k + " > n=" + n);
        }
        int[] perm = permutation(n);
        int[] out = new int[k];
        System.arraycopy(perm, 0, out, 0, k);
        return out;
    }

    private void refill() {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(key);
            mac.update(contextBytes);
            mac.update(ByteBuffer.allocate(4).putInt((int) counter).array());
            currentBlock = mac.doFinal();
            counter++;
            blockOffset = 0;
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
