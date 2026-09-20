package dev.doughbay.fabric;

import dev.doughbay.storage.Database;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * How much of the auction house is us.
 *
 * <p>The tempting way to work this out is our ledger over the server's: we
 * sold two thousand today, the feed recorded two hundred and sixty thousand,
 * so we are one percent of the market. That answer is wrong by a factor of
 * twenty, and the reason is worth stating because it is easy to get caught by
 * twice.
 *
 * <p>Our ledger is complete. Every sale we make arrives as a chat receipt and
 * every one is written down. The server's completed-sales feed is not: it is a
 * paginated endpoint polled on a clock, and between two polls far more sells
 * than the pages we read can carry. Measured against our own receipts it
 * carries about one sale in twenty. So a ratio of our complete count over its
 * sampled count compares two different things and flatters us enormously.
 *
 * <p>Everything here is therefore counted inside the feed on both sides: our
 * sales as the feed saw them, over every sale the feed saw. That ratio is
 * sound whatever the sampling rate, because the sampling applies to both. The
 * coverage figure is reported alongside it so the number can be read for what
 * it is, and our true volume is still available from the ledger where an
 * absolute count is what is wanted.
 */
public final class MarketShare {
    /** Recomputed at most this often: the ranking sweep reads a day of sales. */
    private static final long CACHE_MILLIS = 5 * 60_000L;

    /**
     * @param feedSales  every sale the feed recorded in the window
     * @param feedValue  their total value
     * @param ourSales   our sales, as the feed recorded them
     * @param ourValue   their value, on the same basis
     * @param sellers    distinct sellers the feed saw
     * @param rank       our best-placed account's rank by sales, 1-based; 0 if absent
     * @param ranked     which account that was
     * @param coverage   share of our real sales the feed caught, 0-1; -1 if unknown
     */
    public record Share(int feedSales, long feedValue, int ourSales, long ourValue,
                        int sellers, int rank, String ranked, double coverage) {

        /** Our share of sales, as a percentage of what the feed saw. */
        public double salesPct() {
            return feedSales <= 0 ? 0 : ourSales * 100.0 / feedSales;
        }

        /** Our share of value, on the same basis. */
        public double valuePct() {
            return feedValue <= 0 ? 0 : ourValue * 100.0 / feedValue;
        }

        /** "0.045% of sales · 0.11% of value". */
        public String describe() {
            return String.format(Locale.ROOT, "%.3f%% of sales · %.3f%% of value", salesPct(), valuePct());
        }

        /** "#135 of 50,334 sellers", or "" when we are not in the window. */
        public String describeRank() {
            if (rank <= 0) return "";
            return String.format(Locale.ROOT, "#%,d of %,d sellers", rank, sellers);
        }

        /** "top 0.21%" of all sellers by sales, or "" when we are not ranked. */
        public String topPercent() {
            if (rank <= 0 || sellers <= 0) return "";
            double pct = rank * 100.0 / sellers;
            // Under 1% earns the finer grain that makes the standing feel real.
            return pct < 1.0
                    ? String.format(Locale.ROOT, "top %.2f%%", pct)
                    : String.format(Locale.ROOT, "top %.0f%%", pct);
        }

        /** "feed sees 5.3% of sales", or "" when it cannot be measured. */
        public String describeCoverage() {
            if (coverage < 0) return "";
            return String.format(Locale.ROOT, "feed sees %.1f%% of sales", coverage * 100);
        }
    }

    private static final Share EMPTY = new Share(0, 0, 0, 0, 0, 0, "", -1);
    private static volatile Share cached = EMPTY;
    private static volatile long cachedAt;
    private static volatile long cachedWindow;

    private MarketShare() {
    }

    /**
     * Our share of the market over the last {@code windowMillis}, cached.
     *
     * <p>Never throws: a panel that cannot answer this still has to draw.
     */
    public static Share of(Path ledger, long windowMillis, Collection<String> accounts) {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MILLIS && cachedWindow == windowMillis) return cached;
        cachedAt = now;
        cachedWindow = windowMillis;
        try {
            cached = compute(ledger, now - windowMillis, accounts);
        } catch (Exception | Error e) {
            DoughBayClient.LOGGER.debug("DoughBay market share could not be read: {}", e.toString());
        }
        return cached;
    }

    private static Share compute(Path ledger, long since, Collection<String> accounts) throws Exception {
        List<String> mine = new ArrayList<>();
        for (String a : accounts) if (a != null && !a.isBlank()) mine.add(a.toLowerCase(Locale.ROOT));
        try (Database db = new Database(ledger)) {
            int feedSales = 0;
            long feedValue = 0;
            int sellers = 0;
            int ourSales = 0;
            long ourValue = 0;
            int rank = 0;
            String ranked = "";

            // One sweep answers the totals, the ranking and our own place in
            // it. Ranking is by sales count, which is what "how busy is this
            // account" means and what a server would sort by to find us.
            int best = Integer.MAX_VALUE;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT seller_name, COUNT(*) c, COALESCE(SUM(total_price),0) v FROM transactions "
                            + "WHERE sold_at > ? GROUP BY seller_name ORDER BY c DESC")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    int place = 0;
                    while (rs.next()) {
                        String name = rs.getString(1);
                        int count = rs.getInt(2);
                        long value = rs.getLong(3);
                        place++;
                        sellers++;
                        feedSales += count;
                        feedValue += value;
                        if (name != null && mine.contains(name.toLowerCase(Locale.ROOT))) {
                            ourSales += count;
                            ourValue += value;
                            if (place < best) {
                                best = place;
                                rank = place;
                                ranked = name;
                            }
                        }
                    }
                }
            }

            // How much of the market the feed can actually see, measured
            // against the one seller whose true count we know exactly.
            double coverage = -1;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT COUNT(*) FROM positions WHERE mode='REAL' AND status='SOLD' AND closed_at > ?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int trueSales = rs.getInt(1);
                        if (trueSales > 0) coverage = Math.min(1.0, ourSales / (double) trueSales);
                    }
                }
            }
            return new Share(feedSales, feedValue, ourSales, ourValue, sellers, rank, ranked, coverage);
        }
    }
}
