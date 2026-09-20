package dev.doughbay.fabric.automation;

/** Measures server progress only after a command leaves the local queue. */
final class OrderReadWatchdog {
    static final long STALL_MILLIS = 20_000;
    private long startedAt;
    private long progressAt;
    private long dispatchAt;
    private int pagesSeen;

    void reset() {
        startedAt = progressAt = dispatchAt = 0;
        pagesSeen = 0;
    }

    boolean stalled(long now, boolean commandPending, long lastDispatchAt, int pages) {
        if (commandPending) {
            // The driver's bounded queue owns this wait, including server backoff.
            progressAt = 0;
            return false;
        }
        if (progressAt == 0 || lastDispatchAt != dispatchAt) {
            dispatchAt = lastDispatchAt;
            progressAt = lastDispatchAt;
            if (startedAt == 0) startedAt = lastDispatchAt;
        }
        if (pages > pagesSeen) {
            pagesSeen = pages;
            progressAt = now;
        }
        return progressAt > 0 && now - progressAt > STALL_MILLIS;
    }

    long startedAt() { return startedAt; }
}
