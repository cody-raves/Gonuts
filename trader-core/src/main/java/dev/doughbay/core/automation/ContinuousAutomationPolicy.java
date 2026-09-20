package dev.doughbay.core.automation;

/**
 * Immutable risk limits for one authorized automation session.
 *
 * <p>All money values are whole server currency units. Confidence is a 0..1
 * fraction; ROI is a percentage (12 means 12%). Invalid or effectively
 * unbounded values are rejected at construction so callers cannot
 * accidentally turn a partial configuration into permission to trade.
 */
public record ContinuousAutomationPolicy(
        int maxTradesPerSession,
        long maxSessionSpend,
        long maxPurchasePrice,
        long minimumProfit,
        double minimumRoiPercent,
        double minimumConfidence,
        long cooldownMillis,
        int reservedHotbarSlot,
        long maximumHoldMillis,
        long maximumSnapshotAgeMillis,
        long preparationTimeoutMillis,
        int maxOpenListings
) {
    /** The auction house allows 45 concurrent listings per player, 90 with the top rank. */
    public static final int MAX_OPEN_LISTINGS = 90;

    /** One listing at a time: buy, list, wait for the sale, repeat. */
    public ContinuousAutomationPolicy(int maxTradesPerSession, long maxSessionSpend,
                                      long maxPurchasePrice, long minimumProfit,
                                      double minimumRoiPercent, double minimumConfidence,
                                      long cooldownMillis, int reservedHotbarSlot,
                                      long maximumHoldMillis, long maximumSnapshotAgeMillis,
                                      long preparationTimeoutMillis) {
        this(maxTradesPerSession, maxSessionSpend, maxPurchasePrice, minimumProfit,
                minimumRoiPercent, minimumConfidence, cooldownMillis, reservedHotbarSlot,
                maximumHoldMillis, maximumSnapshotAgeMillis, preparationTimeoutMillis, 1);
    }

    public static final long MAX_COOLDOWN_MILLIS = 3_600_000L;
    public static final long MAX_SNAPSHOT_AGE_MILLIS = 300_000L;
    public static final long MAX_PREPARATION_TIMEOUT_MILLIS = 180_000L;
    public static final long MAX_HOLD_MILLIS = 7L * 24L * 60L * 60L * 1_000L;

    public ContinuousAutomationPolicy {
        if (maxTradesPerSession <= 0 || maxTradesPerSession > 100_000) {
            throw new IllegalArgumentException("maxTradesPerSession must be 1..100000");
        }
        if (maxSessionSpend <= 0 || maxPurchasePrice <= 0
                || maxPurchasePrice > maxSessionSpend) {
            throw new IllegalArgumentException(
                    "positive purchase/session caps are required and purchase must not exceed session spend");
        }
        if (minimumProfit < 0) {
            throw new IllegalArgumentException("minimumProfit must not be negative");
        }
        if (!Double.isFinite(minimumRoiPercent) || minimumRoiPercent < 0) {
            throw new IllegalArgumentException("minimumRoiPercent must be finite and non-negative");
        }
        if (!Double.isFinite(minimumConfidence)
                || minimumConfidence < 0 || minimumConfidence > 1) {
            throw new IllegalArgumentException("minimumConfidence must be a 0..1 fraction");
        }
        if (cooldownMillis <= 0 || cooldownMillis > MAX_COOLDOWN_MILLIS) {
            throw new IllegalArgumentException("cooldownMillis must be 1..3600000");
        }
        if (reservedHotbarSlot < 0 || reservedHotbarSlot > 8) {
            throw new IllegalArgumentException("reservedHotbarSlot must be 0..8");
        }
        if (maximumHoldMillis <= 0 || maximumHoldMillis > MAX_HOLD_MILLIS) {
            throw new IllegalArgumentException("maximumHoldMillis must be positive and at most 7 days");
        }
        if (maximumSnapshotAgeMillis <= 0
                || maximumSnapshotAgeMillis > MAX_SNAPSHOT_AGE_MILLIS) {
            throw new IllegalArgumentException("maximumSnapshotAgeMillis must be 1..300000");
        }
        if (preparationTimeoutMillis <= 0
                || preparationTimeoutMillis > MAX_PREPARATION_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("preparationTimeoutMillis must be 1..180000");
        }
        if (maxOpenListings <= 0 || maxOpenListings > MAX_OPEN_LISTINGS) {
            throw new IllegalArgumentException("maxOpenListings must be 1..45");
        }
    }

    /** Maps the persisted UI units to the strict internal units. */
    public static ContinuousAutomationPolicy fromConfigValues(
            int maxTradesPerSession,
            long maxSessionSpend,
            long maxPurchasePrice,
            long minimumProfit,
            double minimumRoiPercent,
            double minimumConfidencePercent,
            int cooldownSeconds,
            int reservedHotbarSlot,
            int maximumHoldMinutes,
            int maxOpenListings
    ) {
        if (!Double.isFinite(minimumConfidencePercent)) {
            throw new IllegalArgumentException("minimumConfidencePercent must be finite");
        }
        return new ContinuousAutomationPolicy(
                maxTradesPerSession,
                maxSessionSpend,
                maxPurchasePrice,
                minimumProfit,
                minimumRoiPercent,
                minimumConfidencePercent / 100.0,
                Math.multiplyExact((long) cooldownSeconds, 1_000L),
                reservedHotbarSlot,
                Math.multiplyExact((long) maximumHoldMinutes, 60_000L),
                90_000L,
                90_000L,
                maxOpenListings);
    }
}
