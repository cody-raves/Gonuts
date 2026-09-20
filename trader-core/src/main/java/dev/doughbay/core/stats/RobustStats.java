package dev.doughbay.core.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Robust statistics used by the valuation engine: median/MAD outlier detection
 * in log-price space, exponential recency weighting, and weighted percentiles.
 * All methods are deterministic and side-effect free so strategies are testable.
 */
public final class RobustStats {

    /** Modified z-score cutoff; 3.5 is the standard Iglewicz–Hoaglin threshold. */
    public static final double OUTLIER_Z_CUTOFF = 3.5;

    /** Consistency constant relating MAD to the standard deviation of a normal. */
    private static final double MAD_TO_SIGMA = 1.4826;

    private RobustStats() {
    }

    public record WeightedValue(double value, double weight) {
    }

    public record OutlierSplit<T>(List<T> kept, List<T> outliers) {
    }

    /** Plain (unweighted) median. Returns NaN for an empty list. */
    public static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        return n % 2 == 1
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /** Median absolute deviation around the given center. */
    public static double mad(List<Double> values, double center) {
        List<Double> deviations = new ArrayList<>(values.size());
        for (double v : values) {
            deviations.add(Math.abs(v - center));
        }
        return median(deviations);
    }

    /**
     * Splits items into kept vs. outliers using a modified z-score on
     * log(price). Working in log space makes a $1 troll sale of a $10k item and
     * a $100M troll listing symmetric problems, and stops a single extreme sale
     * from dragging the whole distribution.
     *
     * <p>Outliers are returned, not discarded — callers persist them flagged.
     */
    public static <T> OutlierSplit<T> splitOutliersByLogPrice(List<T> items,
                                                             java.util.function.ToDoubleFunction<T> priceOf) {
        List<T> positive = new ArrayList<>();
        List<T> invalid = new ArrayList<>();
        for (T item : items) {
            if (priceOf.applyAsDouble(item) > 0) {
                positive.add(item);
            } else {
                invalid.add(item);
            }
        }
        if (positive.size() < 3) {
            // Too few samples to call anything an outlier statistically.
            return new OutlierSplit<>(positive, invalid);
        }

        List<Double> logs = new ArrayList<>(positive.size());
        for (T item : positive) {
            logs.add(Math.log(priceOf.applyAsDouble(item)));
        }
        double med = median(logs);
        double mad = mad(logs, med);

        List<T> kept = new ArrayList<>();
        List<T> outliers = new ArrayList<>(invalid);
        if (mad == 0) {
            // Perfectly uniform core: anything that deviates at all beyond a
            // small tolerance is an outlier.
            for (int i = 0; i < positive.size(); i++) {
                if (Math.abs(logs.get(i) - med) <= 1e-9) {
                    kept.add(positive.get(i));
                } else {
                    outliers.add(positive.get(i));
                }
            }
        } else {
            for (int i = 0; i < positive.size(); i++) {
                double z = 0.6745 * (logs.get(i) - med) / mad;
                if (Math.abs(z) <= OUTLIER_Z_CUTOFF) {
                    kept.add(positive.get(i));
                } else {
                    outliers.add(positive.get(i));
                }
            }
        }
        return new OutlierSplit<>(kept, outliers);
    }

    /** weight = 2^(-age / halfLife). Age at or before zero gets full weight. */
    public static double recencyWeight(long ageMillis, long halfLifeMillis) {
        if (ageMillis <= 0) return 1.0;
        if (halfLifeMillis <= 0) throw new IllegalArgumentException("halfLife must be positive");
        return Math.pow(2.0, -((double) ageMillis / halfLifeMillis));
    }

    /**
     * Weighted percentile (0..1) using cumulative-weight interpolation.
     * Returns NaN when total weight is zero or the list is empty.
     */
    public static double weightedPercentile(List<WeightedValue> samples, double percentile) {
        if (percentile < 0 || percentile > 1) {
            throw new IllegalArgumentException("percentile must be within [0,1]");
        }
        List<WeightedValue> sorted = new ArrayList<>();
        double total = 0;
        for (WeightedValue s : samples) {
            if (s.weight() > 0) {
                sorted.add(s);
                total += s.weight();
            }
        }
        if (sorted.isEmpty() || total <= 0) return Double.NaN;
        sorted.sort(Comparator.comparingDouble(WeightedValue::value));

        double target = percentile * total;
        double cumulative = 0;
        for (int i = 0; i < sorted.size(); i++) {
            double next = cumulative + sorted.get(i).weight();
            if (next >= target) {
                if (i == 0 || next == target) {
                    return sorted.get(i).value();
                }
                // Interpolate between the previous and current value.
                double prevValue = sorted.get(i - 1).value();
                double fraction = (target - cumulative) / sorted.get(i).weight();
                return prevValue + (sorted.get(i).value() - prevValue) * Math.min(1.0, fraction);
            }
            cumulative = next;
        }
        return sorted.get(sorted.size() - 1).value();
    }

    /**
     * Robust volatility as a fraction of price: sigma estimated from the MAD of
     * log prices. ~0.04 means prices scatter about ±4% around the median.
     */
    public static double robustLogVolatility(List<Double> prices) {
        List<Double> logs = new ArrayList<>();
        for (double p : prices) {
            if (p > 0) logs.add(Math.log(p));
        }
        if (logs.size() < 3) return Double.NaN;
        double med = median(logs);
        double sigma = mad(logs, med) * MAD_TO_SIGMA;
        // For small sigma, exp(sigma)-1 ≈ sigma: fractional price scatter.
        return Math.expm1(sigma);
    }
}
