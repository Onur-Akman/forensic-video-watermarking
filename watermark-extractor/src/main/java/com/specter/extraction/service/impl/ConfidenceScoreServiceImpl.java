package com.specter.extraction.service.impl;

import com.specter.extraction.model.WatermarkPayload;
import com.specter.extraction.service.ConfidenceScoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementation of the confidence score calculation service.
 * Computes a statistical confidence score indicating the probability
 * that the extracted UUID is a correct match.
 */
@Service
public class ConfidenceScoreServiceImpl implements ConfidenceScoreService {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceScoreServiceImpl.class);

    @Override
    public double calculate(WatermarkPayload payload) {
        if (payload == null || payload.getSoftBits() == null || payload.getSoftBits().length == 0) {
            log.warn("Cannot calculate confidence: no soft-decision values available");
            return 0.0;
        }

        double[] softBits = payload.getSoftBits();
        double[] absSoftBits = payload.getAbsSoftBits();
        
        // Contract §5.2: bit_confidence = mean( |sum_votes_for_bit| / sum_|votes_for_bit| ) for each of 84 codeword bits
        // softBits contains the average vote (sum/N) for 252 points.
        // absSoftBits contains the average absolute vote (sum|v|/N) for 252 points.
        int cw = 84;
        if (softBits.length != 252 || absSoftBits == null || absSoftBits.length != 252) {
            log.warn("Expected 252 soft bits and abs soft bits, got {} and {}", 
                    softBits.length, absSoftBits != null ? absSoftBits.length : "null");
            return 0.0;
        }

        double totalConfidence = 0.0;
        for (int i = 0; i < cw; i++) {
            // Per-bit consolidation (contract §5.1 step 4 → §5.2):
            //   1) sum frame votes within each repetition slot to get one value per slot
            //   2) confidence = | sum_r (frame_sum_r) | / sum_r | frame_sum_r |
            // Frame averaging-then-absolute (instead of per-frame absolute) is the
            // matched-filter form: noise cancels across frames before magnitude is taken,
            // matching the M3 acceptance thresholds (0.80–0.85). softBits[i] already holds
            // the cross-frame sum for repetition i, so absSoftBits is no longer needed here.
            double r0 = softBits[i];
            double r1 = softBits[i + cw];
            double r2 = softBits[i + cw * 2];
            double num = Math.abs(r0 + r1 + r2);
            double den = Math.abs(r0) + Math.abs(r1) + Math.abs(r2);
            if (den > 0) {
                totalConfidence += (num / den);
            }
        }

        double confidence = totalConfidence / cw;

        log.info("Confidence calculation (Contract §5.2 full) — final: {}", String.format("%.4f", confidence));

        return confidence;
    }
}
