package com.specter.extraction.util;

/**
 * Utility class for color space conversions.
 * Converts between RGB and YCbCr color spaces.
 *
 * Watermark is embedded in the Luminance (Y) channel of YCbCr
 * because it survives color-space conversions (RGB → YUV → RGB)
 * that occur during video encoding/decoding with H.264.
 *
 * ITU-R BT.601 standard conversion formulas:
 *   Y  =  0.299 * R + 0.587 * G + 0.114 * B
 *   Cb = -0.169 * R - 0.331 * G + 0.500 * B + 128
 *   Cr =  0.500 * R - 0.419 * G - 0.081 * B + 128
 */
public final class ColorSpaceUtils {

    private ColorSpaceUtils() {
        // Utility class — no instantiation
    }

    /**
     * Extract the luminance (Y) channel from packed ARGB pixel data.
     *
     * @param rgbPixels packed ARGB pixel array (from BufferedImage.getRGB)
     * @param width     image width
     * @param height    image height
     * @return 2D luminance array [height][width] with values in [0, 255]
     */
    public static double[][] extractLuminance(int[] rgbPixels, int width, int height) {
        double[][] luminance = new double[height][width];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = rgbPixels[y * width + x];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                // ITU-R BT.601 luminance
                luminance[y][x] = 0.299 * r + 0.587 * g + 0.114 * b;
            }
        }

        return luminance;
    }

    /**
     * Convert a single RGB pixel to its Y (luminance) value.
     *
     * @param r red channel [0, 255]
     * @param g green channel [0, 255]
     * @param b blue channel [0, 255]
     * @return luminance value Y
     */
    public static double rgbToLuminance(int r, int g, int b) {
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }

    /**
     * Convert a single RGB pixel to YCbCr.
     *
     * @param r red channel [0, 255]
     * @param g green channel [0, 255]
     * @param b blue channel [0, 255]
     * @return double array {Y, Cb, Cr}
     */
    public static double[] rgbToYCbCr(int r, int g, int b) {
        double y  =  0.299 * r + 0.587 * g + 0.114 * b;
        double cb = -0.169 * r - 0.331 * g + 0.500 * b + 128.0;
        double cr =  0.500 * r - 0.419 * g - 0.081 * b + 128.0;
        return new double[]{y, cb, cr};
    }

    /**
     * Convert YCbCr to RGB.
     *
     * @param y  luminance [0, 255]
     * @param cb blue-difference chroma
     * @param cr red-difference chroma
     * @return int array {R, G, B} clamped to [0, 255]
     */
    public static int[] yCbCrToRgb(double y, double cb, double cr) {
        int r = clamp((int) Math.round(y + 1.402 * (cr - 128.0)));
        int g = clamp((int) Math.round(y - 0.344 * (cb - 128.0) - 0.714 * (cr - 128.0)));
        int b = clamp((int) Math.round(y + 1.772 * (cb - 128.0)));
        return new int[]{r, g, b};
    }

    /**
     * Clamp a value to the [0, 255] range.
     */
    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
