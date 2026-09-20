package dev.doughbay.core.analysis;

import java.time.Duration;

/**
 * Tunables for market analysis. Defaults follow the plan; every value is
 * expected to be re-tuned through paper trading.
 */
public record AnalyzerConfig(
        long halfLifeMillis,          // recency weighting half-life
        long windowMillis,            // ignore sales older than this entirely
        long liquidityWindowMillis,   // window for sales/hour measurement
        double quickSalePercentile,   // conservative resale band
        double patientSalePercentile,
        double trendSplitFraction     // fraction of window treated as "recent" for trend
) {
    public static AnalyzerConfig defaults() {
        return new AnalyzerConfig(
                Duration.ofHours(6).toMillis(),
                Duration.ofDays(7).toMillis(),
                Duration.ofHours(3).toMillis(),
                0.375,
                0.625,
                0.25
        );
    }
}
