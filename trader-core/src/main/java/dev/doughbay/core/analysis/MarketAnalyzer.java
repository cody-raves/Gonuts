package dev.doughbay.core.analysis;

import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Sale;
import dev.doughbay.core.model.StackBucket;
import dev.doughbay.core.stats.RobustStats;
import dev.doughbay.core.stats.RobustStats.OutlierSplit;
import dev.doughbay.core.stats.RobustStats.WeightedValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns completed sales into {@link MarketStats}. Deterministic and strictly
 * chronological: only sales that happened at or before {@code asOf} are used,
 * which is what makes backtests leak-free.
 */
public final class MarketAnalyzer {

    private final AnalyzerConfig config;

    public MarketAnalyzer(AnalyzerConfig config) {
        this.config = config;
    }

    /**
     * Result of an analysis: the stats plus which sales were excluded as
     * outliers (persisted flagged, never deleted).
     */
    public record Analysis(MarketStats stats, List<Sale> keptSales, List<Sale> outliers) {
        public Analysis {
            keptSales = List.copyOf(keptSales);
            outliers = List.copyOf(outliers);
        }
    }

    /**
     * @param sales completed sales for the item across all buckets; malformed,
     *              future (after {@code asOf}), out-of-window and wrong-bucket
     *              records are filtered here.
     */
    public Analysis analyze(String itemKey, StackBucket bucket, List<Sale> sales, long asOf) {
        List<Sale> inScope = new ArrayList<>();
        for (Sale s : sales) {
            if (!s.isValid()) continue;
            if (!s.itemKey().equals(itemKey)) continue;
            if (s.soldAt() > asOf) continue;                       // no future leakage
            if (asOf - s.soldAt() > config.windowMillis()) continue;
            if (bucket != StackBucket.OTHER && s.bucket() != bucket) continue;
            if (bucket == StackBucket.OTHER && s.bucket() != StackBucket.OTHER) continue;
            inScope.add(s);
        }

        // Outlier detection runs on unit prices so a lucky x64 sale can't
        // masquerade as a normal x16 price.
        OutlierSplit<Sale> split = RobustStats.splitOutliersByLogPrice(inScope, Sale::unitPrice);
        List<Sale> kept = split.kept();

        if (kept.isEmpty()) {
            return new Analysis(
                    emptyStats(itemKey, bucket, asOf, split.outliers().size()),
                    List.of(),
                    split.outliers());
        }

        List<WeightedValue> weighted = new ArrayList<>(kept.size());
        Set<String> sellers = new HashSet<>();
        long newestSaleAt = 0;
        for (Sale s : kept) {
            double w = RobustStats.recencyWeight(asOf - s.soldAt(), config.halfLifeMillis());
            weighted.add(new WeightedValue(stackPrice(s, bucket), w));
            sellers.add(s.sellerUuid());
            newestSaleAt = Math.max(newestSaleAt, s.soldAt());
        }

        double p25 = RobustStats.weightedPercentile(weighted, 0.25);
        double quick = RobustStats.weightedPercentile(weighted, config.quickSalePercentile());
        double median = RobustStats.weightedPercentile(weighted, 0.50);
        double patient = RobustStats.weightedPercentile(weighted, config.patientSalePercentile());
        double p75 = RobustStats.weightedPercentile(weighted, 0.75);

        double salesPerHour = salesPerHour(kept, asOf);
        // Two estimates, worst case wins. A median absolute deviation
        // ignores up to half the sample by design, so a market trading at
        // two prices reads as perfectly tight once the larger cluster
        // passes half the weight. The quartile spread cannot ignore a
        // large minority. On a genuinely tight book the two agree, so
        // this costs nothing where it does not apply.
        double volatility = Math.max(volatility(kept), spreadVolatility(p25, p75));
        double trend = trend(kept, asOf);
        double confidence = confidence(kept.size(), split.outliers().size(),
                sellers.size(), volatility, asOf - newestSaleAt);

        MarketStats stats = new MarketStats(
                itemKey, bucket, config.windowMillis(),
                kept.size(), split.outliers().size(), sellers.size(), newestSaleAt,
                p25, quick, median, patient, p75,
                salesPerHour, volatility, trend, confidence, asOf);
        return new Analysis(stats, kept, split.outliers());
    }

