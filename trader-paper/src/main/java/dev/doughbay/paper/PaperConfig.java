package dev.doughbay.paper;

import dev.doughbay.core.analysis.FeeConfig;

import java.time.Duration;

/**
 * Paper-trading simulation settings. Execution latency is simulated so deals
 * that vanish instantly are not counted as easy wins.
 */
public record PaperConfig(
        long startingBalance,
        long executionDelayMillis,
        long listingDurationMillis,
        FeeConfig fees
) {
    public static PaperConfig defaults(long startingBalance) {
        return new PaperConfig(
                startingBalance,
                Duration.ofSeconds(15).toMillis(),
                Duration.ofHours(12).toMillis(),
                FeeConfig.zero());
    }
}
