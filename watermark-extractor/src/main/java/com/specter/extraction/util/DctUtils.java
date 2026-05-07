package com.specter.extraction.util;

/**
 * Utility class for Discrete Cosine Transform (DCT) operations.
 * Implements the 8x8 block DCT used in JPEG/H.264 compression,
 * which is the core of the frequency-domain watermark extraction.
 *
 * The DCT transforms spatial-domain pixel values into frequency-domain
 * coefficients. Watermark data is embedded in mid-frequency coefficients
 * because they survive compression better than high-frequency ones
 * and are less visible than modifications to low-frequency ones.
 */
public final class DctUtils {

    private DctUtils() {
        // Utility class — no instantiation
    }

    /**
     * Pre-computed cosine values for 8x8 DCT to avoid repeated Math.cos calls.
     */
    private static final double[][] COSINE_TABLE = new double[8][8];

    static {
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                COSINE_TABLE[i][j] = Math.cos((2.0 * i + 1.0) * j * Math.PI / 16.0);
            }
        }
    }

    /**
     * Normalization factor C(u) for DCT.
     * C(0) = 1/sqrt(2), C(u) = 1 for u > 0.
     */
    private static double alpha(int u) {
        return u == 0 ? 1.0 / Math.sqrt(2.0) : 1.0;
    }

    /**
     * Perform forward 2D DCT on an 8x8 block.
     *
     * @param block 8x8 spatial-domain pixel values
     * @return 8x8 frequency-domain DCT coefficients
     */
    public static double[][] forwardDct8x8(double[][] block) {
        double[][] dct = new double[8][8];
        forwardDct8x8(block, dct);
        return dct;
    }

    /**
     * Perform forward 2D DCT on an 8x8 block into a pre-allocated array.
     *
     * @param block 8x8 spatial-domain pixel values
     * @param dct Pre-allocated 8x8 frequency-domain DCT coefficients array to fill
     */
    public static void forwardDct8x8(double[][] block, double[][] dct) {
        for (int u = 0; u < 8; u++) {
            for (int v = 0; v < 8; v++) {
                double sum = 0.0;
                for (int x = 0; x < 8; x++) {
                    for (int y = 0; y < 8; y++) {
                        sum += block[x][y] * COSINE_TABLE[x][u] * COSINE_TABLE[y][v];
                    }
                }
                dct[u][v] = 0.25 * alpha(u) * alpha(v) * sum;
            }
        }
    }

    /**
     * Perform inverse 2D DCT on an 8x8 block.
     *
     * @param dct 8x8 frequency-domain DCT coefficients
     * @return 8x8 spatial-domain pixel values
     */
    public static double[][] inverseDct8x8(double[][] dct) {
        double[][] block = new double[8][8];

        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                double sum = 0.0;
                for (int u = 0; u < 8; u++) {
                    for (int v = 0; v < 8; v++) {
                        sum += alpha(u) * alpha(v) * dct[u][v]
                                * COSINE_TABLE[x][u] * COSINE_TABLE[y][v];
                    }
                }
                block[x][y] = 0.25 * sum;
            }
        }

        return block;
    }

    /**
     * Zigzag scan order for an 8x8 block.
     * Maps linear index → (row, col) in zigzag traversal order.
     * This is the standard JPEG zigzag ordering used to separate
     * low, mid, and high frequency coefficients.
     */
    public static final int[][] ZIGZAG_ORDER = {
            {0, 0}, {0, 1}, {1, 0}, {2, 0}, {1, 1}, {0, 2}, {0, 3}, {1, 2},
            {2, 1}, {3, 0}, {4, 0}, {3, 1}, {2, 2}, {1, 3}, {0, 4}, {0, 5},
            {1, 4}, {2, 3}, {3, 2}, {4, 1}, {5, 0}, {6, 0}, {5, 1}, {4, 2},
            {3, 3}, {2, 4}, {1, 5}, {0, 6}, {0, 7}, {1, 6}, {2, 5}, {3, 4},
            {4, 3}, {5, 2}, {6, 1}, {7, 0}, {7, 1}, {6, 2}, {5, 3}, {4, 4},
            {3, 5}, {2, 6}, {1, 7}, {2, 7}, {3, 6}, {4, 5}, {5, 4}, {6, 3},
            {7, 2}, {7, 3}, {6, 4}, {5, 5}, {4, 6}, {3, 7}, {4, 7}, {5, 6},
            {6, 5}, {7, 4}, {7, 5}, {6, 6}, {5, 7}, {6, 7}, {7, 6}, {7, 7}
    };

    /**
     * Extract a DCT coefficient at a specific zigzag index from an 8x8 DCT block.
     *
     * @param dctBlock 8x8 DCT coefficient matrix
     * @param zigzagIndex index in zigzag scan order (0-63)
     * @return the DCT coefficient value at that position
     */
    public static double getCoefficient(double[][] dctBlock, int zigzagIndex) {
        int[] pos = ZIGZAG_ORDER[zigzagIndex];
        return dctBlock[pos[0]][pos[1]];
    }

    /**
     * Extract 8x8 blocks from a 2D luminance channel.
     * Only complete blocks are returned (edges are trimmed).
     *
     * @param luminance full luminance channel [height][width]
     * @param blockSize size of each block (typically 8)
     * @return array of 8x8 blocks, indexed as [blockIndex][row][col]
     */
    public static double[][][] extractBlocks(double[][] luminance, int blockSize) {
        int height = luminance.length;
        int width = luminance[0].length;
        int blocksY = height / blockSize;
        int blocksX = width / blockSize;

        double[][][] blocks = new double[blocksY * blocksX][blockSize][blockSize];

        int blockIdx = 0;
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                for (int y = 0; y < blockSize; y++) {
                    for (int x = 0; x < blockSize; x++) {
                        blocks[blockIdx][y][x] = luminance[by * blockSize + y][bx * blockSize + x];
                    }
                }
                blockIdx++;
            }
        }

        return blocks;
    }
}
