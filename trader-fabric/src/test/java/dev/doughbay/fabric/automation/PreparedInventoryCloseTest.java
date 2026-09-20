package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PreparedInventoryCloseTest {
    @Test
    void failedPreparationMustCloseAndSettleBeforeScanningOrRetrying() {
        var preparation = new PreparedInventoryClose();
        var player = new Object();
        var closes = new AtomicInteger();
        preparation.interactionStarted(player);
        assertTrue(preparation.cleanupPending());
        assertFalse(preparation.finishPending(player, true, 1_000, closes::incrementAndGet));
        assertFalse(preparation.finishPending(player, true, 1_599, closes::incrementAndGet));
        assertTrue(preparation.cleanupPending());
        assertTrue(preparation.finishPending(player, true, 1_600, closes::incrementAndGet));
        assertFalse(preparation.cleanupPending());
        assertEquals(1, closes.get());
        assertTrue(preparation.ready(1_600, closes::incrementAndGet));
        assertEquals(1, closes.get(), "successful verification must not close the same swap twice");
    }

    @Test
    void cleanupCannotCloseAForeignMenuOrDiscardACarriedStack() {
        var preparation = new PreparedInventoryClose();
        var player = new Object();
        var closes = new AtomicInteger();
        preparation.interactionStarted(player);
        assertFalse(preparation.finishPending(player, false, 1_000, closes::incrementAndGet));
        assertFalse(preparation.finishPending(player, false, 90_000, closes::incrementAndGet));
        assertEquals(0, closes.get());
        assertFalse(preparation.finishPending(player, true, 91_000, closes::incrementAndGet));
        assertTrue(preparation.finishPending(player, true, 91_600, closes::incrementAndGet));
        assertEquals(1, closes.get());
    }

    @Test
    void failedCleanupRemainsPendingButCannotAffectANewPlayerConnection() {
        var preparation = new PreparedInventoryClose();
        var player = new Object();
        preparation.interactionStarted(player);
        assertThrows(IllegalStateException.class, () -> preparation.finishPending(player, true, 1_000,
                () -> { throw new IllegalStateException("connection lost"); }));
        assertTrue(preparation.cleanupPending());
        assertTrue(preparation.finishPending(new Object(), true, 2_000,
                () -> fail("old cleanup must not close a new player's menu")));
        assertFalse(preparation.cleanupPending());
    }

    @Test
    void listingWaitsForOneInventoryCloseAndASettlingPeriod() {
        var preparation = new PreparedInventoryClose();
        var closes = new AtomicInteger();
        assertFalse(preparation.ready(1_000, closes::incrementAndGet));
        assertEquals(1, closes.get());
        assertFalse(preparation.ready(1_599, closes::incrementAndGet));
        assertTrue(preparation.ready(1_600, closes::incrementAndGet));
        assertTrue(preparation.ready(1_700, closes::incrementAndGet));
        assertEquals(1, closes.get(), "waiting must not repeatedly send close packets");
    }

    @Test
    void anotherStackMoveRequiresANewCloseBeforeListing() {
        var preparation = new PreparedInventoryClose();
        var closes = new AtomicInteger();
        preparation.ready(1_000, closes::incrementAndGet);
        assertTrue(preparation.ready(2_000, closes::incrementAndGet));
        preparation.reset();
        assertFalse(preparation.ready(3_000, closes::incrementAndGet));
        assertEquals(2, closes.get());
        assertFalse(preparation.ready(3_599, closes::incrementAndGet));
        assertTrue(preparation.ready(3_600, closes::incrementAndGet));
    }

    @Test
    void failedCloseDoesNotUnlockListing() {
        var preparation = new PreparedInventoryClose();
        assertThrows(IllegalStateException.class,
                () -> preparation.ready(1_000, () -> { throw new IllegalStateException("connection lost"); }));
        var closes = new AtomicInteger();
        assertFalse(preparation.ready(10_000, closes::incrementAndGet));
        assertEquals(1, closes.get());
        assertTrue(preparation.ready(10_600, closes::incrementAndGet));
    }
}
