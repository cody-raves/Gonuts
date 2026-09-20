package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-book coverage on DonutSMP. The listings endpoint publishes no listing
 * id, so two identical stacks from one seller collapse to a single key. That
 * is an identity limit of the API, not a failed read, and treating it as
 * incompleteness left a live session re-queuing the same scan every tick.
 */
class MarketWatcherCoverageTest {

    private static final String ITEM = "minecraft:ender_pearl";

    @Test
    void aCleanlyReadBookIsCompleteEvenWithCollapsedRows() {
        MarketWatcher.ExactListingCoverage coverage = new MarketWatcher.ExactListingCoverage(
                ITEM, List.of(), 1_000, 1_400, true, 0, 3, 2, "complete");
        assertTrue(coverage.complete());
        assertEquals(3, coverage.indistinguishableRows());
        assertTrue(coverage.isFreshFor(ITEM, 900));
    }

    @Test
    void collapsedRowsSurviveGoingStale() {
        MarketWatcher.ExactListingCoverage coverage = new MarketWatcher.ExactListingCoverage(
                ITEM, List.of(), 1_000, 1_400, true, 0, 3, 2, "complete");
        MarketWatcher.ExactListingCoverage stale = coverage.stale("cleared");
        assertFalse(stale.complete());
        assertEquals(3, stale.indistinguishableRows());
    }

    @Test
    void negativeCollapsedRowCountIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MarketWatcher.ExactListingCoverage(
                ITEM, List.of(), 1_000, 1_400, false, 0, -1, 2, "bad"));
    }

    @Test
    void anIncompleteResultIsNotRescannedImmediately() {
        // Page cap reached: this cannot complete on the next attempt either.
        MarketWatcher.ExactListingCoverage incomplete = new MarketWatcher.ExactListingCoverage(
                ITEM, List.of(), 1_000, 1_400, false, 0, 0, 10, "page cap reached");
        assertTrue(incomplete.retryTooSoonFor(ITEM, 900, 1_400 + 1));
        assertTrue(incomplete.retryTooSoonFor(ITEM, 900,
                1_400 + MarketWatcher.INCOMPLETE_COVERAGE_RETRY_MILLIS - 1));
        assertFalse(incomplete.retryTooSoonFor(ITEM, 900,
                1_400 + MarketWatcher.INCOMPLETE_COVERAGE_RETRY_MILLIS));
    }

    @Test
    void theRetryFloorOnlyCoversTheSameRequest() {
        MarketWatcher.ExactListingCoverage incomplete = new MarketWatcher.ExactListingCoverage(
                ITEM, List.of(), 1_000, 1_400, false, 0, 0, 10, "page cap reached");
        // A newer boundary is a new request; the old result cannot answer it.
        assertFalse(incomplete.retryTooSoonFor(ITEM, 1_000, 1_401));
        assertFalse(incomplete.retryTooSoonFor("minecraft:diamond", 900, 1_401));
    }

    @Test
    void placeholdersAndCompleteResultsNeverInvokeTheFloor() {
        assertFalse(MarketWatcher.ExactListingCoverage.pending(ITEM, 900)
                .retryTooSoonFor(ITEM, 900, 1_401));
        MarketWatcher.ExactListingCoverage complete = new MarketWatcher.ExactListingCoverage(
                ITEM, List.of(), 1_000, 1_400, true, 0, 0, 2, "complete");
        assertFalse(complete.retryTooSoonFor(ITEM, 900, 1_401));
    }
}
