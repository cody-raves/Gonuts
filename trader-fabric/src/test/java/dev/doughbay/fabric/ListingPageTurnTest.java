package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.doughbay.fabric.ListingPageTurn.Result.*;

class ListingPageTurnTest {
    @Test
    void unchangedPageIsNeverCountedAsAnotherPageEvenAfterRepeatedReads() {
        var turn = new ListingPageTurn();
        turn.requested("menu1|page1|rows", 1_000);
        for (long now = 1_050; now < 9_000; now += 50) {
            assertEquals(WAITING, turn.observe("menu1|page1|rows", now, true));
        }
        assertEquals(EXPIRED, turn.observe("menu1|page1|rows", 9_000, true));
        assertTrue(turn.pending(), "a failed page turn must not unlock reconciliation");
    }

    @Test
    void observedPageArrivalIsConsumedOnlyOnce() {
        var turn = new ListingPageTurn();
        turn.requested("menu1|page1|rows", 1_000);
        assertEquals(WAITING, turn.observe("menu1|page1|rows", 1_050, true));
        assertEquals(ARRIVED, turn.observe("menu2|page2|otherRows", 1_500, true));
        assertFalse(turn.pending());
        assertEquals(NONE, turn.observe("menu2|page2|otherRows", 1_550, true));
    }

    @Test
    void anEmptyLoadingPageIsNotEvidenceOfAnEmptyBook() {
        var turn = new ListingPageTurn();
        turn.requested("menu1|page1|rows", 1_000);
        assertEquals(WAITING, turn.observe("menu2|page2|empty", 1_500, false));
        assertEquals(EXPIRED, turn.observe("menu2|page2|empty", 9_000, false));
        assertEquals(ARRIVED, turn.observe("menu2|page2|rows", 9_050, true));
    }

    @Test
    void terminalCleanupCannotCarryAPendingPageIntoTheNextOperation() throws Exception {
        var driver = new AutomatedExecutionDriver();
        for (String name : new String[] {"cancelPageTurn", "auditPageTurn"}) {
            var f = AutomatedExecutionDriver.class.getDeclaredField(name);
            f.setAccessible(true);
            ((ListingPageTurn) f.get(driver)).requested("old-page", 1_000);
        }
        var cleanup = AutomatedExecutionDriver.class.getDeclaredMethod("clearTarget");
        cleanup.setAccessible(true);
        cleanup.invoke(driver);
        for (String name : new String[] {"cancelPageTurn", "auditPageTurn"}) {
            var f = AutomatedExecutionDriver.class.getDeclaredField(name);
            f.setAccessible(true);
            assertFalse(((ListingPageTurn) f.get(driver)).pending());
        }
    }
}
