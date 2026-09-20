package dev.doughbay.fabric;

import dev.doughbay.storage.Database;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What size each market actually buys in.
 *
 * <p>The bot buys a stack of sixty-four and tries to sell it as a stack of
 * sixty-four, and for most of the economy that is fine. For a good part of it
 * that is the opposite of what the market wants, and the difference is not
 * small. Across the markets where both sizes trade, four in five pay more per
 * unit for a single than for a stack, and the premium runs from twenty percent
 * to several hundred.
 *
 * <p>It cost real money to learn that one item at a time. Twenty-one stacks of
 * name tags were bought at a forty percent discount to the going rate, walked
 * down by the repricer for a day and a half, and closed at a three and a half
 * million dollar loss - and the reason was never the price. Singles were
 * ninety percent of that market's sales at fifty thousand each; stacks were
 * four percent at thirty-two thousand a unit. The same shape then repeated
 * with smithing templates for another three million, and would have kept
 * repeating, once per expensive item, for as long as nobody looked.
 *
 * <p>Nobody needs to pay that twice. Every completed sale on the server is
 * already recorded here with its stack size and its unit price, so the answer
 * for every market can be read off the history before a single coin is put at
 * risk. That is what this is.
 *
 * <p>Three things make the numbers trustworthy, and all three were learned by
 * getting them wrong first:
 *
 * <ul>
 *   <li>Medians, never averages. A single troll listing - one redstone at
 *       thirty million - drags an average so far that redstone appears to pay
 *       a hundred-thousand-fold premium for singles.
 *   <li>A floor on sample size per size band. Three sales prove nothing.
 *   <li>A ceiling on the premium. Past a certain point it is not a market
 *       preference, it is somebody's mispriced item, and acting on it would be
 *       buying into a fantasy.
 * </ul>
 *
 * <p>Read-only for now: it reports and nothing acts on it yet.
 */
public final class StackProfile {
    private static final long REFRESH_MILLIS = 15 * 60_000L;
    private static final long WINDOW_MILLIS = 3 * 24 * 3_600_000L;
    /** Sales needed in a size band before that band's price is believed. */
    private static final int MIN_SINGLE_SALES = 15;
    private static final int MIN_STACK_SALES = 8;
    /**
     * Above this, the "premium" is a mispriced listing rather than demand.
     * Four hundred percent is already extraordinary and real; ten thousand is
     * somebody asking thirty million for one redstone.
     */
    private static final double MAX_BELIEVABLE_PREMIUM = 4.0;

    /**
     * @param singleShare  share of this market's sales that are single items, 0-1
     * @param premium      how much more a single pays per unit than a stack, as a
     *                     fraction: 0.3 is thirty percent more, 0 is no advantage
     * @param singlePrice  median price of one, sold on its own
     * @param stackPrice   median price per unit when sold in a stack of 32 or more
     * @param singlesADay  how many singles this market clears in a day
     */
    public record Shape(double singleShare, double premium, long singlePrice,
                        long stackPrice, double singlesADay, double unitsADay) {

        /** Whether this market is worth listing one at a time. */
        public boolean prefersSingles() {
            return premium >= 0.20 && singleShare >= 0.25 && singlesADay >= 5;
        }

        /**
         * The most units of this market worth holding at once, as a share of
         * what it clears in a day.
         *
         * <p>Volume falls about four hundredfold across the price range - a
         * market under a thousand a unit moves twelve thousand units a day, one
         * over a hundred thousand moves forty - and the order desk was asking
         * for a hundred and ninety-two units of every one of them. That is
         * sixteen minutes of demand at the cheap end and five days at the
         * expensive end, from the same line of code, and the five-day end is
         * where the losses came from.
         *
         * <p>It is not price that predicts thinness, it is whether the item
         * gets used up: netherite pickaxes at twenty million a unit still clear
         * two thousand a day because players break them, while a decorative at
         * a fraction of that clears forty. So the cap is set from the market's
         * own volume rather than from what it costs.
         */
        public int maxUnits(double sharePct) {
            double share = Math.max(0.5, Math.min(100.0, sharePct)) / 100.0;
            return (int) Math.max(1, Math.round(unitsADay * share));
        }

        /** "singles pay 38% more · 90% of sales · 256 a day". */
        public String describe() {
            return String.format(Locale.ROOT, "singles pay %.0f%% more · %.0f%% of sales · %.0f a day",
                    premium * 100, singleShare * 100, singlesADay);
        }
    }

