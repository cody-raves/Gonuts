package dev.doughbay.core.analysis;

/**
 * How much a market's demand right now runs above or below its own daily
 * average, from when its sales actually happen across the hours of the day.
 *
 * <p>Selection ranks a market on how fast it fills on average, but a day is not
 * flat: a market can be dead at 4am and pour at 8pm, and a flat sales-per-hour
 * hides that. Two markets can average the same and yet, at this exact hour, one
 * is about to fill and the other will sit. Given a market's own histogram of
 * sales by hour of day, this returns a multiplier for the current hour — above
 * one when the hour is busier than its average, below one when it is quieter —
 * that selection folds into the score so scan slots drift toward what is
 * filling now.
 *
 * <p>Two guards keep it a nudge, not a lever. It returns exactly {@code 1.0}
 * (no opinion) until a market has enough sales to have a real daily shape, and
 * the multiplier is clamped to {@code [minFactor, maxFactor]} so one freak busy
 * hour cannot let a thin market outrank a trustworthy one. Hours are read in
 * UTC for both the histogram and "now", so the two always align to the same
 * wall clock; the pattern lands in whatever UTC hour it truly occurs.
 *
 * <p>Pure and stateless.
 */
public final class HourlyDemand {

    public static final int HOURS = 24;

    private HourlyDemand() {
    }

    /**
     * @param histogram   sales per hour of day, length 24, index 0 = UTC 00:00
     * @param nowMillis   the moment to weight for (epoch millis)
     * @param minFactor   floor on the multiplier (e.g. 0.5)
     * @param maxFactor   ceiling on the multiplier (e.g. 2.0)
     * @param minTotal    total sales the histogram needs before it has an opinion
     * @return a multiplier in {@code [minFactor, maxFactor]}, or {@code 1.0}
     *         when the histogram is missing, malformed or too sparse
     */
    public static double factor(int[] histogram, long nowMillis,
                                double minFactor, double maxFactor, int minTotal) {
        if (histogram == null || histogram.length != HOURS) return 1.0;
        long total = 0;
        for (int h : histogram) {
            if (h < 0) return 1.0;
            total += h;
        }
        if (total < Math.max(1, minTotal)) return 1.0;

        double averagePerHour = (double) total / HOURS;
        if (averagePerHour <= 0) return 1.0;

        int hour = (int) Math.floorMod(nowMillis / 3_600_000L, (long) HOURS);
        double ratio = histogram[hour] / averagePerHour;

        double lo = Math.min(minFactor, maxFactor);
        double hi = Math.max(minFactor, maxFactor);
        return Math.max(lo, Math.min(hi, ratio));
    }
}
