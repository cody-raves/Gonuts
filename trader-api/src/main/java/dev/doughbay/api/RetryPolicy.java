package dev.doughbay.api;

import java.time.Duration;

/** Retry limits for transient Donut API failures. */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff) {

    public static RetryPolicy defaults() {
        return new RetryPolicy(4, Duration.ofMillis(250), Duration.ofSeconds(4));
    }

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be positive");
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must be at least initialBackoff");
        }
    }
}
