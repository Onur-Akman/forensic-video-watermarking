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
            // sum_votes_for_bit across 3 repetitions and N frames
            // num = | sum_f (v_f,r1 + v_f,r2 + v_f,r3) |
            // den = sum_f ( |v_f,r1| + |v_f,r2| + |v_f,r3| )
            // Since we stored averages (sum/N), the N cancels out: (sum/N) / (sumAbs/N) = sum/sumAbs
            
            double num = Math.abs(softBits[i] + softBits[i + cw] + softBits[i + cw * 2]);
            double den = absSoftBits[i] + absSoftBits[i + cw] + absSoftBits[i + cw * 2];

            if (den > 0) {
                totalConfidence += (num / den);
            }
        }

        double confidence = totalConfidence / cw;

        log.info("Confidence calculation (Contract §5.2 full) — final: {}", String.format("%.4f", confidence));

        return confidence;
    }
}
