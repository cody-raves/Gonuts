package dev.doughbay.core.model;

/**
 * Snapshot of everything the valuation engine knows about one
 * (item_key, stack_bucket) market at {@code calculatedAt}. All prices are
 * per-stack for exact buckets (unit prices times bucket size) and per-unit for
 * the OTHER bucket.
 */
public record MarketStats(
        String itemKey,
        StackBucket bucket,
        long windowMillis,
        int sampleCount,
        int outlierCount,
        int uniqueSellers,
        long newestSaleAt,
        double lowerBound,        // weighted p25
        double quickSalePrice,    // weighted p375 — the conservative resale target
        double weightedMedian,    // weighted p50
        double patientSalePrice,  // weighted p625
        double upperBound,        // weighted p75
        double salesPerHour,
        double robustVolatility,  // fractional, e.g. 0.042 = 4.2%
        double trend,             // fractional recent-vs-older drift; negative = falling
        double confidence,        // 0..1
        long calculatedAt
) {
    public boolean hasPrices() {
        return !Double.isNaN(weightedMedian) && sampleCount > 0;
    }
}