    /** Exact buckets are priced per stack; OTHER is priced per unit. */
    private static double stackPrice(Sale s, StackBucket bucket) {
        return bucket == StackBucket.OTHER ? s.unitPrice() : s.totalPrice();
    }

    private double salesPerHour(List<Sale> kept, long asOf) {
        long windowStart = asOf - config.liquidityWindowMillis();
        long count = kept.stream().filter(s -> s.soldAt() >= windowStart).count();
        double hours = config.liquidityWindowMillis() / 3_600_000.0;
        return count / hours;
    }

    /**
     * Price scatter implied by the quartile spread.
     *
     * <p>A second opinion on {@link #volatility(List)}, which uses a median
     * absolute deviation and therefore cannot see a market trading at two
     * distinct prices: once the larger cluster passes half the weight the
     * median deviation collapses toward zero.
     *
     * <p>The 1.349 divisor is the interquartile range of a normal distribution
     * in standard deviations, which puts this in the same fractional-scatter
     * units as the MAD estimate. On a tight book the two agree closely, so
     * taking the worse of them only bites where the quartiles are genuinely
     * far apart.
     */
    static double spreadVolatility(double p25, double p75) {
        if (!(p25 > 0) || !(p75 > 0) || p75 <= p25) return 0.0;
        double sigma = Math.log(p75 / p25) / 1.349;
        double scatter = Math.expm1(sigma);
        return Double.isFinite(scatter) ? Math.max(0.0, scatter) : 0.0;
    }

    private static double volatility(List<Sale> kept) {
        List<Double> prices = new ArrayList<>(kept.size());
        for (Sale s : kept) prices.add(s.unitPrice());
        double v = RobustStats.robustLogVolatility(prices);
        return Double.isNaN(v) ? 0.0 : v;
    }

    /**
     * Fractional drift of the recent weighted median vs. the older weighted
     * median. -0.10 means recent prices run 10% below older prices.
     */
    private double trend(List<Sale> kept, long asOf) {
        long recentCutoff = asOf - (long) (config.windowMillis() * config.trendSplitFraction());
        List<Double> recent = new ArrayList<>();
        List<Double> older = new ArrayList<>();
        for (Sale s : kept) {
            (s.soldAt() >= recentCutoff ? recent : older).add(s.unitPrice());
        }
        if (recent.size() < 3 || older.size() < 3) return 0.0;
        double recentMedian = RobustStats.median(recent);
        double olderMedian = RobustStats.median(older);
        if (olderMedian <= 0) return 0.0;
        return recentMedian / olderMedian - 1.0;
    }

    /**
     * Confidence in [0,1]: grows with samples and seller diversity, shrinks
     * with volatility and stale data. Thin markets (few sellers) are exactly
     * where wash trading hides, so they are penalized even with many sales.
     */
    /**
     * @param outliers sales discarded before the statistics were computed
     */
    static double confidence(int samples, int outliers, int uniqueSellers,
                             double volatility, long newestSaleAgeMillis) {
        double sampleFactor = Math.min(1.0, samples / 30.0);
        double sellerFactor = Math.min(1.0, uniqueSellers / 8.0);
        double volatilityFactor = 1.0 / (1.0 + 6.0 * Math.max(0, volatility));
        double staleness = Math.max(0, newestSaleAgeMillis) / 3_600_000.0; // hours
        double freshnessFactor = 1.0 / (1.0 + staleness / 4.0);
        // Discarded sales are disagreement about the price, not noise that is
        // free to ignore. Outlier removal keys off a median absolute deviation,
        // so once the modal price holds over half the sample every other trade
        // is dropped and what remains is uniform by construction — which then
        // reads as certainty. A market whose tightness was achieved by throwing
        // away a third of its trades has not earned it.
        int observed = Math.max(0, samples) + Math.max(0, outliers);
        double discarded = observed == 0 ? 0.0 : (double) Math.max(0, outliers) / observed;
        double agreementFactor = 1.0 / (1.0 + 4.0 * discarded);

        return clamp01(sampleFactor * (0.5 + 0.5 * sellerFactor)
                * volatilityFactor * freshnessFactor * agreementFactor);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private MarketStats emptyStats(String itemKey, StackBucket bucket, long asOf, int outliers) {
        return new MarketStats(itemKey, bucket, config.windowMillis(),
                0, outliers, 0, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                0, 0, 0, 0, asOf);
    }
}
