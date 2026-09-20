package dev.doughbay.storage;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import dev.doughbay.core.performance.PerformanceMode;
import dev.doughbay.core.performance.PerformanceStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PerformanceRepositoryTest {
    private Database db;
    private PositionRepository positions;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        positions = new PositionRepository(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    @Test
    void repositoryReportNeverMixesPaperAndReal() throws Exception {
        positions.insert(position("PAPER", "minecraft:redstone", 64,
                1_000, 2_000, 900, PositionStatus.LIKELY_SOLD));
        positions.insert(position("REAL", "minecraft:diamond", 1,
                1_000, 3_000, 1_900, PositionStatus.SOLD));
        positions.saveAutomationCheckpoint(new AutomationSessionCheckpoint(
                true, "CONTINUOUS", "MONITORING", 1, 10_000,
                500, 750, 2_500, 3_000,
                "runtime", "", null, AutomationUncertainExposure.none()));

        PerformanceStats paper = positions.performance(PerformanceMode.PAPER);
        PerformanceStats real = positions.performance(PerformanceMode.REAL);

        assertEquals(1, paper.completedSales());
        assertEquals(900, paper.realizedProfit(), 0.001);
        assertEquals("minecraft:redstone", paper.bestItemByProfit().orElseThrow().itemKey());
        assertEquals(0, paper.currentSessionActiveMillis());
        assertEquals(0, paper.lifetimeAutomationActiveMillis());
        assertEquals(1, real.completedSales());
        assertEquals(1_900, real.realizedProfit(), 0.001);
        assertEquals("minecraft:diamond", real.bestItemByProfit().orElseThrow().itemKey());
        assertEquals(750, real.currentSessionActiveMillis());
        assertEquals(2_500, real.lifetimeAutomationActiveMillis());
    }

    @Test
    void legacyCrossRunBalancesCannotChangeLifetimePerformance() throws Exception {
        positions.insert(position("PAPER", "minecraft:ender_pearl", 16,
                10_000, 20_000, 10_000, PositionStatus.LIKELY_SOLD));
        // These can belong to unrelated CLI runs. The lifetime stats API must
        // not claim either is authoritative account equity.
        positions.recordBalance(1_500, "PAPER", 2_000_000, 0);
        positions.recordBalance(2_500, "PAPER", 25, 999_999_999);

        PerformanceStats report = positions.performance(PerformanceMode.PAPER);

        assertEquals(10_000, report.realizedProfit(), 0.001);
        assertFalse(report.performanceSeries().isEmpty());
        assertFalse(report.performanceSeries().stream().anyMatch(
                PerformanceStats.PerformancePoint::equityObserved));
    }

    private static Position position(String mode, String item, int quantity,
                                     long purchasedAt, long closedAt, double profit,
                                     PositionStatus status) {
        long purchase = 10_000;
        long sale = purchase + Math.round(profit);
        return new Position(0, mode, item, StackBucket.of(quantity), quantity,
                purchase, sale, purchasedAt, purchasedAt + 500, closedAt,
                sale, profit, status);
    }
}
