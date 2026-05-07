package com.specter.extraction.service;

import com.specter.extraction.model.FrameData;

import java.util.List;

/**
 * Service interface for frame synchronization.
 * Handles detection and compensation for video manipulations
 * such as trimming, framerate conversion, and temporal shifts.
 *
 * This is critical for adversarial robustness — the watermark must
 * be locatable even when the video has been temporally modified.
 */
public interface FrameSynchronizationService {

    /**
     * Synchronize the extracted frames by detecting and compensating
     * for temporal manipulations (trimming, framerate changes).
     *
     * @param frames raw decoded frames from the video
     * @return synchronized frames ready for watermark extraction
     */
    List<FrameData> synchronize(List<FrameData> frames);

    /**
     * Detect if the video has been temporally manipulated
     * (trimmed, framerate-converted, etc.).
     *
     * @param frames raw decoded frames
     * @return true if temporal manipulation is detected
     */
    boolean detectTemporalManipulation(List<FrameData> frames);
}
