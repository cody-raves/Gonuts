package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which broad-feed statuses a session may treat as live evidence.
 *
 * <p>With dozens of markets scanned per sweep, one unproven book is routine.
 * The watcher excludes such books from the snapshot, so a PARTIAL feed is
 * still sound for everything it contains. Treating it as unhealthy stalled a
 * live session on nearly every sweep.
 */
class AutomationSessionControllerFeedStatusTest {

    @Test
    void cleanSweepsAreHealthy() {
        assertTrue(AutomationSessionController.healthyFeedStatus("OK"));
        assertTrue(AutomationSessionController.healthyFeedStatus(
                "No opportunities passed the filters"));
    }

    @Test
    void aPartialSweepIsHealthyForTheBooksItProved() {
        assertTrue(AutomationSessionController.healthyFeedStatus(
                "PARTIAL: 53 of 55 active book(s) proven"));
        assertTrue(AutomationSessionController.healthyFeedStatus(
                "PARTIAL: 12 of 55 active book(s) proven; no opportunities passed the filters"));
    }

    @Test
    void degradedStoppedAndMissingFeedsAreNot() {
        assertFalse(AutomationSessionController.healthyFeedStatus(
                "DEGRADED: completed-sale validation failed; valuations locked"));
        assertFalse(AutomationSessionController.healthyFeedStatus("STOPPED"));
        assertFalse(AutomationSessionController.healthyFeedStatus("Not started"));
        assertFalse(AutomationSessionController.healthyFeedStatus(null));
        // Only the exact prefix counts; a status merely mentioning it does not.
        assertFalse(AutomationSessionController.healthyFeedStatus("STOPPED after PARTIAL: 1 of 2"));
    }
}
