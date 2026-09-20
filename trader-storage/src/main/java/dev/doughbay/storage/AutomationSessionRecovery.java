package dev.doughbay.storage;

import dev.doughbay.core.model.Position;

import java.util.List;

/** Durable state loaded before real automation may start. */
public record AutomationSessionRecovery(
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
        AutomationUncertainExposure uncertainExposure,
        List<Position> openPositions
) {
    public AutomationSessionRecovery {
        runMode = safe(runMode);
        controllerState = safe(controllerState);
        detail = safe(detail);
        boundListingKey = safe(boundListingKey);
        uncertainExposure = uncertainExposure == null
                ? AutomationUncertainExposure.none() : uncertainExposure;
        openPositions = List.copyOf(openPositions == null ? List.of() : openPositions);
        if (tradesStarted < 0 || committedSpend < 0
                || sessionStartedAt < 0 || sessionActiveMillis < 0
                || lifetimeActiveMillis < 0 || updatedAt < 0) {
            throw new IllegalArgumentException("automation recovery values cannot be negative");
        }
        if (lifetimeActiveMillis < sessionActiveMillis) {
            throw new IllegalArgumentException(
                    "lifetime automation runtime cannot be below session runtime");
        }
    }

    /** Source-compatible constructor for pre-runtime callers and old fixtures. */
    public AutomationSessionRecovery(
            boolean sessionOpen,
            String runMode,
            String controllerState,
            int tradesStarted,
            long committedSpend,
            long sessionStartedAt,
            long updatedAt,
            String detail,
            String boundListingKey,
            AutomationUncertainExposure uncertainExposure,
            List<Position> openPositions
    ) {
        this(sessionOpen, runMode, controllerState, tradesStarted, committedSpend,
                sessionStartedAt, 0, 0, updatedAt, detail, boundListingKey,
                uncertainExposure, openPositions);
    }

    public boolean unresolvedExposure() {
        return uncertainExposure.active() || !openPositions.isEmpty();
    }

    public static AutomationSessionRecovery empty() {
        return new AutomationSessionRecovery(false, "", "", 0, 0,
                0, 0, 0, 0, "", "", AutomationUncertainExposure.none(), List.of());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
