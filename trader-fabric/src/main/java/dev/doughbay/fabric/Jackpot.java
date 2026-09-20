package dev.doughbay.fabric;

import java.util.HashMap;
import java.util.Map;

/**
 * Jackpotting: singles of one item selling as fast as the peel can list them.
 *
 * <p>The peel keeps at most {@code list.singles_max} singles of an item up and
 * lists another each time one sells, so when buyers keep pace the whole stack
 * goes out one at a time at the single price. This counts that run: singles of
 * the same item, each sold within {@link #GAP_MILLIS} of the one before. A run
 * is called a jackpot at {@code jackpot.min} sales and again at every multiple
 * of it, so a long streak is announced as it grows rather than once.
 *
 * <p>Pure bookkeeping with no game types, so it is tested directly.
 */
public final class Jackpot {
    /** Longer than this between two sales of the item and the streak is over. */
    static final long GAP_MILLIS = 3 * 60_000L;

    /** A streak that has just reached a milestone. */
    public record Hit(String item, int sold, long profit, long startedAt) { }

    private record Run(int sold, long profit, long startedAt, long lastAt) { }

    private final Map<String, Run> runs = new HashMap<>();

    /**
     * Records one single sold. Returns the streak when it has just reached a
     * milestone (every {@code every} sales), otherwise null. {@code every} of
     * zero or less turns calling off.
     */
    public synchronized Hit onSingleSold(String item, long profit, long now, int every) {
        Run run = runs.get(item);
        if (run == null || now - run.lastAt() > GAP_MILLIS) {
            run = new Run(0, 0, now, now);
        }
        run = new Run(run.sold() + 1, run.profit() + profit, run.startedAt(), now);
        runs.put(item, run);
        if (every <= 0 || run.sold() % every != 0) return null;
        return new Hit(item, run.sold(), run.profit(), run.startedAt());
    }
}
