package dev.doughbay.storage;

import dev.doughbay.core.model.Position;

/** Immutable controller state handed to the off-thread persistence worker. */
public record AutomationSessionCheckpoint(
        boolean sessionOpen,
        String runMode,
        String controllerState,
        int tradesStarted,
        long committedSpend,
        long sessionStartedAt,
        long sessionActiveMillis,
        long lifetimeActiveMillis,
        long updatedAt,
        String detail,
        String boundListingKey,
        Position position,
        AutomationUncertainExposure uncertainExposure
) {
    public AutomationSessionCheckpoint {
        runMode = safe(runMode);
        controllerState = safe(controllerState);
        detail = safe(detail);
        boundListingKey = safe(boundListingKey);
        uncertainExposure = uncertainExposure == null
                ? AutomationUncertainExposure.none() : uncertainExposure;
        if (tradesStarted < 0 || committedSpend < 0
                || sessionStartedAt < 0 || sessionActiveMillis < 0
                || lifetimeActiveMillis < 0 || updatedAt < 0) {
            throw new IllegalArgumentException("automation checkpoint values cannot be negative");
        }
        if (lifetimeActiveMillis < sessionActiveMillis) {
            throw new IllegalArgumentException(
                    "lifetime automation runtime cannot be below session runtime");
        }
        if (sessionOpen && (runMode.isBlank() || controllerState.isBlank()
                || sessionStartedAt <= 0 || updatedAt <= 0)) {
            throw new IllegalArgumentException(
                    "open automation session requires mode, state, and timestamps");
        }
        if (position != null && !"REAL".equals(position.mode())) {
            throw new IllegalArgumentException("automation checkpoints accept REAL positions only");
        }
        if (position != null && position.positionId() <= 0) {
            throw new IllegalArgumentException("persisted positions require a stable positive id");
        }
    }

    /** Source-compatible constructor for pre-runtime callers and old fixtures. */
    public AutomationSessionCheckpoint(
            boolean sessionOpen,
            String runMode,
            String controllerState,
            int tradesStarted,
            long committedSpend,
            long sessionStartedAt,
            long updatedAt,
            String detail,
            String boundListingKey,
            Position position,
            AutomationUncertainExposure uncertainExposure
    ) {
        this(sessionOpen, runMode, controllerState, tradesStarted, committedSpend,
                sessionStartedAt, 0, 0, updatedAt, detail, boundListingKey,
                position, uncertainExposure);
    }

    public boolean unresolvedExposure() {
        return uncertainExposure.active()
                || (position != null && !position.status().isClosed());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
