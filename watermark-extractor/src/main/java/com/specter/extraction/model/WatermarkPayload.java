package com.specter.extraction.model;

/**
 * Represents the extracted watermark payload — the raw bit sequence
 * retrieved from the DCT domain before error correction.
 */
public class WatermarkPayload {

    private int[] bits;
    private double[] softBits;
    private double[] absSoftBits;
    private int blocksProcessed;
    private int framesUsed;

    public WatermarkPayload() {}

    private WatermarkPayload(Builder builder) {
        this.bits = builder.bits;
        this.softBits = builder.softBits;
        this.absSoftBits = builder.absSoftBits;
        this.blocksProcessed = builder.blocksProcessed;
        this.framesUsed = builder.framesUsed;
    }

    public static Builder builder() { return new Builder(); }

    public int[] getBits() { return bits; }
    public void setBits(int[] bits) { this.bits = bits; }

    public double[] getSoftBits() { return softBits; }
    public void setSoftBits(double[] softBits) { this.softBits = softBits; }

    public double[] getAbsSoftBits() { return absSoftBits; }
    public void setAbsSoftBits(double[] absSoftBits) { this.absSoftBits = absSoftBits; }

    public int getBlocksProcessed() { return blocksProcessed; }
    public void setBlocksProcessed(int blocksProcessed) { this.blocksProcessed = blocksProcessed; }

    public int getFramesUsed() { return framesUsed; }
    public void setFramesUsed(int framesUsed) { this.framesUsed = framesUsed; }

    /**
     * Average absolute magnitude of the soft-decision values.
     * Higher values indicate stronger watermark detection.
     */
    public double getAverageStrength() {
        if (softBits == null || softBits.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : softBits) {
            sum += Math.abs(v);
        }
        return sum / softBits.length;
    }

    public static class Builder {
        private int[] bits;
        private double[] softBits;
        private double[] absSoftBits;
        private int blocksProcessed;
        private int framesUsed;

        public Builder bits(int[] val) { bits = val; return this; }
        public Builder softBits(double[] val) { softBits = val; return this; }
        public Builder absSoftBits(double[] val) { absSoftBits = val; return this; }
        public Builder blocksProcessed(int val) { blocksProcessed = val; return this; }
        public Builder framesUsed(int val) { framesUsed = val; return this; }
        public WatermarkPayload build() { return new WatermarkPayload(this); }
    }
}
