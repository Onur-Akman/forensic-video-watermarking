package com.specter.extraction.service;

import com.specter.extraction.model.WatermarkPayload;

/**
 * Service interface for calculating the statistical confidence score
 * of an extracted watermark. The confidence score indicates the
 * probability that the extracted UUID is a correct match.
 */
public interface ConfidenceScoreService {

    /**
     * Calculate the confidence score for an extracted watermark payload.
     *
     * Uses the soft-decision values from DCT extraction to determine
     * how strongly the watermark signal was detected. Higher absolute
     * values indicate more reliable bit decisions.
     *
     * @param payload the extracted watermark payload with soft-decision values
     * @return confidence score in range [0.0, 1.0]
     */
    double calculate(WatermarkPayload payload);
}
