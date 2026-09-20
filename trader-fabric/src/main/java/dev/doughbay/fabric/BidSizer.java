package dev.doughbay.fabric;

/**
 * How many stacks one buy order asks for, market by market.
 *
 * <p>A flat size was wrong at both ends. Empty maps clear hundreds of stacks a
 * day at a quarter margin and could take twenty at a time; a thin decorative
 * cannot sell three. So the size is earned: the margin sets how much the order
 * is worth scaling, our own recent stacks of the item say whether they actually
 * sell, and the market's daily volume caps what it can absorb.
 *
 * <p>Pure arithmetic, no game types, so it is tested directly.
 */
public final class BidSizer {
    /** Margin at or under which an order stays at one stack. */
    static final double FLOOR_MARGIN = 0.03;
    /** Margin at which an order may reach the full size. */
    static final double FULL_MARGIN = 0.25;
    /** An item we have no sold stacks of yet is held to this many. */
    static final int UNPROVEN_STACKS = 3;

    /** Our own recent stacks of one item: how many sold, how many at a profit, how long they sat. */
    public record Track(int sold, int profitable, double avgMinutesListed) { }

    private BidSizer() { }

    /**
     * @param maxStacks   the largest order allowed (orders.bid_stacks)
     * @param depthStacks what the market's daily volume can absorb, in stacks; below 1 means unknown
     * @param margin      1 - bid / quick-sale value, per unit
     * @param track       our recent stacks of this item, or null when there are none
     */
    public static int stacks(int maxStacks, int depthStacks, double margin, Track track) {
        int max = Math.max(1, maxStacks);
        double f = (margin - FLOOR_MARGIN) / (FULL_MARGIN - FLOOR_MARGIN);
        f = Math.max(0, Math.min(1, Double.isFinite(f) ? f : 0));
        double size = 1 + f * (max - 1);
        if (track == null || track.sold() < 3) {
            size = Math.min(size, UNPROVEN_STACKS);
        } else {
            double minutes = track.avgMinutesListed();
            if (minutes > 60) size *= 0.3;
            else if (minutes > 15) size *= 0.6;
            if (track.profitable() < 0.7 * track.sold()) size *= 0.5;
        }
        int cap = depthStacks >= 1 ? Math.min(max, depthStacks) : Math.min(max, UNPROVEN_STACKS);
        return (int) Math.max(1, Math.min(cap, Math.round(size)));
    }
}
