package com.specter.embedder.dto;

/** Contract section 6.1 - embed response icindeki `metrics` alani. */
public record EmbedMetrics(
        double psnrAvgDb,
        double psnrMinDb,
        double mseAvg,
        double mseMax,
        int framesProcessed,
        int framesPsnrViolation,
        double durationSec,
        double processingSec
) {
}
