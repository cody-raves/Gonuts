package dev.doughbay.core.model;

import java.util.List;

/**
 * A listing the engine believes can be bought and conservatively resold at a
 * profit. {@code reasons} carries the human-readable "why this was flagged"
 * explanation every alert must show.
 */
public record Opportunity(
        Listing listing,
        MarketStats stats,
        long buyPrice,
        long recommendedSellPrice,   // gross, per stack
        double expectedNetProfit,
        double expectedRoiPercent,
        double estimatedHoldHours,   // estimate, not a guaranteed fill time
        double saleProbability,      // 0..1
        double confidence,           // 0..1, after manipulation penalties
        double score,
        List<String> reasons
) {
}