    private final Path databasePath;
    private volatile Map<String, Shape> shapes = Map.of();
    private volatile long refreshedAt;

    StackProfile(Path databasePath) {
        this.databasePath = databasePath;
        Thread thread = new Thread(this::loop, "doughbay-stack-profile");
        thread.setDaemon(true);
        thread.start();
    }

    public long refreshedAt() {
        return refreshedAt;
    }

    public int markets() {
        return shapes.size();
    }

    /** What this market buys in, or null when its history cannot say. */
    public Shape shapeOf(String itemId) {
        return shapes.get(itemId);
    }

    /** Markets that pay best for singles, worst first, for the panel. */
    public List<Map.Entry<String, Shape>> preferSingles(int limit) {
        List<Map.Entry<String, Shape>> out = new ArrayList<>();
        for (Map.Entry<String, Shape> e : shapes.entrySet()) {
            if (e.getValue().prefersSingles()) out.add(e);
        }
        out.sort((a, b) -> Double.compare(b.getValue().premium(), a.getValue().premium()));
        return out.size() <= limit ? out : out.subList(0, limit);
    }

    private void loop() {
        while (true) {
            try {
                rebuild();
            } catch (Exception | Error e) {
                DoughBayClient.LOGGER.warn("DoughBay stack profile failed: {}", e.toString());
            }
            try {
                Thread.sleep(REFRESH_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void rebuild() throws Exception {
        long since = System.currentTimeMillis() - WINDOW_MILLIS;
        Map<String, List<Long>> singles = new HashMap<>();
        Map<String, List<Long>> stacks = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        Map<String, Long> unitsOf = new HashMap<>();
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT item_key, item_count, unit_price FROM transactions "
                             + "WHERE is_outlier=0 AND sold_at > ? AND unit_price > 0")) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    if (key == null) continue;
                    int count = rs.getInt(2);
                    long unit = rs.getLong(3);
                    totals.merge(key, 1, Integer::sum);
                    unitsOf.merge(key, (long) Math.max(1, count), Long::sum);
                    if (count == 1) {
                        singles.computeIfAbsent(key, k -> new ArrayList<>()).add(unit);
                    } else if (count >= 32) {
                        stacks.computeIfAbsent(key, k -> new ArrayList<>()).add(unit);
                    }
                }
            }
        }
        double days = WINDOW_MILLIS / 86_400_000.0;
        Map<String, Shape> built = new HashMap<>();
        for (Map.Entry<String, List<Long>> e : singles.entrySet()) {
            String key = e.getKey();
            List<Long> one = e.getValue();
            List<Long> many = stacks.get(key);
            if (many == null || one.size() < MIN_SINGLE_SALES || many.size() < MIN_STACK_SALES) continue;
            long ms = median(one);
            long mp = median(many);
            if (mp <= 0) continue;
            double premium = ms / (double) mp - 1.0;
            if (premium > MAX_BELIEVABLE_PREMIUM || premium < -0.9) continue;
            int total = totals.getOrDefault(key, one.size());
            built.put(key, new Shape(one.size() / (double) total, premium, ms, mp,
                    one.size() / days, unitsOf.getOrDefault(key, 0L) / days));
        }
        shapes = Map.copyOf(built);
        refreshedAt = System.currentTimeMillis();
        int prefer = (int) built.values().stream().filter(Shape::prefersSingles).count();
        DoughBayClient.LOGGER.info(
                "DoughBay stack profile: {} market(s) trade both sizes; {} pay better one at a time",
                built.size(), prefer);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        return n == 0 ? 0 : n % 2 == 1 ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
