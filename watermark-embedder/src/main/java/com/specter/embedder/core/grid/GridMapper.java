package com.specter.embedder.core.grid;

import com.specter.embedder.config.ContractConstants;

/**
 * 48x27 normalize grid -> 8x8 DCT block koordinatlari. Contract section 3.1.
 *
 * <p>Embed-time normalize koordinati:
 * <pre>
 *   x_norm_embed = SAFE_MARGIN + (col + 0.5) * (1 - 2*SAFE_MARGIN) / GRID_COLS
 *   y_norm_embed = SAFE_MARGIN + (row + 0.5) * (1 - 2*SAFE_MARGIN) / GRID_ROWS
 * </pre>
 *
 * <p>Extract-time alignment correction (contract section 5.3, M3 robustness):
 * <pre>
 *   norm_extract = (norm_embed - offset) / scale
 * </pre>
 * Default alignment {@code (scale=1, offset=0)} embed = extract anlamina gelir
 * (M1 / M2 senaryolari). Crop saldirilarinda extractor scale/offset araligini
 * tarayarak en yuksek confidence veren hizalamayi secer.
 *
 * <p>Embedder ve extractor **bire bir** ayni snap fonksiyonunu kullanmali.
 */
public final class GridMapper {

    private final int frameWidth;
    private final int frameHeight;
    private final double scale;
    private final double offsetX;
    private final double offsetY;

    /** Default alignment (M1 / M2: extract == embed coordinate frame). */
    public GridMapper(int frameWidth, int frameHeight) {
        this(frameWidth, frameHeight, 1.0, 0.0, 0.0);
    }

    /** M3 robustness: alignment-aware mapping. */
    public GridMapper(int frameWidth, int frameHeight, double scale, double offsetX, double offsetY) {
        if (frameWidth < ContractConstants.DCT_BLOCK_SIZE || frameHeight < ContractConstants.DCT_BLOCK_SIZE) {
            throw new IllegalArgumentException("frame too small: " + frameWidth + "x" + frameHeight);
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("scale must be > 0, got " + scale);
        }
        this.frameWidth = frameWidth;
        this.frameHeight = frameHeight;
        this.scale = scale;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /** Hucre indeksi (0..GRID_CELLS) -> {block_x, block_y} (pixel, 8'in kati). */
    public int[] cellToBlock(int cellIndex) {
        if (cellIndex < 0 || cellIndex >= ContractConstants.GRID_CELLS) {
            throw new IllegalArgumentException("cell index out of range: " + cellIndex);
        }
        int col = cellIndex % ContractConstants.GRID_COLS;
        int row = cellIndex / ContractConstants.GRID_COLS;
        double safeRange = 1.0 - 2.0 * ContractConstants.SAFE_MARGIN;
        double xNormEmbed = ContractConstants.SAFE_MARGIN + (col + 0.5) * safeRange / ContractConstants.GRID_COLS;
        double yNormEmbed = ContractConstants.SAFE_MARGIN + (row + 0.5) * safeRange / ContractConstants.GRID_ROWS;
        // Apply alignment: norm_extract = (norm_embed - offset) / scale
        double xNormExtract = (xNormEmbed - offsetX) / scale;
        double yNormExtract = (yNormEmbed - offsetY) / scale;
        int xPix = (int) Math.round(xNormExtract * frameWidth);
        int yPix = (int) Math.round(yNormExtract * frameHeight);
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

    public double scale() { return scale; }
    public double offsetX() { return offsetX; }
    public double offsetY() { return offsetY; }
}
