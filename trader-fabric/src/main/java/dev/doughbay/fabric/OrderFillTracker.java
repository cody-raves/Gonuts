package dev.doughbay.fabric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Completed order-house trades, worked out from successive reads of the book.
 *
 * <p>With the Donut API gone there is no feed of the server's completed sales,
 * but the order house shows every order's delivered count, and a count that
 * rose between two reads is units that changed hands at that order's price.
 * Orders carry no owner, so one is known by item, unit price and size; where
 * several share that identity their delivered counts are summed, and compared
 * only when the same number of them was seen both times, so a partial read
 * that saw one of a pair is never mistaken for a fill.
 *
 * <p>Pure bookkeeping with no game or database types, so it is tested directly.
 */
public final class OrderFillTracker {

    /** One order as a read saw it. */
    public record Row(String itemKey, long unitPrice, int total, int delivered) { }

    /** Units delivered to orders of one identity between two reads. */
    public record Fill(String itemKey, long unitPrice, int units, long at) { }

    private record Key(String itemKey, long unitPrice, int total) { }

    private record Seen(int orders, long delivered, long at) { }

    /**
     * A previous sighting older than this is not compared against: days of
     * fills credited to one moment (the first read after a long gap) would
     * read as a burst of trade that never happened.
     */
    static final long MAX_GAP_MILLIS = 2 * 3_600_000L;

    private final Map<Key, Seen> last = new HashMap<>();

    /**
     * Folds in one read of the book, taken at {@code at}, and returns what was
     * delivered since each order's identity was last seen. {@code emit} false
     * only learns the state, for warming up on reads made before this started.
     */
    public List<Fill> read(List<Row> rows, long at, boolean emit) {
        Map<Key, long[]> now = new HashMap<>();
        for (Row r : rows) {
            if (r.itemKey() == null || r.itemKey().indexOf('#') >= 0 || r.unitPrice() <= 0 || r.total() <= 0) continue;
            long[] agg = now.computeIfAbsent(new Key(r.itemKey(), r.unitPrice(), r.total()), k -> new long[2]);
            agg[0]++;
            agg[1] += Math.max(0, Math.min(r.delivered(), r.total()));
        }
        List<Fill> fills = new ArrayList<>();
        for (Map.Entry<Key, long[]> e : now.entrySet()) {
            Key k = e.getKey();
            int orders = (int) e.getValue()[0];
            long delivered = e.getValue()[1];
            Seen before = last.put(k, new Seen(orders, delivered, at));
            if (!emit || before == null || before.orders() != orders || at - before.at() > MAX_GAP_MILLIS) continue;
            long rose = delivered - before.delivered();
            if (rose > 0) fills.add(new Fill(k.itemKey(), k.unitPrice(), (int) Math.min(rose, Integer.MAX_VALUE), at));
        }
        return fills;
    }
}
