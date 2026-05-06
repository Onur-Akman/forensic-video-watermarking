package com.specter.extraction.service.impl;

import com.specter.extraction.model.FrameData;
import com.specter.extraction.service.FrameSynchronizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.specter.extraction.config.WatermarkConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of frame synchronization service.
 * Detects and compensates for temporal manipulations such as:
 * - Video trimming (start/end cut)
 * - Framerate conversion (e.g., 30fps → 24fps)
 * - Temporal shifts
 * Also enforces the EXTRACTOR_MAX_FRAMES limit (Contract §9).
 */
@Service
public class FrameSynchronizationServiceImpl implements FrameSynchronizationService {

    private static final Logger log = LoggerFactory.getLogger(FrameSynchronizationServiceImpl.class);
    private static final double SCENE_CHANGE_THRESHOLD = 30.0;

    private final WatermarkConfig watermarkConfig;

    public FrameSynchronizationServiceImpl(WatermarkConfig watermarkConfig) {
        this.watermarkConfig = watermarkConfig;
    }

    @Override
    public List<FrameData> synchronize(List<FrameData> frames) {
        if (frames == null || frames.isEmpty()) {
            return frames;
        }

        log.info("Synchronizing {} frames", frames.size());

        boolean manipulated = detectTemporalManipulation(frames);

        if (!manipulated) {
            log.info("No temporal manipulation detected, using frames as-is");
            return frames;
        }

        log.info("Temporal manipulation detected, applying synchronization");

        List<FrameData> deduplicated = removeDuplicateFrames(frames);
        List<FrameData> trimmed = removeTrimmingArtifacts(deduplicated);

        // Enforce maximum frames limit (Contract §9)
        int maxFrames = watermarkConfig.getMaxFrames();
        if (trimmed.size() > maxFrames) {
            log.info("Frame count {} exceeds maximum allowed {}. Truncating list.", trimmed.size(), maxFrames);
            trimmed = new ArrayList<>(trimmed.subList(0, maxFrames));
        }

        log.info("Synchronized {} frames down to {} frames", frames.size(), trimmed.size());
        return trimmed;
    }

    @Override
    public boolean detectTemporalManipulation(List<FrameData> frames) {
        if (frames.size() < 2) {
            return false;
        }

        double expectedInterval = 1.0 / 30.0;
        int irregularIntervals = 0;

        for (int i = 1; i < Math.min(frames.size(), 100); i++) {
            double interval = frames.get(i).getTimestampSeconds()
                    - frames.get(i - 1).getTimestampSeconds();
            double deviation = Math.abs(interval - expectedInterval) / expectedInterval;

            if (deviation > 0.15) {
                irregularIntervals++;
            }
        }

        double irregularRatio = (double) irregularIntervals / Math.min(frames.size() - 1, 99);
        boolean hasIrregularTiming = irregularRatio > 0.1;

        int duplicates = countDuplicateFrames(frames);
        boolean hasDuplicates = duplicates > frames.size() * 0.05;

        if (hasIrregularTiming || hasDuplicates) {
            log.info("Manipulation indicators: irregular_timing={}, duplicates={}",
                    hasIrregularTiming, hasDuplicates);
            return true;
        }

        return false;
    }

    private List<FrameData> removeDuplicateFrames(List<FrameData> frames) {
        List<FrameData> result = new ArrayList<>();
        result.add(frames.get(0));

        for (int i = 1; i < frames.size(); i++) {
            double diff = calculateFrameDifference(frames.get(i - 1), frames.get(i));
            if (diff > 1.0) {
                result.add(frames.get(i));
            } else {
                log.trace("Skipping duplicate frame at index {}", i);
            }
        }

        return result;
    }

    private List<FrameData> removeTrimmingArtifacts(List<FrameData> frames) {
        if (frames.size() < 10) {
            return frames;
        }

        int startIdx = 0;
        int endIdx = frames.size();

        for (int i = 0; i < Math.min(5, frames.size() - 1); i++) {
            double meanLuminance = calculateMeanLuminance(frames.get(i));
            if (meanLuminance < 10.0) {
                startIdx = i + 1;
                log.debug("Removing black frame at start index {}", i);
            }
        }

        for (int i = frames.size() - 1; i > Math.max(frames.size() - 6, startIdx); i--) {
            double meanLuminance = calculateMeanLuminance(frames.get(i));
            if (meanLuminance < 10.0) {
                endIdx = i;
                log.debug("Removing black frame at end index {}", i);
            }
        }

        return frames.subList(startIdx, endIdx);
    }

    private int countDuplicateFrames(List<FrameData> frames) {
        int duplicates = 0;
        for (int i = 1; i < frames.size(); i++) {
            double diff = calculateFrameDifference(frames.get(i - 1), frames.get(i));
            if (diff <= 1.0) {
                duplicates++;
            }
        }
        return duplicates;
    }

    private double calculateFrameDifference(FrameData frame1, FrameData frame2) {
        double[][] lum1 = frame1.getLuminanceChannel();
        double[][] lum2 = frame2.getLuminanceChannel();

        if (lum1 == null || lum2 == null) return Double.MAX_VALUE;

        int height = Math.min(lum1.length, lum2.length);
        int width = Math.min(lum1[0].length, lum2[0].length);

        double sum = 0.0;
        int count = 0;

        for (int y = 0; y < height; y += 8) {
            for (int x = 0; x < width; x += 8) {
                sum += Math.abs(lum1[y][x] - lum2[y][x]);
                count++;
            }
        }

        return count > 0 ? sum / count : 0.0;
    }

    private double calculateMeanLuminance(FrameData frame) {
        double[][] lum = frame.getLuminanceChannel();
        if (lum == null) return 0.0;

        double sum = 0.0;
        int count = 0;

        for (int y = 0; y < lum.length; y += 4) {
            for (int x = 0; x < lum[0].length; x += 4) {
                sum += lum[y][x];
                count++;
            }
        }

        return count > 0 ? sum / count : 0.0;
    }
}
