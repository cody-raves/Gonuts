package dev.doughbay.core.analysis;

import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Sale;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketAnalyzerTest {

    private static final String ITEM = "minecraft:ender_pearl";
    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 3600_000L;

    private final MarketAnalyzer analyzer = new MarketAnalyzer(AnalyzerConfig.defaults());

    private static Sale sale(long soldAt, String seller, int count, long totalPrice) {
        return new Sale("h" + soldAt + seller + totalPrice, soldAt, seller, seller,
                ITEM, ITEM, count, totalPrice);
    }

    @Test
    void aSplitMarketIsNotReportedAsTight() {
        // Two price regimes, the larger holding well over half the sample. A
        // median absolute deviation shrugs that off by design and would call
        // this market perfectly tight, which then reads as high confidence.
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sales.add(sale(NOW - i * 60_000L, "s" + (i % 8), 1, 700_000));
        }
        for (int i = 0; i < 24; i++) {
            sales.add(sale(NOW - i * 60_000L - 30_000L, "b" + (i % 8), 1, 270_000));
        }

        MarketStats stats = analyzer.analyze(ITEM, StackBucket.X1, sales, NOW).stats();

        // The minority is stripped as outliers, leaving a uniform core. The
        // number that must not lie is confidence: it is computed after the
        // disagreement was discarded, so it has to account for the discarding.
        assertTrue(stats.outlierCount() > 0, "expected the minority to be discarded");
        assertTrue(stats.confidence() < 0.5,
                "split market reported confidence " + stats.confidence()
                        + " after discarding " + stats.outlierCount()
                        + " of " + (stats.sampleCount() + stats.outlierCount()) + " sales");
    }

    @Test
    void aGenuinelyTightMarketKeepsItsConfidence() {
        // The other half of the change: adding a second estimate must not
        // penalise a market that really does trade at one price.
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sales.add(sale(NOW - i * 60_000L, "s" + (i % 8), 1, 20_000));
        }

        MarketStats stats = analyzer.analyze(ITEM, StackBucket.X1, sales, NOW).stats();

        assertTrue(stats.robustVolatility() < 0.02,
                "tight market reported volatility " + stats.robustVolatility());
        assertTrue(stats.confidence() > 0.9,
                "tight market reported confidence " + stats.confidence());
    }

    @Test
    void discardedSalesCostConfidence() {
        double clean = MarketAnalyzer.confidence(40, 0, 8, 0.0, 0);
        double third = MarketAnalyzer.confidence(40, 20, 8, 0.0, 0);

        assertTrue(clean > 0.95, "clean market confidence " + clean);
        assertTrue(third < 0.5, "heavily filtered market confidence " + third);
        assertTrue(third < clean);
    }

    @Test
    void spreadVolatilityIgnoresDegenerateBands() {
        // Guard the arithmetic: zero, inverted, and equal bands are all "no
        // information", never a negative or infinite scatter.
        assertEquals(0.0, MarketAnalyzer.spreadVolatility(0, 100));
        assertEquals(0.0, MarketAnalyzer.spreadVolatility(100, 0));
        assertEquals(0.0, MarketAnalyzer.spreadVolatility(100, 100));
        assertEquals(0.0, MarketAnalyzer.spreadVolatility(200, 100));
        assertTrue(MarketAnalyzer.spreadVolatility(100, 400) > 0.5);
    }

    @Test
    void futureSalesNeverAffectTheDecision() {
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            sales.add(sale(NOW - i * 10 * 60_000L, "s" + (i % 6), 16, 9_000));
        }
        // A crash to 1000 that happens AFTER the decision time must be invisible.
        for (int i = 0; i < 30; i++) {
            sales.add(sale(NOW + (i + 1) * 60_000L, "s" + (i % 6), 16, 1_000));
        }
        MarketStats stats = analyzer.analyze(ITEM, StackBucket.X16, sales, NOW).stats();
        assertEquals(30, stats.sampleCount());
        assertEquals(9_000, stats.weightedMedian(), 200);
    }

    @Test
    void fallingMarketShowsNegativeTrend() {
        List<Sale> sales = new ArrayList<>();
        // Older sales (2-6 days ago) around 10k, recent (last day) around 7k.
        for (int i = 0; i < 20; i++) {
            sales.add(sale(NOW - 2 * 24 * HOUR - i * 3 * HOUR, "s" + (i % 5), 16, 10_000));
        }
        for (int i = 0; i < 20; i++) {
            sales.add(sale(NOW - i * 60 * 60_000L / 2, "s" + (i % 5), 16, 7_000));
        }
        MarketStats stats = analyzer.analyze(ITEM, StackBucket.X16, sales, NOW).stats();
        assertTrue(stats.trend() < -0.10, "trend was " + stats.trend());
    }

    @Test
    void bandsAreOrderedAndOutliersFlagged() {
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sales.add(sale(NOW - i * 5 * 60_000L, "s" + (i % 8), 16, 8_500 + (i % 7) * 250));
        }
        sales.add(sale(NOW - 30_000, "troll", 16, 90_000_000));

        MarketAnalyzer.Analysis analysis = analyzer.analyze(ITEM, StackBucket.X16, sales, NOW);
        MarketStats stats = analysis.stats();
        assertEquals(1, analysis.outliers().size());
        assertEquals(40, stats.sampleCount());
        assertTrue(stats.lowerBound() <= stats.quickSalePrice());
        assertTrue(stats.quickSalePrice() <= stats.weightedMedian());
        assertTrue(stats.weightedMedian() <= stats.patientSalePrice());
        assertTrue(stats.patientSalePrice() <= stats.upperBound());
        // The quick-sale value must stay conservative: below the max ever seen.
        assertTrue(stats.quickSalePrice() < 10_100);
    }

    @Test
    void exactStackBucketsRemainIsolated() {
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sales.add(sale(NOW - i * 60_000L, "a" + i, 16, 9_000));
            sales.add(sale(NOW - i * 60_000L, "b" + i, 64, 30_000));
        }
        MarketStats x16 = analyzer.analyze(ITEM, StackBucket.X16, sales, NOW).stats();
        MarketStats x64 = analyzer.analyze(ITEM, StackBucket.X64, sales, NOW).stats();

        assertEquals(10, x16.sampleCount());
        assertEquals(9_000, x16.weightedMedian(), 100);
        assertEquals(10, x64.sampleCount());
        assertEquals(30_000, x64.weightedMedian(), 100);
    }

    @Test
    void recentCompletedSalesReceiveMoreWeightThanOldCompletedSales() {
        AnalyzerConfig shortHalfLife = new AnalyzerConfig(
                HOUR,
                2L * 24 * HOUR,
                3 * HOUR,
                0.375,
                0.625,
                0.25);
        MarketAnalyzer recencyWeightedAnalyzer = new MarketAnalyzer(shortHalfLife);
        List<Sale> sales = new ArrayList<>();

        // Equal sample counts make the unweighted center sit between the two
        // regimes. The one-hour half-life should make the current regime win.
        for (int i = 0; i < 20; i++) {
            sales.add(sale(NOW - 24 * HOUR - i * 60_000L,
                    "old-" + (i % 8), 16, 9_000 + (i % 5) * 500L));
            sales.add(sale(NOW - i * 60_000L,
                    "new-" + (i % 8), 16, 18_000 + (i % 5) * 1_000L));
        }

        MarketAnalyzer.Analysis analysis =
                recencyWeightedAnalyzer.analyze(ITEM, StackBucket.X16, sales, NOW);

        assertEquals(40, analysis.stats().sampleCount());
        assertTrue(analysis.outliers().isEmpty());
        assertTrue(analysis.stats().weightedMedian() >= 18_000,
                () -> "recent regime did not dominate: " + analysis.stats().weightedMedian());
    }

    @Test
    void analysisExposesOnlyTheExactCompletedSaleCohortBehindItsStats() {
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            sales.add(sale(NOW - i * 60_000L, "valid-" + i, 16,
                    9_000 + (i % 3) * 100L));
        }
        Sale extreme = sale(NOW - 30_000L, "extreme", 16, 90_000_000);
        sales.add(extreme);
        sales.add(sale(NOW - 60_000L, "wrong-stack", 64, 36_000));
        sales.add(new Sale("wrong-item", NOW - 60_000L, "seller", "Seller",
                "minecraft:diamond", "minecraft:diamond", 16, 9_000));
        sales.add(sale(NOW + 60_000L, "future", 16, 9_000));
        sales.add(sale(NOW - 8L * 24 * HOUR, "stale", 16, 9_000));
        sales.add(sale(NOW - 60_000L, "invalid", 16, 0));

        MarketAnalyzer.Analysis analysis =
                analyzer.analyze(ITEM, StackBucket.X16, sales, NOW);

        assertEquals(30, analysis.stats().sampleCount());
        assertEquals(30, analysis.keptSales().size());
        assertEquals(List.of(extreme), analysis.outliers());
        assertTrue(analysis.keptSales().stream().allMatch(Sale::isValid));
        assertTrue(analysis.keptSales().stream().allMatch(s -> s.itemKey().equals(ITEM)));
        assertTrue(analysis.keptSales().stream().allMatch(s -> s.bucket() == StackBucket.X16));
        assertTrue(analysis.keptSales().stream().allMatch(s -> s.soldAt() <= NOW));
        assertTrue(analysis.keptSales().stream()
                .allMatch(s -> NOW - s.soldAt() <= AnalyzerConfig.defaults().windowMillis()));
    }

    @Test
    void staleDataLowersConfidence() {
        double fresh = MarketAnalyzer.confidence(30, 0, 8, 0.03, 5 * 60_000L);
        double stale = MarketAnalyzer.confidence(30, 0, 8, 0.03, 12 * HOUR);
        assertTrue(fresh > stale * 2);
    }

    @Test
    void thinSellerBaseLowersConfidence() {
        double diverse = MarketAnalyzer.confidence(30, 0, 8, 0.03, 5 * 60_000L);
        double thin = MarketAnalyzer.confidence(30, 0, 1, 0.03, 5 * 60_000L);
        assertTrue(diverse > thin);
    }
}
