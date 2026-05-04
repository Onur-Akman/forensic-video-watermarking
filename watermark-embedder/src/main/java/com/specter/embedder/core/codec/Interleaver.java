package com.specter.embedder.core.codec;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.core.crypto.Prng;

/**
 * 84-bit codeword interleaver. Permutation, contract section 1 / 2.4 uyarinca
 * `interleave_key` ile `PRNG_CTX_INTERLEAVER` context'inden Fisher-Yates ile uretilir.
 *
 * Yon: out[i] = input[permutation[i]]. Extractor inverse mapping ile deinterleave eder.
 */
public final class Interleaver {

    private final int[] permutation;

    public Interleaver(byte[] interleaveKey) {
        Prng prng = new Prng(interleaveKey, ContractConstants.PRNG_CTX_INTERLEAVER);
        this.permutation = prng.permutation(ContractConstants.CODEWORD_BITS);
    }

    public byte[] interleave(byte[] bits) {
        if (bits.length != permutation.length) {
            throw new IllegalArgumentException("expected " + permutation.length + " bits, got " + bits.length);
        }
        byte[] out = new byte[bits.length];
        for (int i = 0; i < bits.length; i++) {
            out[i] = bits[permutation[i]];
        }
        return out;
    }

    public int[] permutation() {
        return permutation.clone();
    }
}
