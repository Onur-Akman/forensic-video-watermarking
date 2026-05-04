package com.specter.embedder.service;

import com.specter.embedder.config.ContractConstants;
import com.specter.embedder.core.crypto.Prng;
import com.specter.embedder.core.dct.Dct2D;
import com.specter.embedder.core.dct.PairModulator;
import com.specter.embedder.core.grid.GridMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tek frame'in Y kanalina 84-bit interleaved codeword'u 3 tekrar ile
 * 252 noktada DCT pair modulation kullanarak gomer (contract section 4.3).
 *
 * <p>Frame-skip path (contract section 10.2 path (b)): islem sonrasi PSNR
 * {@link ContractConstants#PSNR_FLOOR_DB} altinda kalirsa Y duzlemi orijinaline
 * geri yuklenir ve {@link EmbedResult#embedded()} = false donulur. Caller
 * (VideoIO) o frame'i atlar; paket diger frame'lerden zaten redundant okunur.
 */
@Component
public class DctEmbedder {

    private static final Logger log = LoggerFactory.getLogger(DctEmbedder.class);

    private final KeyManager keyManager;
    private final PsnrCalculator psnrCalculator;

    public DctEmbedder(KeyManager keyManager, PsnrCalculator psnrCalculator) {
        this.keyManager = keyManager;
        this.psnrCalculator = psnrCalculator;
    }

    /**
     * Y duzlemine watermark gomer; islem sonrasi PSNR esigi kontrolu uygular.
     *
     * @param yPlane     row-major 8-bit luma (length = width * height); IN-PLACE modifiye edilir
     * @param width      frame genisligi
     * @param height     frame yuksekligi
     * @param codeword84 interleaved codeword (84 bit, contract section 4.3 girdi)
     * @param delta      embed strength (production: {@link ContractConstants#DELTA})
     * @return embedded=true (psnr >= floor) veya embedded=false (revert edildi)
     */
    public EmbedResult embedIntoYPlane(byte[] yPlane, int width, int height, byte[] codeword84, double delta) {
        if (codeword84.length != ContractConstants.CODEWORD_BITS) {
            throw new IllegalArgumentException("codeword must be " + ContractConstants.CODEWORD_BITS
                    + " bits, got " + codeword84.length);
        }
        if (yPlane.length != width * height) {
            throw new IllegalArgumentException("yPlane length " + yPlane.length
                    + " != width*height " + (width * height));
        }
        int[] cells = planCells();
        int[] pairs = planPairs();
        byte[] bitsToEmbed = repeatCodeword(codeword84);

        byte[] originalY = yPlane.clone();
        GridMapper grid = new GridMapper(width, height);
        Dct2D dct = new Dct2D(ContractConstants.DCT_BLOCK_SIZE);

        for (int i = 0; i < ContractConstants.EMBED_POINTS_PER_FRAME; i++) {
            int[] xy = grid.cellToBlock(cells[i]);
            double[] block = extractBlock(yPlane, width, xy[0], xy[1]);
            dct.forward(block);
            PairModulator.embedBit(block, pairs[i], bitsToEmbed[i] & 1, delta);
            dct.inverse(block);
            writeBlock(yPlane, width, xy[0], xy[1], block);
        }

        double mse = psnrCalculator.mse(originalY, yPlane);
        double psnr = psnrCalculator.psnrDb(mse);
        if (psnr < ContractConstants.PSNR_FLOOR_DB) {
            System.arraycopy(originalY, 0, yPlane, 0, yPlane.length);
            log.warn("frame skipped: PSNR {} dB < floor {} dB (contract section 10.2 path b)",
                    String.format("%.2f", psnr), ContractConstants.PSNR_FLOOR_DB);
            return new EmbedResult(false, psnr, mse);
        }
        return new EmbedResult(true, psnr, mse);
    }

    /** Cell index list deterministik PRNG ile. Diagnostik/test icin paylasilir. */
    public int[] planCells() {
        return new Prng(keyManager.prngKey(), ContractConstants.PRNG_CTX_CELL_MAP)
                .sampleWithoutReplacement(ContractConstants.GRID_CELLS, ContractConstants.EMBED_POINTS_PER_FRAME);
    }

    /** Pair index list (her cell icin 0..3). */
    public int[] planPairs() {
        Prng prng = new Prng(keyManager.prngKey(), ContractConstants.PRNG_CTX_PAIR_MAP);
        int n = ContractConstants.EMBED_POINTS_PER_FRAME;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = prng.boundedInt(ContractConstants.DCT_PAIRS.length);
        }
        return out;
    }

    /** 84-bit codeword'u 3 tekrar ile 252-bit'e genisletir. */
    public static byte[] repeatCodeword(byte[] codeword) {
        byte[] out = new byte[ContractConstants.EMBED_POINTS_PER_FRAME];
        for (int r = 0; r < ContractConstants.REPEAT_PER_FRAME; r++) {
            System.arraycopy(codeword, 0, out, r * codeword.length, codeword.length);
        }
        return out;
    }

    /** 8x8 piksel block'unu Y duzleminden flat double[] (length 64) olarak okur. */
    public static double[] extractBlock(byte[] y, int width, int x, int y0) {
        int b = ContractConstants.DCT_BLOCK_SIZE;
        double[] block = new double[b * b];
        for (int row = 0; row < b; row++) {
            int srcOffset = (y0 + row) * width + x;
            for (int col = 0; col < b; col++) {
                block[row * b + col] = y[srcOffset + col] & 0xFF;
            }
        }
        return block;
    }

    /** 8x8 block'u Y duzlemine round + clamp [0,255] ile yazar. */
    public static void writeBlock(byte[] y, int width, int x, int y0, double[] block) {
        int b = ContractConstants.DCT_BLOCK_SIZE;
        for (int row = 0; row < b; row++) {
            int dstOffset = (y0 + row) * width + x;
            for (int col = 0; col < b; col++) {
                int v = (int) Math.round(block[row * b + col]);
                if (v < 0) v = 0;
                else if (v > 255) v = 255;
                y[dstOffset + col] = (byte) v;
            }
        }
    }

    /** Embedding sonucu: skip durumunda yPlane orijinaline geri yuklenmistir.
     *  psnrDb ve mse PRE-revert degerlerdir (skip kararini tetikleyen olcumler). */
    public record EmbedResult(boolean embedded, double psnrDb, double mse) {
    }
}
