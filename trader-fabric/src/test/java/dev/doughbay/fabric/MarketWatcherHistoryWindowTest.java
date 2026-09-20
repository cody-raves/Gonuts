package dev.doughbay.fabric;

import dev.doughbay.engine.MarketService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transaction window has to cover the gap between two polls. The API keeps
 * only the most recent 1,000 sales and cannot page further back, so anything
 * that scrolls past the window between polls is lost permanently and silently.
 */
class MarketWatcherHistoryWindowTest {

    private static MarketService.CollectReport report(int seen, int added, int duplicates) {
        return new MarketService.CollectReport(2, seen, added, duplicates, 0, List.of());
    }

    @Test
    void healthyOverlapIsNotAGap() {
        // The normal case: most of the window was already stored last poll.
        assertFalse(MarketWatcher.suspectsHistoryGap(report(200, 12, 188)));
    }

    @Test
    void zeroOverlapOnANonEmptyPageIsAGap() {
        // Every row new means the window no longer reaches the previous poll.
        assertTrue(MarketWatcher.suspectsHistoryGap(report(200, 200, 0)));
    }

    @Test
    void anEmptyPageIsNotAGap() {
        // A quiet market returning nothing has lost nothing.
        assertFalse(MarketWatcher.suspectsHistoryGap(report(0, 0, 0)));
    }

    @Test
    void windowDoublesWhenAGapIsSuspected() {
        assertEquals(4, MarketWatcher.nextHistoryPages(2, report(200, 200, 0)));
        assertEquals(8, MarketWatcher.nextHistoryPages(4, report(400, 400, 0)));
    }

    @Test
    void wideningStopsAtTheApiPageCeiling() {
        // Page 11 answers HTTP 500; the ceiling is the API's, not a budget.
        assertEquals(10, MarketWatcher.nextHistoryPages(8, report(800, 800, 0)));
        assertEquals(10, MarketWatcher.nextHistoryPages(10, report(1000, 1000, 0)));
    }

    @Test
    void theProxyLiftsThePageCeiling() {
        // The community proxy serves the whole history table, so a high-volume
        // market can walk past ten to regain overlap. Donut still stops at ten.
        assertEquals(20, MarketWatcher.nextHistoryPages(10, report(1000, 1000, 0), 30));
        assertEquals(10, MarketWatcher.nextHistoryPages(8, report(800, 800, 0), 10));
    }

    @Test
    void theProxyIsAnythingButDonut() {
        assertTrue(MarketWatcher.usingProxy("http://192.0.2.10:8787"));
        assertTrue(MarketWatcher.usingProxy("https://data.example"));
        assertFalse(MarketWatcher.usingProxy("https://api.donutsmp.net"));
    }

    @Test
    void windowEasesBackOnePageAtATime() {
        // Narrowing slowly: one quiet cycle must not undo a needed widening.
        assertEquals(7, MarketWatcher.nextHistoryPages(8, report(800, 3, 797)));
        assertEquals(6, MarketWatcher.nextHistoryPages(7, report(700, 3, 697)));
    }

    @Test
    void windowNeverNarrowsBelowTheSteadyState() {
        assertEquals(2, MarketWatcher.nextHistoryPages(2, report(200, 5, 195)));
    }

    @Test
    void aBusyMarketWidensUntilItRegainsOverlap() {
        // The live shape of the bug: ~97 new sales per 30s against a 200-row
        // window leaves little headroom, and a burst wipes the overlap out.
        int pages = 2;
        pages = MarketWatcher.nextHistoryPages(pages, report(200, 200, 0));
        pages = MarketWatcher.nextHistoryPages(pages, report(400, 400, 0));
        assertEquals(8, pages);

        // Overlap returns once the window outruns the sale rate.
        pages = MarketWatcher.nextHistoryPages(pages, report(800, 250, 550));
        assertEquals(7, pages);
    }
}
