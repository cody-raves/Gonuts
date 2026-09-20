package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceStatsSnapshotTest {

    @Test
    void demoKeepsPaperAndRealLedgersExplicit() {
        long now = 1_900_000_000_000L;
        PerformanceStatsSnapshot paper = PerformanceStatsSnapshot.demo(
                PerformanceStatsSnapshot.Mode.PAPER, now);
        PerformanceStatsSnapshot real = PerformanceStatsSnapshot.demo(
                PerformanceStatsSnapshot.Mode.REAL, now);

        assertEquals(PerformanceStatsSnapshot.Mode.PAPER, paper.mode());
        assertEquals(PerformanceStatsSnapshot.Mode.REAL, real.mode());
        assertTrue(paper.status().contains("DEMO PAPER"));
        assertTrue(real.status().contains("DEMO REAL"));
        assertTrue(paper.demo());
        assertEquals(PerformanceStatsSnapshot.Mode.REAL,
                PerformanceStatsSnapshot.Mode.PAPER.next());
        assertEquals(PerformanceStatsSnapshot.Mode.PAPER,
                PerformanceStatsSnapshot.Mode.REAL.next());
    }

    @Test
    void realizedRoiUsesOnlySettledCapital() {
        PerformanceStatsSnapshot stats = PerformanceStatsSnapshot.demo(
                PerformanceStatsSnapshot.Mode.PAPER, 1_900_000_000_000L);

        assertEquals(stats.realizedProfit() * 100.0 / stats.settledCapital(),
                stats.realizedRoiPercent(), 0.0001);
    }

    @Test
    void rankingsUseProfitAndUnitsIndependently() {
        PerformanceStatsSnapshot stats = PerformanceStatsSnapshot.demo(
                PerformanceStatsSnapshot.Mode.PAPER, 1_900_000_000_000L);

        assertEquals("Obsidian x64", stats.mostProfitableItems().getFirst().displayName());
        assertEquals("Redstone x64", stats.mostSoldItem().displayName());
    }

    @Test
    void runtimeAloneIsStillHistoryWorthShowing() {
        PerformanceStatsSnapshot runtimeOnly = snapshotWithRuntime(60_000, 3_600_000);

        assertTrue(runtimeOnly.hasHistory());
        assertFalse(PerformanceStatsSnapshot.empty(
                PerformanceStatsSnapshot.Mode.REAL, "not loaded").hasHistory());
    }

    @Test
    void classifiedOutcomesCannotExceedCompletedSales() {
        assertThrows(IllegalArgumentException.class, () -> new PerformanceStatsSnapshot(
                PerformanceStatsSnapshot.Mode.REAL, false, "test",
                1, 1, 0, 0, 0, 1, 1, 0, 0,
                1, 1, 1, 0, 0, 0, 0, null, null, null,
                0, 0, List.of(), List.of(), List.of()));
    }

    @Test
    void milestoneProgressIsBoundedAndUnlocksAtTarget() {
        var locked = new PerformanceStatsSnapshot.Milestone(
                "sales", "SALES", "test", 7, 10);
        var unlocked = new PerformanceStatsSnapshot.Milestone(
                "sales", "SALES", "test", 12, 10);

        assertEquals(0.7, locked.completion(), 0.0001);
        assertFalse(locked.unlocked());
        assertEquals(1.0, unlocked.completion(), 0.0001);
        assertTrue(unlocked.unlocked());
    }

    @Test
    void negativeProfitRateIsAValidMeasuredOutcome() {
        PerformanceStatsSnapshot loss = snapshotWithProfitRate(-2_500L);

        assertEquals(-2_500L, loss.profitPerHour());
    }

    private static PerformanceStatsSnapshot snapshotWithRuntime(long session, long lifetime) {
        return new PerformanceStatsSnapshot(
                PerformanceStatsSnapshot.Mode.REAL, false, "local",
                0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, null, null, null,
                session, lifetime, List.of(), List.of(), List.of());
    }

    private static PerformanceStatsSnapshot snapshotWithProfitRate(long profitPerHour) {
        return new PerformanceStatsSnapshot(
                PerformanceStatsSnapshot.Mode.REAL, false, "this local database",
                10_000, 10_000, -500, 0, 0, 64, 1, 1, 0, 0,
                0, 1, 0, -5, -500, 15 * 60_000L,
                null, null, profitPerHour, 0, 0,
                List.of(new PerformanceStatsSnapshot.ProfitPoint(1, -500, 10_000)),
                List.of(), List.of());
    }
}
