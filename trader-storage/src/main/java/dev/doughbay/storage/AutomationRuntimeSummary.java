package dev.doughbay.storage;

/**
 * Durable active runtime for authorized REAL automation in this local database.
 * Paused, stopped, and offline time is never included.
 */
public record AutomationRuntimeSummary(
        long currentSessionActiveMillis,
        long lifetimeActiveMillis,
        long sessionStartedAt,
        boolean sessionOpen,
        long recordedAt
) {
    public AutomationRuntimeSummary {
        if (currentSessionActiveMillis < 0 || lifetimeActiveMillis < 0
                || sessionStartedAt < 0 || recordedAt < 0) {
            throw new IllegalArgumentException("automation runtime values cannot be negative");
        }
        if (lifetimeActiveMillis < currentSessionActiveMillis) {
            throw new IllegalArgumentException(
                    "lifetime automation runtime cannot be below session runtime");
        }
    }

    public static AutomationRuntimeSummary empty() {
        return new AutomationRuntimeSummary(0, 0, 0, false, 0);
    }
}
