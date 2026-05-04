package com.specter.embedder.service;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.core.crypto.Prng;
import org.springframework.stereotype.Component;

/**
 * Tek frame'in Y kanalina 84-bit interleaved codeword'u 3 tekrar ile
 * 252 noktada DCT pair modulation kullanarak gomer (contract section 4.3).
 *
 * NOT: Full per-frame DCT loop M2'de doldurulacak; bu skeletonda sadece
 * PRNG plani (cell + pair) ve codeword tekrari hazir, gercek DCT/IDCT
 * adimi UnsupportedOperationException atar.
 */
@Component
public class DctEmbedder {

    private final KeyManager keyManager;

    public DctEmbedder(KeyManager keyManager) {
        this.keyManager = keyManager;
    }

    /**
     * Y duzlemine watermark gomer; islem sonrasi PSNR (dB) doner.
     *
     * @param yPlane     row-major 8-bit luma (length = width * height)
     * @param width      frame genisligi
     * @param height     frame yuksekligi
     * @param codeword84 interleaved codeword (84 bit, contract section 4.3 girdi)
     * @param delta      embed strength; section 10.2'ye gore 8.0..12.0 arasinda adaptif
     * @return PSNR (dB) - watermark uygulanmis Y'nin orjinaline gore
     */
    public double embedIntoYPlane(byte[] yPlane, int width, int height, byte[] codeword84, double delta) {
        if (codeword84.length != ContractConstants.CODEWORD_BITS) {
            throw new IllegalArgumentException("codeword must be " + ContractConstants.CODEWORD_BITS
                    + " bits, got " + codeword84.length);
        }
        int[] cells = planCells();
        int[] pairs = planPairs();
        byte[] bitsToEmbed = repeatCodeword(codeword84);

        // TODO(M2): per-cell DCT/IDCT, modulation, clamp+round, PSNR
        //   for i in [0..252):
        //     block = extract 8x8 from y at cells[i]
        //     dct.forward(block)
        //     PairModulator.embedBit(block, pairs[i], bitsToEmbed[i], delta)
        //     dct.inverse(block)
        //     write block back with clamp [0,255] + round
        //   compute PSNR via PsnrCalculator.psnrDb(originalY, watermarkedY)
        throw new UnsupportedOperationException(
                "DctEmbedder.embedIntoYPlane: M2 - DCT loop pending (cells=" + cells.length
                        + " pairs=" + pairs.length + " bits=" + bitsToEmbed.length + ")");
    }

    private int[] planCells() {
        return new Prng(keyManager.prngKey(), ContractConstants.PRNG_CTX_CELL_MAP)
                .sampleWithoutReplacement(ContractConstants.GRID_CELLS, ContractConstants.EMBED_POINTS_PER_FRAME);
    }

    private int[] planPairs() {
        Prng prng = new Prng(keyManager.prngKey(), ContractConstants.PRNG_CTX_PAIR_MAP);
        int n = ContractConstants.EMBED_POINTS_PER_FRAME;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = prng.boundedInt(ContractConstants.DCT_PAIRS.length);
        }
        return out;
    }

    private static byte[] repeatCodeword(byte[] codeword) {
        byte[] out = new byte[ContractConstants.EMBED_POINTS_PER_FRAME];
        for (int r = 0; r < ContractConstants.REPEAT_PER_FRAME; r++) {
            System.arraycopy(codeword, 0, out, r * codeword.length, codeword.length);
        }
        return out;
    }
}
