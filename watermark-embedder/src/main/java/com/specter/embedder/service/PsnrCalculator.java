package com.specter.embedder.service;

import org.springframework.stereotype.Component;

/**
 * MSE / PSNR hesaplari, contract section 3 formulleri.
 * Y duzlemleri unsigned 0..255 olarak yorumlanir.
 */
@Component
public class PsnrCalculator {

    private static final double MAX_PIXEL = 255.0;

    public double mse(byte[] original, byte[] watermarked) {
        if (original.length != watermarked.length) {
            throw new IllegalArgumentException("buffers must have equal length: "
                    + original.length + " vs " + watermarked.length);
        }
        long sumSquaredDiff = 0L;
        for (int i = 0; i < original.length; i++) {
            int diff = (original[i] & 0xFF) - (watermarked[i] & 0xFF);
            sumSquaredDiff += (long) diff * diff;
        }
        return (double) sumSquaredDiff / original.length;
    }

    public double psnrDb(double mse) {
        if (mse <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return 10.0 * Math.log10((MAX_PIXEL * MAX_PIXEL) / mse);
    }

    public double psnrDb(byte[] original, byte[] watermarked) {
        return psnrDb(mse(original, watermarked));
    }
}
