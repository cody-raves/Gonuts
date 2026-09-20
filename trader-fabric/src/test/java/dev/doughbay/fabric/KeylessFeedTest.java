package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeylessFeedTest {

    @Test
    void ghostsAndWishfulAsksAreDropped() {
        // Eight asks: two cheap ghosts at the bottom, two wishful at the top.
        List<Long> kept = KeylessFeed.representativeAsks(
                List.of(900_000L, 1_000L, 1_200L, 300_000L, 6_000L, 6_200L, 6_100L, 6_300L), 6);
        assertEquals(List.of(6_000L, 6_100L, 6_200L, 6_300L), kept);
    }

    @Test
    void jokeListingsCannotBecomeThePrice() {
        // Seen live: 15 experience-bottle asks, two of them at 43M and 44M.
        List<Long> asks = new java.util.ArrayList<>(List.of(44_000_000L, 43_000_000L));
        for (int i = 0; i < 13; i++) asks.add(200_000L + i * 1_000);
        List<Long> kept = KeylessFeed.representativeAsks(asks, 6);
        assertTrue(kept.stream().allMatch(a -> a < 300_000), kept.toString());
    }

    @Test
    void manyAsksAreSampledEvenly() {
        List<Long> asks = new java.util.ArrayList<>();
        for (long p = 1; p <= 40; p++) asks.add(p * 100);
        List<Long> kept = KeylessFeed.representativeAsks(asks, 6);
        assertEquals(6, kept.size());
        assertTrue(kept.get(0) >= 1_100 && kept.get(5) <= 3_000, kept.toString());
    }

    @Test
    void oneOrTwoAsksAreKept() {
        assertEquals(List.of(5_000L), KeylessFeed.representativeAsks(List.of(5_000L), 6));
        assertEquals(2, KeylessFeed.representativeAsks(List.of(5_000L, 5_200L), 6).size());
        assertTrue(KeylessFeed.representativeAsks(List.of(), 6).isEmpty());
    }

    @Test
    void aFreshInstallStartsFromTheShippedTables() {
        // No API history to learn from: the jar's own tables stand in.
        assertTrue(KeylessFeed.bundled("keyless-ratios.txt").size() > 500);
        assertTrue(KeylessFeed.bundled("keyless-singles.txt").contains("minecraft:golden_carrot"));
        assertTrue(KeylessFeed.bundled("no-such-table.txt").isEmpty());
    }
}
