package dev.doughbay.fabric;

/**
 * Read-only boundary between the in-game Stats presentation and persisted
 * performance history. Implementations must keep PAPER and REAL histories
 * strictly separated; the requested mode is never inferred from a trade.
 *
 * <p>The provider is called while rendering, so production adapters should
 * return an already-prepared immutable snapshot and must not perform database
 * or network work on the Minecraft render thread.
 */
@FunctionalInterface
public interface PerformanceStatsProvider {

    PerformanceStatsSnapshot snapshot(PerformanceStatsSnapshot.Mode mode,
                                      boolean demoFeed,
                                      long nowMillis);

    /** Preview provider used until a durable history adapter is installed. */
    static PerformanceStatsProvider previewOnly() {
        return new PerformanceStatsProvider() {
            private final long demoAnchor = System.currentTimeMillis();
            private final PerformanceStatsSnapshot paper = PerformanceStatsSnapshot.demo(
                    PerformanceStatsSnapshot.Mode.PAPER, demoAnchor);
            private final PerformanceStatsSnapshot real = PerformanceStatsSnapshot.demo(
                    PerformanceStatsSnapshot.Mode.REAL, demoAnchor);

            @Override
            public PerformanceStatsSnapshot snapshot(PerformanceStatsSnapshot.Mode mode,
                                                     boolean demoFeed,
                                                     long nowMillis) {
                if (!demoFeed) {
                    return PerformanceStatsSnapshot.empty(mode,
                            "Performance history is not connected yet");
                }
                return mode == PerformanceStatsSnapshot.Mode.PAPER ? paper : real;
            }
        };
    }
}
