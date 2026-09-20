package dev.doughbay.core.analysis;

import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Sale;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the plan's worked example: target listing $40k, historical quick
 * $88k, median $94k, and active competition at $91k or $72k.
 */
class OpportunityDetectorTest {

    private static final String ITEM = "minecraft:obsidian";
    private static final long NOW = 1_700_000_000_000L;

    private static final OpportunityDetector.Bankroll RICH =
            new OpportunityDetector.Bankroll(10_000_000, 10_000_000, 0);

    private final OpportunityDetector detector =
            new OpportunityDetector(FeeConfig.zero(), RiskConfig.defaults());

    private static MarketStats healthyStats() {
        return new MarketStats(ITEM, StackBucket.X64, 7L * 24 * 3600_000L,
                47, 1, 9, NOW - 5 * 60_000L,
                82_000, 88_000, 94_000, 99_000, 104_000,
                18.4, 0.042, 0.02, 0.93, NOW);
    }

    private static Listing listing(String key, long price) {
        return new Listing(key, NOW, "seller-" + key, "Seller", ITEM, ITEM, 64, price, null);
    }

    private static List<Sale> keptSales() {
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 47; i++) {
            sales.add(new Sale("h" + i, NOW - i * 3 * 60_000L, "s" + (i % 9), "n",
                    ITEM, ITEM, 64, 94_000 + (i % 5) * 1000));
        }
        return sales;
    }

    private static List<Sale> completedSales(int count) {
        return completedSales(count, 0);
    }

    private static List<Sale> completedSales(int count, long newestAgeMillis) {
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sales.add(new Sale("completed-" + newestAgeMillis + "-" + i,
                    NOW - newestAgeMillis - i * 3 * 60_000L,
                    "completed-seller-" + (i % 9), "Seller",
                    ITEM, ITEM, 64, 86_000 + (i % 7) * 1_000L));
        }
        return sales;
    }

    private static List<Sale> volatileCompletedSales() {
        long[] prices = {55_000, 65_000, 75_000, 85_000, 95_000,
                110_000, 130_000, 155_000, 180_000};
        List<Sale> sales = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            sales.add(new Sale("volatile-" + i, NOW - i * 3 * 60_000L,
                    "volatile-seller-" + (i % 9), "Seller",
                    ITEM, ITEM, 64, prices[i % prices.length]));
        }
        return sales;
    }

    @Test
    void completedSaleHistorySetsTheTargetAndHighActiveAsksCannotInflateIt() {
        List<Sale> completed = completedSales(47);
        MarketStats fromCompletedSales = new MarketAnalyzer(AnalyzerConfig.defaults())
                .analyze(ITEM, StackBucket.X64, completed, NOW)
                .stats();
        Listing target = listing("target", 40_000);

        OpportunityDetector.Evaluation withoutCompetition =
                detector.evaluate(target, fromCompletedSales, completed, List.of(), RICH);
        OpportunityDetector.Evaluation withExtremeAsk =
                detector.evaluate(target, fromCompletedSales, completed,
                        List.of(listing("extreme-ask", 50_000_000)), RICH);

        assertTrue(withoutCompetition.accepted(), () -> "rejections: " + withoutCompetition.rejections());
        assertTrue(withExtremeAsk.accepted(), () -> "rejections: " + withExtremeAsk.rejections());
        long historicalQuickSale = (long) Math.floor(fromCompletedSales.quickSalePrice());
        assertEquals(historicalQuickSale,
                withoutCompetition.opportunity().orElseThrow().recommendedSellPrice());
        assertEquals(historicalQuickSale,
                withExtremeAsk.opportunity().orElseThrow().recommendedSellPrice(),
                "an active asking price must never raise the completed-sale target");
    }

    @Test
    void activeAsksCannotChangeCompletedSaleConfidenceOrDemand() {
        Listing target = listing("target", 40_000);
        OpportunityDetector.Evaluation withoutCompetition =
                detector.evaluate(target, healthyStats(), keptSales(), List.of(), RICH);
        OpportunityDetector.Evaluation withSeveralExtremeAsks =
                detector.evaluate(target, healthyStats(), keptSales(), List.of(
                        listing("extreme-1", 40_000_000),
                        listing("extreme-2", 50_000_000),
                        listing("extreme-3", 60_000_000)), RICH);

        Opportunity baseline = withoutCompetition.opportunity().orElseThrow();
        Opportunity surrounded = withSeveralExtremeAsks.opportunity().orElseThrow();
        assertEquals(baseline.recommendedSellPrice(), surrounded.recommendedSellPrice());
        assertEquals(baseline.confidence(), surrounded.confidence(), 1e-12,
                "asking-price depth is not completed-sale confidence evidence");
        assertEquals(baseline.stats().salesPerHour(), surrounded.stats().salesPerHour(), 1e-12,
                "demand must remain the completed-sales rate");
    }

    @Test
    void resaleCapsAtHistoricalQuickSaleWhenCompetitionIsHigher() {
        Listing target = listing("target", 40_000);
        List<Listing> competition = List.of(listing("next", 91_000));

        OpportunityDetector.Evaluation eval =
                detector.evaluate(target, healthyStats(), keptSales(), competition, RICH);
        assertTrue(eval.accepted(), () -> "rejections: " + eval.rejections());
        Opportunity o = eval.opportunity().orElseThrow();
        assertEquals(88_000, o.recommendedSellPrice());
        assertEquals(48_000, o.expectedNetProfit(), 1);
    }

    @Test
    void resaleUndercutsCheaperActiveCompetition() {
        Listing target = listing("target", 40_000);
        List<Listing> competition = List.of(listing("next", 72_000));

        OpportunityDetector.Evaluation eval =
                detector.evaluate(target, healthyStats(), keptSales(), competition, RICH);
        assertTrue(eval.accepted(), () -> "rejections: " + eval.rejections());
        long recommended = eval.opportunity().orElseThrow().recommendedSellPrice();
        assertEquals(71_999, recommended);
        assertTrue(recommended <= healthyStats().quickSalePrice(),
                "active competition may only cap the historical quick-sale target");
    }

    @Test
    void remainingAskBelowCandidatePriceCapsTargetAndRejectsTheTrade() {
        Listing target = listing("target", 40_000);
        List<Listing> competition = List.of(listing("cheaper-remaining-ask", 35_000));

        OpportunityDetector.Evaluation eval =
                detector.evaluate(target, healthyStats(), keptSales(), competition, RICH);

        assertFalse(eval.accepted());
        assertTrue(eval.rejections().stream().anyMatch(r -> r.contains("Profit")),
                () -> "cheaper competition must destroy the margin: " + eval.rejections());
    }

    @Test
    void destroyedMarginIsRejected() {
        // Competition at 41k leaves ~999 profit — below the 5000 minimum.
        Listing target = listing("target", 40_000);
        List<Listing> competition = List.of(listing("next", 41_000));

        OpportunityDetector.Evaluation eval =
                detector.evaluate(target, healthyStats(), keptSales(), competition, RICH);
        assertFalse(eval.accepted());
        assertTrue(eval.rejections().stream().anyMatch(r -> r.contains("Profit")),
                () -> "rejections: " + eval.rejections());
    }

    @Test
    void feesReduceProfit() {
        OpportunityDetector taxed = new OpportunityDetector(
                new FeeConfig(0, 0.0, 10.0), RiskConfig.defaults());
        Listing target = listing("target", 40_000);
        OpportunityDetector.Evaluation eval =
                taxed.evaluate(target, healthyStats(), keptSales(), List.of(), RICH);
        assertTrue(eval.accepted(), () -> "rejections: " + eval.rejections());
        // 88_000 * 0.9 - 40_000 = 39_200
        assertEquals(39_200, eval.opportunity().orElseThrow().expectedNetProfit(), 1);
    }

    @Test
    void tooFewSamplesIsRejected() {
        List<Sale> completed = completedSales(5);
        MarketStats thin = new MarketAnalyzer(AnalyzerConfig.defaults())
                .analyze(ITEM, StackBucket.X64, completed, NOW)
                .stats();
        OpportunityDetector.Evaluation eval =
                detector.evaluate(listing("t", 40_000), thin, completed, List.of(), RICH);
        assertFalse(eval.accepted());
        assertTrue(eval.rejections().stream().anyMatch(r -> r.contains("sales (need")),
                () -> "rejections: " + eval.rejections());
    }

    @Test
    void fallingMarketIsRejected() {
        MarketStats falling = new MarketStats(ITEM, StackBucket.X64, 7L * 24 * 3600_000L,
                47, 1, 9, NOW - 5 * 60_000L,
                82_000, 88_000, 94_000, 99_000, 104_000,
                18.4, 0.042, -0.30, 0.93, NOW);
        OpportunityDetector.Evaluation eval =
                detector.evaluate(listing("t", 40_000), falling, keptSales(), List.of(), RICH);
        assertFalse(eval.accepted());
        assertTrue(eval.rejections().stream().anyMatch(r -> r.contains("Falling")));
    }

    @Test
    void volatileCompletedSaleHistoryIsRejected() {
        List<Sale> completed = volatileCompletedSales();
        MarketStats volatileMarket = new MarketAnalyzer(AnalyzerConfig.defaults())
                .analyze(ITEM, StackBucket.X64, completed, NOW)
                .stats();

        OpportunityDetector.Evaluation eval =
                detector.evaluate(listing("t", 40_000), volatileMarket, completed, List.of(), RICH);

        assertTrue(volatileMarket.robustVolatility() > RiskConfig.defaults().maximumVolatility());
        assertFalse(eval.accepted());
        assertTrue(eval.rejections().stream().anyMatch(r -> r.contains("Volatility")),
                () -> "rejections: " + eval.rejections());
    }

    @Test
    void staleCompletedSaleHistoryIsRejected() {
        List<Sale> completed = completedSales(47, 2 * 3600_000L);
        MarketStats staleMarket = new MarketAnalyzer(AnalyzerConfig.defaults())
                .analyze(ITEM, StackBucket.X64, completed, NOW)
                .stats();

        OpportunityDetector.Evaluation eval =
                detector.evaluate(listing("t", 40_000), staleMarket, completed, List.of(), RICH);

        assertFalse(eval.accepted());
        assertTrue(eval.rejections().stream().anyMatch(r -> r.contains("Last sale")),
                () -> "rejections: " + eval.rejections());
    }

    @Test
    void bankrollRulesBlockOversizedPositions() {
        // 40k listing but a 100k bankroll: exceeds the 10% position cap.
        OpportunityDetector.Bankroll small = new OpportunityDetector.Bankroll(100_000, 100_000, 0);
        OpportunityDetector.Evaluation eval =
                detector.evaluate(listing("t", 40_000), healthyStats(), keptSales(), List.of(), small);
        assertFalse(eval.accepted());
    }

    @Test
    void maxConcurrentPositionsBlocksNewSignals() {
        OpportunityDetector.Bankroll busy = new OpportunityDetector.Bankroll(10_000_000, 10_000_000, 8);
        OpportunityDetector.Evaluation eval =
                detector.evaluate(listing("t", 40_000), healthyStats(), keptSales(), List.of(), busy);
        assertFalse(eval.accepted());
    }

    @Test
    void acceptedOpportunityExplainsItself() {
        OpportunityDetector.Evaluation eval =
                detector.evaluate(listing("t", 40_000), healthyStats(), keptSales(), List.of(), RICH);
        Opportunity o = eval.opportunity().orElseThrow();
        assertFalse(o.reasons().isEmpty());
        assertTrue(o.reasons().stream().anyMatch(r -> r.contains("below conservative value")));
    }
}
