package dev.doughbay.fabric;

import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.StackBucket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sample rows so the interface can be seen and laid out before an API key
 * exists.
 *
 * <p>These numbers are invented. Everything that renders them must say so
 * loudly — mistaking demo figures for real market data is exactly the failure
 * this tool exists to prevent.
 */
final class DemoData {

    private DemoData() {
    }

    private record Spec(String itemId, int count, long buy, long sell, double median,
                        double salesPerHour, double volatility, double trend,
                        double confidence, int samples, int sellers, String reason) {
    }

    private static final List<Spec> SPECS = List.of(
            new Spec("minecraft:ender_pearl", 16, 4_100, 8_950, 9_340, 18.4, 0.042, 0.02, 0.93, 47, 12,
                    "41% below conservative value"),
            new Spec("minecraft:redstone", 64, 3_000, 9_000, 9_400, 22.0, 0.031, 0.05, 0.88, 112, 19,
                    "67% below conservative value"),
            new Spec("minecraft:obsidian", 64, 810_000, 1_010_000, 1_060_000, 4.2, 0.055, 0.005, 0.71, 31, 8,
                    "24% below conservative value"),
            new Spec("minecraft:diamond", 32, 128_000, 151_000, 158_000, 9.1, 0.038, 0.03, 0.84, 58, 14,
                    "18% below conservative value"),
            new Spec("minecraft:netherite_scrap", 4, 240_000, 268_000, 279_000, 2.4, 0.081, 0.008, 0.62, 22, 6,
                    "12% below conservative value")
    );

    /** Markets with no opportunity, so the market browser is not just the hit list. */
    private static final List<Spec> QUIET_MARKETS = List.of(
            new Spec("minecraft:gold_ingot", 64, 0, 0, 44_500, 12.7, 0.028, 0.01, 0.90, 84, 21, ""),
            new Spec("minecraft:iron_ingot", 64, 0, 0, 18_200, 15.3, 0.024, -0.02, 0.91, 96, 24, ""),
            new Spec("minecraft:emerald", 64, 0, 0, 61_000, 6.8, 0.047, 0.04, 0.79, 41, 11, ""),
            new Spec("minecraft:coal_block", 64, 0, 0, 7_400, 3.1, 0.062, -0.08, 0.64, 26, 7, ""),
            new Spec("minecraft:glowstone", 64, 0, 0, 22_800, 5.4, 0.039, 0.00, 0.77, 35, 9, "")
    );

    /** @param now a timestamp supplied by the caller so this stays deterministic */
    static List<Opportunity> opportunities(long now) {
        List<Opportunity> out = new ArrayList<>();
        for (Spec s : SPECS) {
            out.add(build(now, s));
        }
        return out;
    }

    /** Every demo market, including ones with nothing worth buying. */
    static List<MarketStats> markets(long now) {
        List<MarketStats> out = new ArrayList<>();
        for (Spec s : SPECS) {
            out.add(stats(now, s));
        }
        for (Spec s : QUIET_MARKETS) {
            out.add(stats(now, s));
        }
        return out;
    }

    /**
     * Deterministic synthetic price series per market, so the charts show a
     * plausible shape before real history exists. Seeded from the item id,
     * so a given market always draws the same curve. The last point is pinned
     * to the quoted median instead of allowing random-walk drift to contradict
     * the figures printed beside the chart.
     */
    static Map<String, List<Charts.Point>> history(long now) {
        Map<String, List<Charts.Point>> out = new HashMap<>();
        for (Spec s : SPECS) {
            out.put(s.itemId(), walk(now, s));
        }
        for (Spec s : QUIET_MARKETS) {
            out.put(s.itemId(), walk(now, s));
        }
        return out;
    }

    private static List<Charts.Point> walk(long now, Spec s) {
        long span = 12L * 3_600_000L;
        int[] bucketCounts = volume(s.itemId(), s.salesPerHour());
        int points = 0;
        for (int bucketCount : bucketCounts) points += bucketCount;
        points = Math.max(2, points);
        long seed = s.itemId().hashCode() * 2654435761L;
        double unit = s.median() / Math.max(1, s.count());
        double[] deviations = new double[points];
        double shock = 0;

        // Correlated noise reads more like a traded market than independent
        // saw teeth. Remove its final offset below so the series closes on the
        // median while retaining the locally noisy route taken to get there.
        for (int i = 0; i < points; i++) {
            seed = nextSeed(seed);
            double noise = unitInterval(seed) - 0.5;
            shock = shock * 0.78 + noise * 0.72;
            double phase = (double) i / (points - 1);
            double cycle = Math.sin(phase * Math.PI * 5.0 + (s.itemId().hashCode() & 7)) * 0.22;
            deviations[i] = shock + cycle;
        }

        List<Long> timestamps = new ArrayList<>(points);
        long windowStart = now - span;
        long bucketSpan = span / bucketCounts.length;
        for (int bucket = 0; bucket < bucketCounts.length; bucket++) {
            int count = bucketCounts[bucket];
            for (int i = 0; i < count; i++) {
                // Place observations strictly inside their half-hour bucket.
                // Counts vary by market, but timestamps remain deterministic
                // and ordered so all demo points can feed the real bucketer.
                double within = (i + 1.0) / (count + 1.0);
                timestamps.add(windowStart + bucket * bucketSpan
                        + (long) (within * bucketSpan));
            }
        }

        // Specs currently all produce at least two observations. Retain a
        // harmless fallback if a zero-demand demo row is added later.
        if (timestamps.size() < 2) {
            timestamps.clear();
            timestamps.add(windowStart + bucketSpan / 2);
            timestamps.add(now - bucketSpan / 2);
        }

        List<Charts.Point> series = new ArrayList<>(timestamps.size());
        for (int i = 0; i < timestamps.size(); i++) {
            double progress = (double) i / (points - 1);
            double trendLevel = unit * (1.0 + s.trend() * (progress - 1.0));
            double anchoredNoise = deviations[i] - deviations[points - 1] * progress;
            double level = trendLevel * (1.0 + anchoredNoise * s.volatility() * 0.85);
            if (i == timestamps.size() - 1) level = unit;
            series.add(new Charts.Point(timestamps.get(i), Math.max(1, level)));
        }
        return series;
    }

