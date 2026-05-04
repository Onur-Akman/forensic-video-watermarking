package com.specter.embedder.core.grid;

import com.specter.embedder.config.ContractConstants;

/**
 * 48x27 normalize grid -> 8x8 DCT block koordinatlari. Contract section 3.1.
 *
 * x_norm = SAFE_MARGIN + (col + 0.5) * (1 - 2*SAFE_MARGIN) / GRID_COLS
 * y_norm = SAFE_MARGIN + (row + 0.5) * (1 - 2*SAFE_MARGIN) / GRID_ROWS
 *
 * En yakin 8x8 block'a snap; embedder ve extractor **bire bir** ayni fonksiyonu kullanmali.
 */
public final class GridMapper {

    private final int frameWidth;
    private final int frameHeight;

    public GridMapper(int frameWidth, int frameHeight) {
        if (frameWidth < ContractConstants.DCT_BLOCK_SIZE || frameHeight < ContractConstants.DCT_BLOCK_SIZE) {
            throw new IllegalArgumentException("frame too small: " + frameWidth + "x" + frameHeight);
        }
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
    }

    /** Hucre indeksi (0..GRID_CELLS) -> {block_x, block_y} (pixel, 8'in kati). */
    public int[] cellToBlock(int cellIndex) {
        if (cellIndex < 0 || cellIndex >= ContractConstants.GRID_CELLS) {
            throw new IllegalArgumentException("cell index out of range: " + cellIndex);
        }
        int col = cellIndex % ContractConstants.GRID_COLS;
        int row = cellIndex / ContractConstants.GRID_COLS;
        double safeRange = 1.0 - 2.0 * ContractConstants.SAFE_MARGIN;
        double xNorm = ContractConstants.SAFE_MARGIN + (col + 0.5) * safeRange / ContractConstants.GRID_COLS;
        double yNorm = ContractConstants.SAFE_MARGIN + (row + 0.5) * safeRange / ContractConstants.GRID_ROWS;
        int xPix = (int) Math.round(xNorm * frameWidth);
        int yPix = (int) Math.round(yNorm * frameHeight);
        int blockX = (xPix / ContractConstants.DCT_BLOCK_SIZE) * ContractConstants.DCT_BLOCK_SIZE;
        int blockY = (yPix / ContractConstants.DCT_BLOCK_SIZE) * ContractConstants.DCT_BLOCK_SIZE;
        int maxBlockX = ((frameWidth  - ContractConstants.DCT_BLOCK_SIZE) / ContractConstants.DCT_BLOCK_SIZE) * ContractConstants.DCT_BLOCK_SIZE;
        int maxBlockY = ((frameHeight - ContractConstants.DCT_BLOCK_SIZE) / ContractConstants.DCT_BLOCK_SIZE) * ContractConstants.DCT_BLOCK_SIZE;
        if (blockX > maxBlockX) blockX = maxBlockX;
        if (blockY > maxBlockY) blockY = maxBlockY;
        if (blockX < 0) blockX = 0;
        if (blockY < 0) blockY = 0;
        return new int[]{blockX, blockY};
    }

    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }
}
