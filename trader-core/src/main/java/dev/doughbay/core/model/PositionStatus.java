package dev.doughbay.core.model;

/** Lifecycle of a paper or real position. */
public enum PositionStatus {
    SIGNAL,
    MISSED_BEFORE_PURCHASE,
    SIMULATED_PURCHASED,
    SIMULATED_LISTED,
    LIKELY_SOLD,
    EXPIRED,
    CANCELLED,
    UNRESOLVED,
    // Real-trade tracking (player-confirmed, Phase 7)
    PURCHASED,
    LISTED,
    SOLD;

    public boolean isClosed() {
        return this == LIKELY_SOLD || this == EXPIRED || this == CANCELLED
                || this == MISSED_BEFORE_PURCHASE || this == SOLD;
    }
}
