package com.specter.embedder.core.dct;

import org.jtransforms.dct.DoubleDCT_2D;

/**
 * Orthonormal 2D DCT-II (8x8 blok icin tipik kullanim). Contract section 4.
 * JTransforms `forward(data, true)` orthonormal scaling kullanir.
 */
public final class Dct2D {

    private final int size;
    private final DoubleDCT_2D engine;

    public Dct2D(int size) {
        this.size = size;
        this.engine = new DoubleDCT_2D(size, size);
    }

    /** Forward DCT, in-place; block flat row-major (length size*size). */
    public void forward(double[] block) {
        engine.forward(block, true);
    }

    /** Inverse DCT, in-place. */
    public void inverse(double[] block) {
        engine.inverse(block, true);
    }

    public int size() {
        return size;
    }
}
