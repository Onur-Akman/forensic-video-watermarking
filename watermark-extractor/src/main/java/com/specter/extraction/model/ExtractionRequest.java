package com.specter.extraction.model;

/**
 * DTO representing a watermark extraction request.
 */
public class ExtractionRequest {

    /** Path to the uploaded video file for processing */
    private String videoFilePath;

    /** Secret key used to locate the watermark (must match embedding key) */
    private String secretKey;

    /** Optional: specific frame range to scan (start frame index) */
    private Integer startFrame;

    /** Optional: specific frame range to scan (end frame index) */
    private Integer endFrame;

    public ExtractionRequest() {}

    public ExtractionRequest(String videoFilePath, String secretKey, Integer startFrame, Integer endFrame) {
        this.videoFilePath = videoFilePath;
        this.secretKey = secretKey;
        this.startFrame = startFrame;
        this.endFrame = endFrame;
    }

    public String getVideoFilePath() { return videoFilePath; }
    public void setVideoFilePath(String videoFilePath) { this.videoFilePath = videoFilePath; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public Integer getStartFrame() { return startFrame; }
    public void setStartFrame(Integer startFrame) { this.startFrame = startFrame; }

    public Integer getEndFrame() { return endFrame; }
    public void setEndFrame(Integer endFrame) { this.endFrame = endFrame; }
}
