package com.specter.embedder.core.dct;

import com.specter.embedder.config.ContractConstants;

/**
 * DCT pair modulation kurallari (contract section 4.2).
 *
 * a, b = secilen pair'in iki katsayisi.
 *   bit 1 hedef:  a - b >= DELTA
 *   bit 0 hedef:  b - a >= DELTA
 * Hedef saglanmiyorsa (a, b) ortalamayi koruyacak sekilde simetrik olarak ayarlanir.
 */
public final class PairModulator {

    private PairModulator() {
    }

    public static void embedBit(double[] block, int pairIndex, int bit, double delta) {
        int[][] pair = ContractConstants.DCT_PAIRS[pairIndex];
        int idxA = pair[0][0] * ContractConstants.DCT_BLOCK_SIZE + pair[0][1];
        int idxB = pair[1][0] * ContractConstants.DCT_BLOCK_SIZE + pair[1][1];
        double a = block[idxA];
        double b = block[idxB];
        double diff = a - b;
        boolean satisfied = (bit == 1) ? (diff >= delta) : (-diff >= delta);
        if (satisfied) {
            return;
        }
        double mean = (a + b) / 2.0;
        double half = delta / 2.0;
        if (bit == 1) {
            block[idxA] = mean + half;
            block[idxB] = mean - half;
        } else {
            block[idxA] = mean - half;
            block[idxB] = mean + half;
        }
    }

    /** Extractor tarafi soft-vote icin (a - b) ham fark. Embedder'da test/dogrulama icin. */
    public static double readDifference(double[] block, int pairIndex) {
        int[][] pair = ContractConstants.DCT_PAIRS[pairIndex];
        int idxA = pair[0][0] * ContractConstants.DCT_BLOCK_SIZE + pair[0][1];
        int idxB = pair[1][0] * ContractConstants.DCT_BLOCK_SIZE + pair[1][1];
        return block[idxA] - block[idxB];
    }
}
