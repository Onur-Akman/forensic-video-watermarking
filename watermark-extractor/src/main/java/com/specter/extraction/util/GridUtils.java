package com.specter.extraction.util;

import com.specter.extraction.config.WatermarkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grid coordinate utilities for the DCT watermark extraction pipeline.
 */
public class GridUtils {
    private static final Logger log = LoggerFactory.getLogger(GridUtils.class);

    public static final int EMBED_W = 1920;
    public static final int EMBED_H = 1080;

    public enum MappingMode {
        EXTRACT_SNAPPED, // Snap to 8-pixel grid of CURRENT resolution (Naive)
        EMBED_SNAPPED    // Snap to 8-pixel grid of 1080p and project (Contract-compliant)
    }

    public static double[] getBlockCoordinates(
            int cellIndex, int frameWidth, int frameHeight,
            double scale, double offsetX, double offsetY, MappingMode mode) {

        int col = cellIndex % WatermarkConfig.GRID_COLS;
        int row = cellIndex / WatermarkConfig.GRID_COLS;

        double xNormBase = WatermarkConfig.SAFE_MARGIN
                + (col + 0.5) * (1.0 - 2.0 * WatermarkConfig.SAFE_MARGIN) / WatermarkConfig.GRID_COLS;
        double yNormBase = WatermarkConfig.SAFE_MARGIN
                + (row + 0.5) * (1.0 - 2.0 * WatermarkConfig.SAFE_MARGIN) / WatermarkConfig.GRID_ROWS;

        double xNorm = (xNormBase - 0.5) * scale + 0.5;
        double yNorm = (yNormBase - 0.5) * scale + 0.5;

        if (mode == MappingMode.EXTRACT_SNAPPED) {
            // Naive snap at current resolution
            long xPix = Math.round(xNorm * frameWidth);
            long yPix = Math.round(yNorm * frameHeight);
            double blockX = (double) (xPix / 8) * 8;
            double blockY = (double) (yPix / 8) * 8;
            return new double[]{blockX + offsetX, blockY + offsetY};
        } else {
            // Contract-compliant snap at 1080p
            long xPixEmbed = Math.round(xNorm * EMBED_W);
            long yPixEmbed = Math.round(yNorm * EMBED_H);
            double blockXEmbed = (double) (xPixEmbed / 8) * 8;
            double blockYEmbed = (double) (yPixEmbed / 8) * 8;
            double blockX = blockXEmbed * (double) frameWidth  / EMBED_W;
            double blockY = blockYEmbed * (double) frameHeight / EMBED_H;
            return new double[]{blockX + offsetX, blockY + offsetY};
        }
    }

    public static void extractBlockBilinear(double[][] luminance, double blockX, double blockY, double[][] block) {
        int height = luminance.length;
        int width  = luminance[0].length;

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                double px = blockX + x;
                double py = blockY + y;

                int x0 = (int) Math.floor(px);
                int y0 = (int) Math.floor(py);
                
                int cx0 = Math.max(0, Math.min(width - 1, x0));
                int cy0 = Math.max(0, Math.min(height - 1, y0));
                int cx1 = Math.max(0, Math.min(width - 1, x0 + 1));
                int cy1 = Math.max(0, Math.min(height - 1, y0 + 1));

                double dx = px - x0;
                double dy = py - y0;

                double v00 = luminance[cy0][cx0];
                double v10 = luminance[cy0][cx1];
                double v01 = luminance[cy1][cx0];
                double v11 = luminance[cy1][cx1];

                block[y][x] = v00 * (1 - dx) * (1 - dy)
                            + v10 * dx * (1 - dy)
                            + v01 * (1 - dx) * dy
                            + v11 * dx * dy;
            }
        }
    }
}
