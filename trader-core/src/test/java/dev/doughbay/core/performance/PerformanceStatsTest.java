package dev.doughbay.core.performance;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceStatsTest {

    @Test
    void paperSummarySeparatesModesAndRanksItemsDeterministically() {
        List<Position> positions = new ArrayList<>(List.of(
                position(1, "PAPER", "minecraft:ender_pearl", 16, 100, 1_000,
                        61_000, 150, 45, PositionStatus.LIKELY_SOLD),
                position(2, "paper", "minecraft:redstone", 64, 200, 2_000,
                        122_000, 350, 140, PositionStatus.LIKELY_SOLD),
                position(3, "PAPER", "minecraft:ender_pearl", 16, 100, 3_000,
                        183_000, 90, -15, PositionStatus.LIKELY_SOLD),
                position(4, "PAPER", "minecraft:ender_pearl", 16, 500, 4_000,
                        0, 0, Double.NaN, PositionStatus.SIMULATED_LISTED),
                position(5, "PAPER", "minecraft:redstone", 64, 300, 5_000,
                        305_000, 0, Double.NaN, PositionStatus.EXPIRED),
                position(6, "PAPER", "minecraft:diamond", 1, 400, 6_000,
                        0, 0, Double.NaN, PositionStatus.UNRESOLVED),
                position(7, "PAPER", "minecraft:coal", 64, 50, 7_000,
                        70_000, 0, Double.NaN, PositionStatus.LIKELY_SOLD),
                position(8, "PAPER", "minecraft:ignored_wrong_status", 1, 60, 8_000,
                        80_000, 100, 40, PositionStatus.SOLD),
                position(9, "REAL", "minecraft:nether_star", 1, 1, 9_000,
                        10_000, 1_000_000, 999_999, PositionStatus.SOLD),
                position(10, "PAPER", "minecraft:signal", 1, 9_999, 0,
                        0, 0, Double.NaN, PositionStatus.SIGNAL)
        ));
        Collections.reverse(positions); // input order must not affect any result

        List<PerformanceStats.AccountValuePoint> values = List.of(
                new PerformanceStats.AccountValuePoint(500, 1_000, 0),
                new PerformanceStats.AccountValuePoint(70_000, 1_050, 50),
                new PerformanceStats.AccountValuePoint(-1, 99_999, 0));
        PerformanceStats stats = PerformanceStats.compute(
                PerformanceMode.PAPER, positions, values,
                new PerformanceStats.RuntimeSummary(10, 20, 1, true, 200));

        assertEquals("Simulated estimate", stats.scopeLabel());
        assertEquals(0, stats.currentSessionActiveMillis());
        assertEquals(0, stats.lifetimeAutomationActiveMillis());
        assertEquals(1_710, stats.capitalInvested());
        assertEquals(400, stats.settledCapital());
        assertEquals(500, stats.openCapital());
        assertEquals(300, stats.returnedInventoryCost());
        assertEquals(510, stats.unreconciledCost());
        assertEquals(590, stats.grossSales());
        assertEquals(96, stats.totalQuantitySold());
        assertEquals(170, stats.realizedProfit(), 0.0001);
        assertEquals(42.5, stats.realizedRoiPercent(), 0.0001);
        assertEquals(33.333, stats.averageRoiPercent(), 0.01);
        assertEquals(45, stats.medianRoiPercent(), 0.001);
        assertEquals(8, stats.purchasedTrades());
        assertEquals(3, stats.closedTrades());
        assertEquals(1, stats.openTrades());
        assertEquals(3, stats.completedSales());
        assertEquals(1, stats.returnedInventoryTrades());
        assertEquals(3, stats.unreconciledTrades());
        assertEquals(2, stats.wins());
        assertEquals(1, stats.losses());
        assertEquals(0, stats.breakEvenTrades());
        assertEquals(66.666, stats.winRatePercent(), 0.01);
        assertEquals(56.666, stats.averageProfit(), 0.01);
        assertEquals(45, stats.medianProfit(), 0.001);
        assertEquals(2, stats.averageHoldMinutes(), 0.001);
        assertEquals(2, stats.medianHoldMinutes(), 0.001);
        assertEquals("minecraft:redstone", stats.bestItemByProfit().orElseThrow().itemKey());
        assertEquals("minecraft:redstone", stats.mostSoldItem().orElseThrow().itemKey());
        assertEquals("minecraft:ender_pearl", stats.mostTradedItem().orElseThrow().itemKey());
        assertEquals(140, stats.largestFlip().orElseThrow().profit(), 0.001);
        assertEquals(2, stats.maxConsecutiveProfitableTrades());
        assertEquals(70, stats.bestRoiPercent(), 0.001);
        assertEquals(1, stats.fastestPositiveSaleMinutes(), 0.001);
        assertEquals(2, stats.distinctSoldItems());
        assertEquals(170, stats.currentCumulativeProfit(), 0.001);
        assertEquals(185, stats.peakCumulativeProfit(), 0.001);
        assertEquals(0, stats.ignoredPositions());
        assertEquals(1, stats.incompleteSettlements());

        assertFalse(stats.performanceSeries().isEmpty());
        PerformanceStats.PerformancePoint last = stats.performanceSeries().getLast();
        assertEquals(1_710, last.cumulativeCapitalInvested());
        assertEquals(170, last.cumulativeProfit(), 0.001);
        assertTrue(last.equityObserved());
        assertEquals(1_100, last.equity());
    }

    @Test
    void realModeUsesOnlySoldAndExposesDurableRuntime() {
        List<Position> positions = List.of(
                position(1, "REAL", "minecraft:diamond", 1, 100, 1_000,
                        2_000, 220, 120, PositionStatus.SOLD),
                position(2, "REAL", "minecraft:redstone", 64, 100, 2_000,
                        3_000, 300, 200, PositionStatus.LIKELY_SOLD));

        PerformanceStats stats = PerformanceStats.compute(
                PerformanceMode.REAL, positions, List.of(),
                new PerformanceStats.RuntimeSummary(12_000, 44_000, 1_000, true, 5_000));

        assertEquals("This local database", stats.scopeLabel());
        assertEquals(12_000, stats.currentSessionActiveMillis());
        assertEquals(44_000, stats.lifetimeAutomationActiveMillis());
        assertEquals(1, stats.completedSales());
        assertEquals(120, stats.realizedProfit(), 0.001);
        assertEquals(1, stats.unreconciledTrades());
        assertEquals(100, stats.unreconciledCost());
        assertTrue(stats.performanceSeries().stream().noneMatch(
                PerformanceStats.PerformancePoint::equityObserved));
    }

    @Test
    void malformedAndExtremeRowsCannotProduceNanOrOverflow() {
        Position invalid = position(1, "REAL", "", 0, 0, 0,
                0, 0, Double.NaN, PositionStatus.SOLD);
        Position huge1 = position(2, "REAL", "minecraft:a", 1, Long.MAX_VALUE,
                1, 2, Long.MAX_VALUE, Double.MAX_VALUE, PositionStatus.SOLD);
        Position huge2 = position(3, "REAL", "minecraft:b", 1, Long.MAX_VALUE,
                2, 3, Long.MAX_VALUE, Double.MAX_VALUE, PositionStatus.SOLD);
        Position infinite = position(4, "REAL", "minecraft:c", 1, 10,
                3, 4, 20, Double.POSITIVE_INFINITY, PositionStatus.SOLD);

        PerformanceStats stats = PerformanceStats.compute(
                PerformanceMode.REAL, List.of(invalid, huge1, huge2, infinite));

        assertEquals(Long.MAX_VALUE, stats.capitalInvested());
        assertEquals(Long.MAX_VALUE, stats.settledCapital());
        assertEquals(Long.MAX_VALUE, stats.grossSales());
        assertTrue(Double.isFinite(stats.realizedProfit()));
        assertTrue(Double.isFinite(stats.realizedRoiPercent()));
        assertTrue(Double.isFinite(stats.averageProfit()));
        assertEquals(1, stats.ignoredPositions());
        assertEquals(1, stats.incompleteSettlements());
        assertEquals(1, stats.unreconciledTrades());
    }

    @Test
    void sameTimestampUsesPositionIdForWinStreakAndSeries() {
        Position win = position(2, "REAL", "minecraft:a", 1, 100, 1_000,
                10_000, 120, 20, PositionStatus.SOLD);
        Position loss = position(1, "REAL", "minecraft:b", 1, 100, 2_000,
                10_000, 80, -20, PositionStatus.SOLD);

        PerformanceStats stats = PerformanceStats.compute(
                PerformanceMode.REAL, List.of(win, loss));

        // id 1 loss is applied before id 2 win at the same close time.
        assertEquals(1, stats.maxConsecutiveProfitableTrades());
        assertEquals(0, stats.currentCumulativeProfit(), 0.001);
        assertEquals(0, stats.peakCumulativeProfit(), 0.001);
        assertEquals(1, stats.performanceSeries().stream()
                .filter(point -> point.at() == 10_000).count());
    }

    @Test
    void holdTimeStartsWhenListedAndFallsBackToPurchaseTime() {
        Position listedLater = new Position(1, "REAL", "minecraft:a", StackBucket.X1,
                1, 100, 120, 1_000, 61_000, 181_000, 120, 20,
                PositionStatus.SOLD);
        Position listingTimestampMissing = new Position(2, "REAL", "minecraft:b",
                StackBucket.X1, 1, 100, 120, 1_000, 0, 181_000, 120, 20,
                PositionStatus.SOLD);

        PerformanceStats stats = PerformanceStats.compute(
                PerformanceMode.REAL, List.of(listedLater, listingTimestampMissing));

        assertEquals(2.5, stats.averageHoldMinutes(), 0.001);
        assertEquals(2.5, stats.medianHoldMinutes(), 0.001);
        assertEquals(2, stats.fastestPositiveSaleMinutes(), 0.001);
    }

    private static Position position(long id, String mode, String item, int quantity,
                                     long purchasePrice, long purchasedAt, long closedAt,
                                     long salePrice, double profit, PositionStatus status) {
        return new Position(id, mode, item, StackBucket.of(quantity), quantity,
                purchasePrice, salePrice, purchasedAt, purchasedAt, closedAt,
                salePrice, profit, status);
    }
}
