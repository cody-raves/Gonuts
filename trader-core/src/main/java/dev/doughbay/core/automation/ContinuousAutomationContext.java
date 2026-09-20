package dev.doughbay.core.automation;

import java.util.Set;

/** Immutable session/snapshot facts consumed by the deterministic selector. */
public record ContinuousAutomationContext(
        long nowMillis,
        long snapshotUpdatedAtMillis,
        boolean demoSnapshot,
        boolean sessionStopped,
        boolean authorized,
        boolean driverIdle,
        boolean hasOpenPosition,
        int tradesStarted,
        long committedSpend,
        long cooldownUntilMillis,
        Set<String> attemptedListingKeys
) {
    public ContinuousAutomationContext {
        if (nowMillis < 0 || snapshotUpdatedAtMillis < 0 || tradesStarted < 0
                || committedSpend < 0 || cooldownUntilMillis < 0) {
            throw new IllegalArgumentException("automation context counters/times must be non-negative");
        }
        attemptedListingKeys = attemptedListingKeys == null
                ? Set.of()
                : Set.copyOf(attemptedListingKeys);
    }
}
