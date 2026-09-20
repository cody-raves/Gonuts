package dev.doughbay.storage;

import dev.doughbay.core.analysis.LosingMarketFilter;
import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import dev.doughbay.core.performance.MarketProfit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RealizedProfitWindowTest {

    private static final long NOW = 10_000_000_000L;
    private static final long DAY = 24L * 3_600_000L;

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

    /** A settled REAL sale with sale price fixed positive and profit chosen freely. */
    private static Position sold(String item, long closedAt, double profit) {
        return new Position(0, "REAL", item, StackBucket.of(1), 1,
                10_000, 20_000, closedAt - 1_000, closedAt - 500, closedAt,
                20_000, profit, PositionStatus.SOLD);
    }

    private Map<String, MarketProfit> byItem(long since) throws Exception {
        return positions.realizedProfitByItemSince(since).stream()
                .collect(Collectors.toMap(MarketProfit::itemKey, Function.identity()));
    }

    @Test
    void countsOnlyRecentSettledRealSales() throws Exception {
        long since = NOW - 5 * DAY;

        // 12 recent losing dirt sales, -6,000 each = -72,000 inside the window.
        for (int i = 0; i < 12; i++) positions.insert(sold("minecraft:dirt", NOW - 3_600_000L, -6_000));
        // A huge dirt loss from ten days ago must NOT count — it has aged out.
        positions.insert(sold("minecraft:dirt", NOW - 10 * DAY, -500_000));
        // 12 recent winning diamond sales, +5,000 each.
        for (int i = 0; i < 12; i++) positions.insert(sold("minecraft:diamond", NOW - 3_600_000L, 5_000));
        // Noise that must be ignored: PAPER, and REAL-but-still-LISTED.
        positions.insert(new Position(0, "PAPER", "minecraft:dirt", StackBucket.of(1), 1,
                10_000, 20_000, NOW - 3_600_000L, NOW - 3_500_000L, NOW - 3_400_000L,
                20_000, -9_000, PositionStatus.LIKELY_SOLD));
        positions.insert(new Position(0, "REAL", "minecraft:dirt", StackBucket.of(1), 1,
                10_000, 20_000, NOW - 3_600_000L, NOW - 3_500_000L, 0,
                0, Double.NaN, PositionStatus.LISTED));

        Map<String, MarketProfit> pnl = byItem(since);

        assertEquals(-72_000, pnl.get("minecraft:dirt").realizedProfit(), 0.001,
                "old loss must age out of the window");
        assertEquals(12, pnl.get("minecraft:dirt").settledSales());
        assertEquals(60_000, pnl.get("minecraft:diamond").realizedProfit(), 0.001);
        assertEquals(12, pnl.get("minecraft:diamond").settledSales());
    }

    @Test
    void feedsTheFilterSoOnlyTheRecentLoserIsPruned() throws Exception {
        long since = NOW - 5 * DAY;
        for (int i = 0; i < 12; i++) positions.insert(sold("minecraft:dirt", NOW - 3_600_000L, -6_000));
        for (int i = 0; i < 12; i++) positions.insert(sold("minecraft:diamond", NOW - 3_600_000L, 5_000));

        Set<String> losers = LosingMarketFilter.losers(
                positions.realizedProfitByItemSince(since), 10, -50_000);

        assertTrue(losers.contains("minecraft:dirt"));
        assertFalse(losers.contains("minecraft:diamond"));
    }

    @Test
    void aDroppedMarketAgesBackInOnceItsRecentTradesLeaveTheWindow() throws Exception {
        // Dirt lost heavily, but all of it is now older than the window: with no
        // recent trades it falls below the sample bar and is no longer a loser.
        for (int i = 0; i < 20; i++) positions.insert(sold("minecraft:dirt", NOW - 8 * DAY, -6_000));

        long since = NOW - 5 * DAY;
        Set<String> losers = LosingMarketFilter.losers(
                positions.realizedProfitByItemSince(since), 10, -50_000);

        assertFalse(losers.contains("minecraft:dirt"),
                "a market with no recent trades gets a fresh try");
    }
}