    /**
     * Invented half-hour sales counts for the 12-hour volume chart.
     *
     * <p>The bar shapes contain deterministic cycles and a small demand burst,
     * but are normalized so their total agrees with the displayed sales/hour
     * headline. That keeps the demo visually interesting without presenting
     * internally contradictory evidence.
     */
    static int[] volume(String itemId, double salesPerHour) {
        int buckets = 24;
        int[] counts = new int[buckets];
        long seed = itemId.hashCode() * 40503L + 17;
        double[] weights = new double[buckets];
        double weightTotal = 0;
        int burstBucket = Math.floorMod(itemId.hashCode(), buckets - 4) + 2;

        for (int i = 0; i < buckets; i++) {
            seed = nextSeed(seed);
            double noise = unitInterval(seed);
            double cycle = 1.0 + Math.sin((i + (itemId.hashCode() & 3)) * Math.PI / 6.0) * 0.18;
            double burst = Math.abs(i - burstBucket) <= 1 ? 1.28 : 1.0;
            weights[i] = Math.max(0.1, cycle * burst * (0.72 + noise * 0.56));
            weightTotal += weights[i];
        }

        int targetSales = Math.max(0, (int) Math.round(salesPerHour * 12.0));
        double[] remainders = new double[buckets];
        int allocated = 0;
        for (int i = 0; i < buckets; i++) {
            double share = targetSales * weights[i] / weightTotal;
            counts[i] = (int) Math.floor(share);
            remainders[i] = share - counts[i];
            allocated += counts[i];
        }

        // Largest-remainder allocation keeps the total exact and deterministic.
        while (allocated < targetSales) {
            int best = 0;
            for (int i = 1; i < buckets; i++) {
                if (remainders[i] > remainders[best]) best = i;
            }
            counts[best]++;
            remainders[best] = -1;
            allocated++;
        }
        return counts;
    }

    private static MarketStats stats(long now, Spec s) {
        double quick = s.sell() > 0 ? s.sell() : s.median() * 0.955;
        return new MarketStats(
                s.itemId(), StackBucket.of(s.count()), 7L * 24 * 3_600_000L,
                s.samples(), 1, s.sellers(), now - 300_000L,
                quick * 0.93, quick, s.median(), s.median() * 1.05, s.median() * 1.11,
                s.salesPerHour(), s.volatility(), s.trend(), s.confidence(), now);
    }

    private static Opportunity build(long now, Spec s) {
        Listing listing = new Listing(
                "demo-" + s.itemId(), now, "demo-seller-uuid", "DemoSeller",
                s.itemId(), s.itemId(), s.count(), s.buy(), 3_600_000L);
        double profit = s.sell() - s.buy();
        return new Opportunity(listing, stats(now, s), s.buy(), s.sell(), profit,
                profit / s.buy() * 100.0,
                (1.0 / Math.max(0.1, s.salesPerHour())) * 2,
                0.9, s.confidence(),
                profit / s.buy(),
                reasons(s));
    }

    private static List<String> reasons(Spec s) {
        List<String> reasons = new ArrayList<>();
        // Keep this first: compact layouts may not have room for every reason.
        reasons.add("DEMO DATA — invented preview, not a real listing");
        reasons.add(s.reason());

        String demand = s.salesPerHour() >= 12 ? "Strong recent demand"
                : s.salesPerHour() >= 5 ? "Steady recent demand"
                : "Thin recent demand — slower exit risk";
        reasons.add(demand + " — " + decimal(s.salesPerHour()) + " demo sales/hour");

        double volatilityPercent = s.volatility() * 100.0;
        String stability = s.volatility() <= 0.04 ? "Low volatility"
                : s.volatility() <= 0.065 ? "Moderate volatility"
                : "Elevated volatility — wider price risk";
        reasons.add(stability + " — " + decimal(volatilityPercent) + "% robust spread");

        reasons.add(s.samples() + " invented comparable sales across "
                + s.sellers() + " demo sellers");
        if (s.trend() >= 0.025) {
            reasons.add("Positive synthetic trend — +" + decimal(s.trend() * 100.0) + "%");
        } else if (s.trend() <= -0.025) {
            reasons.add("Falling synthetic trend risk — " + decimal(s.trend() * 100.0) + "%");
        } else {
            reasons.add("Synthetic price trend is broadly stable");
        }
        return List.copyOf(reasons);
    }

    private static long nextSeed(long seed) {
        return seed * 6364136223846793005L + 1442695040888963407L;
    }

    private static double unitInterval(long seed) {
        return (seed >>> 11) / (double) (1L << 53);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
