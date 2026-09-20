package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PreparedInventoryCloseTest {
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
