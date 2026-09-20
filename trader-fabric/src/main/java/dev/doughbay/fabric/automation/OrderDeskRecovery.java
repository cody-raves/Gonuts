package dev.doughbay.fabric.automation;

/** Retry own-order reads with backoff, without hiding them behind a long auction watch. */
final class OrderDeskRecovery {
    private int failures;
    private long retryAt;

    void failed(long now) {
        failures = Math.min(3, failures + 1);
        retryAt = now + (failures == 1 ? 5_000L : failures == 2 ? 15_000L : 60_000L);
    }

    void recovered() { failures = 0; retryAt = 0; }
    boolean pending() { return failures > 0; }
    boolean ready(long now) { return pending() && now >= retryAt; }
    long remainingSeconds(long now) { return Math.max(0, (retryAt - now + 999) / 1000); }

    static boolean navigationFailure(String detail) {
        if (detail == null) return false;
        String text = detail.toLowerCase(java.util.Locale.ROOT);
        return text.contains("your orders did not open") || text.contains("your orders could not be clicked")
                || text.contains("no your orders control") || text.contains("no progress at stage 0")
                || text.contains("no progress at stage 1 of") || text.contains("orderstage=0")
                || text.contains("orderstage=1,") || text.contains("order house did not open");
    }
}
