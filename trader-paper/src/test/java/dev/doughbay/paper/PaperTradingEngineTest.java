package dev.doughbay.paper;

import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.Sale;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperTradingEngineTest {

    private static final String ITEM = "minecraft:redstone";
    private static final long NOW = 1_700_000_000_000L;
    private static final long DELAY = 15_000;

    private static PaperConfig config() {
        return new PaperConfig(2_000_000, DELAY, 12L * 3600_000L, FeeConfig.zero());
    }

    private static Listing listing(long price) {
        return new Listing("lst-1", NOW, "seller", "Seller", ITEM, ITEM, 64, price, null);
    }

    private static Opportunity opportunity(long buy, long sell) {
        MarketStats stats = new MarketStats(ITEM, StackBucket.X64, 7L * 24 * 3600_000L,
                40, 0, 8, NOW - 60_000,
                sell - 1000, sell, sell + 1000, sell + 2000, sell + 3000,
                12.0, 0.03, 0.0, 0.9, NOW);
        return new Opportunity(listing(buy), stats, buy, sell,
                sell - buy, (double) (sell - buy) / buy * 100, 0.1, 0.85, 0.85, 1.0,
                List.of("test"));
    }

    private static Sale saleAt(long soldAt, long totalPrice) {
        return new Sale("h" + soldAt + totalPrice, soldAt, "other-seller", "n",
                ITEM, ITEM, 64, totalPrice);
    }

    @Test
    void purchaseHappensOnlyAfterExecutionDelayIfListingSurvives() {
        PaperTradingEngine engine = new PaperTradingEngine(config());
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        assertEquals(2_000_000, engine.balance());

        // Before the delay elapses nothing is bought even if we tick.
        engine.tick(NOW + 1_000, Map.of("lst-1", listing(3_000)));
        assertEquals(2_000_000, engine.balance());

        // After the delay, with the listing still available, the buy fills.
        engine.tick(NOW + DELAY + 1, Map.of("lst-1", listing(3_000)));
        assertEquals(2_000_000 - 3_000, engine.balance());
        Position p = engine.positions().iterator().next();
        assertEquals(PositionStatus.SIMULATED_LISTED, p.status());
    }

    @Test
    void vanishedListingIsMissedNotWon() {
        PaperTradingEngine engine = new PaperTradingEngine(config());
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        // Deal disappeared before our simulated latency elapsed.
        engine.tick(NOW + DELAY + 1, Map.of());
        Position p = engine.positions().iterator().next();
        assertEquals(PositionStatus.MISSED_BEFORE_PURCHASE, p.status());
        assertEquals(2_000_000, engine.balance());
    }

    @Test
    void salesBeforeListingTimeNeverResolveThePosition() {
        PaperTradingEngine engine = new PaperTradingEngine(config());
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        long listedAt = NOW + DELAY + 1;
        engine.tick(listedAt, Map.of("lst-1", listing(3_000)));

        // A qualifying-priced sale that happened BEFORE we listed is history,
        // not evidence our listing sold.
        engine.onSales(List.of(saleAt(NOW - 1_000, 9_500)), listedAt + 1);
        Position p = engine.positions().iterator().next();
        assertEquals(PositionStatus.SIMULATED_LISTED, p.status());
    }

    @Test
    void futureSaleCannotResolveOrReleaseCapitalBeforeItsAsOfTime() {
        PaperTradingEngine engine = new PaperTradingEngine(config());
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        long listedAt = NOW + DELAY + 1;
        engine.tick(listedAt, Map.of("lst-1", listing(3_000)));
        Sale future = saleAt(listedAt + 60_000, 9_200);

        engine.onSales(List.of(future), listedAt + 30_000);
        assertEquals(PositionStatus.SIMULATED_LISTED,
                engine.positions().iterator().next().status());
        assertEquals(2_000_000 - 3_000, engine.balance());

        engine.onSales(List.of(future), listedAt + 60_000);
        assertEquals(PositionStatus.LIKELY_SOLD,
                engine.positions().iterator().next().status());
        assertEquals(2_000_000 - 3_000 + 9_000, engine.balance());
    }

    @Test
    void unorderedSaleBatchClosesAtTheEarliestQualifyingTimestamp() {
        PaperTradingEngine engine = new PaperTradingEngine(config());
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        long listedAt = NOW + DELAY + 1;
        engine.tick(listedAt, Map.of("lst-1", listing(3_000)));
        Sale earlier = saleAt(listedAt + 60_000, 9_200);
        Sale later = saleAt(listedAt + 120_000, 9_300);

        engine.onSales(List.of(later, earlier), listedAt + 120_000);

        Position position = engine.positions().iterator().next();
        assertEquals(PositionStatus.LIKELY_SOLD, position.status());
        assertEquals(earlier.soldAt(), position.closedAt());
    }

    @Test
    void enoughQualifyingSalesMarksLikelySoldAndCreditsBalance() {
        PaperTradingEngine engine = new PaperTradingEngine(config());
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        long listedAt = NOW + DELAY + 1;
        engine.tick(listedAt, Map.of("lst-1", listing(3_000)));

        engine.onSales(List.of(
                saleAt(listedAt + 60_000, 9_200),
                saleAt(listedAt + 120_000, 9_100),
                saleAt(listedAt + 180_000, 9_050)), listedAt + 180_000);

        Position p = engine.positions().iterator().next();
        assertEquals(PositionStatus.LIKELY_SOLD, p.status());
        assertEquals(2_000_000 - 3_000 + 9_000, engine.balance());
        assertEquals(6_000, p.realizedProfit(), 0.01);
    }

    @Test
    void cheapSalesDoNotResolveTheListing() {
        PaperTradingEngine engine = new PaperTradingEngine(config());
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        long listedAt = NOW + DELAY + 1;
        engine.tick(listedAt, Map.of("lst-1", listing(3_000)));

        // Sales far below our target don't demonstrate demand at our price.
        engine.onSales(List.of(
                saleAt(listedAt + 60_000, 4_000),
                saleAt(listedAt + 120_000, 4_100),
                saleAt(listedAt + 180_000, 3_900)), listedAt + 180_000);
        assertEquals(PositionStatus.SIMULATED_LISTED,
                engine.positions().iterator().next().status());
    }

    @Test
    void unsoldListingExpiresAndCapitalStaysStuck() {
        PaperTradingEngine engine = new PaperTradingEngine(config());
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        long listedAt = NOW + DELAY + 1;
        engine.tick(listedAt, Map.of("lst-1", listing(3_000)));

        engine.tick(listedAt + 13L * 3600_000L, Map.of());
        Position p = engine.positions().iterator().next();
        assertEquals(PositionStatus.EXPIRED, p.status());
        assertEquals(2_000_000 - 3_000, engine.balance());
        assertEquals(3_000, engine.inventoryValue());
    }

    @Test
    void feesAreDeductedFromSimulatedProceeds() {
        PaperConfig taxed = new PaperConfig(2_000_000, DELAY, 12L * 3600_000L,
                new FeeConfig(0, 0.0, 10.0));
        PaperTradingEngine engine = new PaperTradingEngine(taxed);
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        long listedAt = NOW + DELAY + 1;
        engine.tick(listedAt, Map.of("lst-1", listing(3_000)));
        engine.onSales(List.of(saleAt(listedAt + 60_000, 9_200)), listedAt + 60_000);

        Position p = engine.positions().iterator().next();
        assertEquals(PositionStatus.LIKELY_SOLD, p.status());
        // 9000 - 10% tax = 8100 proceeds; profit 5100.
        assertEquals(2_000_000 - 3_000 + 8_100, engine.balance());
        assertEquals(5_100, p.realizedProfit(), 0.01);
    }

    @Test
    void metricsSummarizeOutcomes() {
        PaperTradingEngine engine = new PaperTradingEngine(config());
        engine.onOpportunity(opportunity(3_000, 9_000), NOW);
        long listedAt = NOW + DELAY + 1;
        engine.tick(listedAt, Map.of("lst-1", listing(3_000)));
        engine.onSales(List.of(saleAt(listedAt + 60_000, 9_200)), listedAt + 60_000);

        PaperMetrics metrics = engine.metrics(listedAt + 120_000);
        assertEquals(1, metrics.totalSignals());
        assertEquals(1, metrics.likelySold());
        assertEquals(6_000, metrics.realizedProfit(), 0.01);
        assertEquals(100.0, metrics.winRatePercent(), 0.01);
        assertTrue(metrics.endingBalance() > metrics.startingBankroll());
    }
}
