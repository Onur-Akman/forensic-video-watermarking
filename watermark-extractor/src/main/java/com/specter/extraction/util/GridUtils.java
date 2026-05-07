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
        EXTRACT_SNAPPED,     // Snap to 8-pixel grid of CURRENT resolution (Naive)
        EMBED_SNAPPED,       // Snap to 8-pixel grid of 1080p and project (Contract-compliant)
        CROP_CENTER_SNAPPED  // Snap to 1080p grid, then apply center-crop offset (for adversarial crop)
    }

    public static double[] getBlockCoordinates(
            int cellIndex, int frameWidth, int frameHeight,
            double scale, double offsetX, double offsetY, MappingMode mode) {

        int col = cellIndex % WatermarkConfig.GRID_COLS;
        int row = cellIndex / WatermarkConfig.GRID_COLS;

        double xNormEmbed = WatermarkConfig.SAFE_MARGIN
                + (col + 0.5) * (1.0 - 2.0 * WatermarkConfig.SAFE_MARGIN) / WatermarkConfig.GRID_COLS;
        double yNormEmbed = WatermarkConfig.SAFE_MARGIN
                + (row + 0.5) * (1.0 - 2.0 * WatermarkConfig.SAFE_MARGIN) / WatermarkConfig.GRID_ROWS;

        if (mode == MappingMode.CROP_CENTER_SNAPPED) {
            // Center-crop: snap at 1080p, then subtract crop offset
            long xPixEmbed = Math.round(xNormEmbed * EMBED_W);
            long yPixEmbed = Math.round(yNormEmbed * EMBED_H);
            double blockXEmbed = (double) (xPixEmbed / 8) * 8;
            double blockYEmbed = (double) (yPixEmbed / 8) * 8;
            
            // FFmpeg center-crop offset: (original - cropped) / 2
            double cropOffsetX = (EMBED_W - frameWidth) / 2.0;
            double cropOffsetY = (EMBED_H - frameHeight) / 2.0;
            
            // Apply scale/offset search around the center-cropped coordinate
            double blockX = (blockXEmbed - cropOffsetX - (offsetX * frameWidth)) / scale;
            double blockY = (blockYEmbed - cropOffsetY - (offsetY * frameHeight)) / scale;
            
            return new double[]{blockX, blockY};
        } else if (mode == MappingMode.EXTRACT_SNAPPED) {
            double xNormExtract = (xNormEmbed - offsetX) / scale;
            double yNormExtract = (yNormEmbed - offsetY) / scale;
            long xPix = Math.round(xNormExtract * frameWidth);
            long yPix = Math.round(yNormExtract * frameHeight);
            double blockX = (double) (xPix / 8) * 8;
            double blockY = (double) (yPix / 8) * 8;
            return new double[]{blockX, blockY};
        } else {
            long xPixEmbed = Math.round(xNormEmbed * EMBED_W);
            long yPixEmbed = Math.round(yNormEmbed * EMBED_H);
            double xNormSnap = ((double) ((xPixEmbed / 8) * 8)) / EMBED_W;
            double yNormSnap = ((double) ((yPixEmbed / 8) * 8)) / EMBED_H;
            double xNormExtract = (xNormSnap - offsetX) / scale;
            double yNormExtract = (yNormSnap - offsetY) / scale;
            double blockX = xNormExtract * frameWidth;
            double blockY = yNormExtract * frameHeight;
            return new double[]{blockX, blockY};
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
