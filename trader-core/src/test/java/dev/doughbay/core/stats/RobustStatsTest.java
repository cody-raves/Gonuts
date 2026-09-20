package dev.doughbay.core.stats;

import dev.doughbay.core.stats.RobustStats.OutlierSplit;
import dev.doughbay.core.stats.RobustStats.WeightedValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobustStatsTest {

    @Test
    void trollSalesAreExcludedNotDeleted() {
        // A cheap commodity around 100 with one absurd 10M troll sale and one
        // 1-unit giveaway. Both must be flagged, neither may distort stats.
        List<Double> prices = new ArrayList<>(List.of(
                95.0, 100.0, 102.0, 98.0, 105.0, 99.0, 101.0, 97.0, 103.0, 100.0,
                10_000_000.0, 1.0));
        OutlierSplit<Double> split = RobustStats.splitOutliersByLogPrice(prices, d -> d);

        assertEquals(10, split.kept().size());
        assertEquals(2, split.outliers().size());
        assertTrue(split.outliers().contains(10_000_000.0));
        assertTrue(split.outliers().contains(1.0));
        // Nothing was lost.
        assertEquals(prices.size(), split.kept().size() + split.outliers().size());
    }

    @Test
    void normalSpreadIsNotFlagged() {
        List<Double> prices = List.of(90.0, 95.0, 100.0, 105.0, 110.0, 98.0, 102.0);
        OutlierSplit<Double> split = RobustStats.splitOutliersByLogPrice(prices, d -> d);
        assertTrue(split.outliers().isEmpty());
    }

    @Test
    void nonPositivePricesAlwaysExcluded() {
        List<Double> prices = List.of(100.0, 0.0, -50.0, 101.0, 99.0);
        OutlierSplit<Double> split = RobustStats.splitOutliersByLogPrice(prices, d -> d);
        assertEquals(3, split.kept().size());
        assertEquals(2, split.outliers().size());
    }

    @Test
    void recencyWeightHalvesEveryHalfLife() {
        long halfLife = 6L * 3600_000L;
        assertEquals(1.0, RobustStats.recencyWeight(0, halfLife), 1e-9);
        assertEquals(0.5, RobustStats.recencyWeight(halfLife, halfLife), 1e-9);
        assertEquals(0.25, RobustStats.recencyWeight(2 * halfLife, halfLife), 1e-9);
        // 5 minutes old: almost full weight.
        assertTrue(RobustStats.recencyWeight(5 * 60_000L, halfLife) > 0.99);
    }

    @Test
    void weightedPercentileFavorsHeavierSamples() {
        // Recent (heavy) samples at 100, stale (light) samples at 200:
        // the weighted median should sit near 100, not 150.
        List<WeightedValue> samples = List.of(
                new WeightedValue(100, 1.0), new WeightedValue(100, 1.0),
                new WeightedValue(100, 1.0), new WeightedValue(200, 0.05),
                new WeightedValue(200, 0.05));
        double median = RobustStats.weightedPercentile(samples, 0.5);
        assertTrue(median <= 110, "weighted median was " + median);
    }

    @Test
    void weightedPercentileBoundsAreOrdered() {
        List<WeightedValue> samples = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            samples.add(new WeightedValue(i, 1.0));
        }
        double p25 = RobustStats.weightedPercentile(samples, 0.25);
        double p50 = RobustStats.weightedPercentile(samples, 0.50);
        double p75 = RobustStats.weightedPercentile(samples, 0.75);
        assertTrue(p25 < p50 && p50 < p75);
        assertEquals(50, p50, 2.0);
    }

    @Test
    void volatilityReflectsScatter() {
        List<Double> tight = List.of(100.0, 101.0, 99.0, 100.0, 100.5, 99.5);
        List<Double> wide = List.of(60.0, 140.0, 80.0, 120.0, 100.0, 90.0);
        assertTrue(RobustStats.robustLogVolatility(tight) < 0.02);
        assertTrue(RobustStats.robustLogVolatility(wide) > 0.10);
    }
}
