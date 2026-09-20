package dev.doughbay.core.analysis;

import dev.doughbay.core.performance.MarketProfit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LosingMarketFilterTest {

    @Test
    void prunesASustainedLoserPastTheFloor() {
        Set<String> losers = LosingMarketFilter.losers(
                List.of(new MarketProfit("minecraft:dirt", -80_000, 15)),
                10, -50_000);
        assertTrue(losers.contains("minecraft:dirt"));
    }

    @Test
    void keepsAMarketThatIsMerelyBreakingEven() {
        Set<String> losers = LosingMarketFilter.losers(
                List.of(new MarketProfit("minecraft:dirt", -10_000, 20)),
                10, -50_000);
        assertTrue(losers.isEmpty());
    }

    @Test
    void keepsAProfitableMarket() {
        Set<String> losers = LosingMarketFilter.losers(
                List.of(new MarketProfit("minecraft:diamond", 5_000_000, 200)),
                10, -50_000);
        assertTrue(losers.isEmpty());
    }

    @Test
    void oneUnluckyFlipIsNotAVerdict() {
        // A big loss but only two settled trades: below the sample bar, kept.
        Set<String> losers = LosingMarketFilter.losers(
                List.of(new MarketProfit("minecraft:elytra", -900_000, 2)),
                10, -50_000);
        assertFalse(losers.contains("minecraft:elytra"));
    }

    @Test
    void aPositiveFloorIsClampedToZeroSoProfitIsNeverPruned() {
        // Even a misconfigured positive floor must not prune a market in profit.
        Set<String> losers = LosingMarketFilter.losers(
                List.of(new MarketProfit("minecraft:diamond", 1_000, 50)),
                10, 100_000);
        assertTrue(losers.isEmpty());
    }

    @Test
    void separatesLosersFromKeepersInAMixedRoster() {
        Set<String> losers = LosingMarketFilter.losers(
                List.of(
                        new MarketProfit("minecraft:dirt", -120_000, 30),
                        new MarketProfit("minecraft:diamond", 400_000, 40),
                        new MarketProfit("minecraft:sand", -60_000, 12),
                        new MarketProfit("minecraft:kelp", -55_000, 3)),
                10, -50_000);
        assertEquals(Set.of("minecraft:dirt", "minecraft:sand"), losers);
    }

    @Test
    void toleratesNullInput() {
        assertTrue(LosingMarketFilter.losers(null, 10, -50_000).isEmpty());
    }
}
