package com.specter.extraction.model;

/**
 * Represents the raw data of a single decoded video frame.
 * Only the luminance (Y) channel is stored — the DCT watermark
 * pipeline operates exclusively on luminance, so RGB pixels are
 * never retained (saves ~8 MB per 1080p frame).
 */
public class FrameData {

    private int frameIndex;
    private int width;
    private int height;
    private double[][] luminanceChannel;
    private double timestampSeconds;

    public FrameData() {}

    private FrameData(Builder builder) {
        this.frameIndex       = builder.frameIndex;
        this.width            = builder.width;
        this.height           = builder.height;
        this.luminanceChannel = builder.luminanceChannel;
        this.timestampSeconds = builder.timestampSeconds;
    }

    public static Builder builder() { return new Builder(); }

    public int getFrameIndex() { return frameIndex; }
    public void setFrameIndex(int frameIndex) { this.frameIndex = frameIndex; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public double[][] getLuminanceChannel() { return luminanceChannel; }
    public void setLuminanceChannel(double[][] luminanceChannel) { this.luminanceChannel = luminanceChannel; }

    public double getTimestampSeconds() { return timestampSeconds; }
    public void setTimestampSeconds(double timestampSeconds) { this.timestampSeconds = timestampSeconds; }

    public static class Builder {
        private int frameIndex;
        private int width;
        private int height;
        private double[][] luminanceChannel;
        private double timestampSeconds;

        public Builder frameIndex(int val)            { frameIndex = val;       return this; }
        public Builder width(int val)                 { width = val;            return this; }
        public Builder height(int val)                { height = val;           return this; }
        public Builder luminanceChannel(double[][] val){ luminanceChannel = val; return this; }
        public Builder timestampSeconds(double val)   { timestampSeconds = val; return this; }
        public FrameData build()                      { return new FrameData(this); }
    }
}
