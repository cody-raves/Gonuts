package dev.doughbay.core.analysis;

/**
 * Trade filters. The defaults mirror the plan's starting configuration and are
 * explicitly temporary — tune through paper trading, not by intuition.
 */
public record RiskConfig(
        int minimumSamples,
        long minimumProfit,
        double minimumRoiPercent,
        double maximumPositionPercent,     // of bankroll per single trade
        double bankrollReservePercent,     // never deploy this slice
        int maximumConcurrentPositions,
        boolean skipFallingMarkets,
        boolean commoditiesOnly,
        double maximumVolatility,          // fractional, e.g. 0.25 = 25%
        long maximumNewestSaleAgeMillis,
        double maximumExpectedHoldHours,
        double minimumConfidence,
        long undercutAmount                // undercut vs next active listing
) {
    public static RiskConfig defaults() {
        return new RiskConfig(
                20,
                5_000,
                12.0,
                10.0,
                25.0,
                8,
                true,
                true,
                0.25,
                60L * 60L * 1000L,   // newest comparable sale within 1 hour
                2.0,
                0.5,
                1
        );
    }
}
