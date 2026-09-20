package dev.doughbay.api;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * Sliding-window limiter keeping a safe margin under the published API limit.
 * {@link #acquire()} blocks until a request is allowed at the target rate and
 * never permits exceeding the hard internal maximum in any 60s window.
 */
public final class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000;

    private volatile int targetPerMinute;
    private volatile int hardMaxPerMinute;
    private final LongSupplier clock;
    private final Deque<Long> requestTimes = new ArrayDeque<>();

    public RateLimiter(int targetPerMinute, int hardMaxPerMinute) {
        this(targetPerMinute, hardMaxPerMinute, System::currentTimeMillis);
    }

    RateLimiter(int targetPerMinute, int hardMaxPerMinute, LongSupplier clock) {
        this.targetPerMinute = targetPerMinute;
        this.hardMaxPerMinute = hardMaxPerMinute;
        this.clock = clock;
    }

    /**
     * Narrows or widens the budget while running. Several clients can share one
     * API key, and each takes a share of the allowance that changes as clients
     * come and go.
     */
    public synchronized void setBudget(int newTargetPerMinute, int newHardMaxPerMinute) {
        if (newTargetPerMinute <= 0 || newHardMaxPerMinute <= 0) return;
        targetPerMinute = newTargetPerMinute;
        hardMaxPerMinute = newHardMaxPerMinute;
        notifyAll();
    }

    public synchronized int targetPerMinute() {
        return targetPerMinute;
    }

    /** Blocks until a request slot is available, then records it. */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            long waitMillis = millisUntilPermitted();
            if (waitMillis <= 0) {
                requestTimes.addLast(clock.getAsLong());
                return;
            }
            wait(waitMillis);
        }
    }

    /** >0 means the caller must wait that long; used directly by tests. */
    synchronized long millisUntilPermitted() {
        long now = clock.getAsLong();
        while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= WINDOW_MILLIS) {
            requestTimes.removeFirst();
        }
        int inWindow = requestTimes.size();
        if (inWindow >= hardMaxPerMinute) {
            return WINDOW_MILLIS - (now - requestTimes.peekFirst());
        }
        if (inWindow >= targetPerMinute) {
            // Pace to the target budget: wait until the oldest request ages out.
            return WINDOW_MILLIS - (now - requestTimes.peekFirst());
        }
        return 0;
    }

    public synchronized int requestsInLastMinute() {
        long now = clock.getAsLong();
        while (!requestTimes.isEmpty() && now - requestTimes.peekFirst() >= WINDOW_MILLIS) {
            requestTimes.removeFirst();
        }
        return requestTimes.size();
    }
}
