package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderDeskRecoveryTest {
    @Test void aFailedReadKeepsPriorityButDoesNotRetryEveryTick() {
        var recovery = new OrderDeskRecovery();
        assertFalse(recovery.pending());
        recovery.failed(1000);
        assertTrue(recovery.pending());
        assertFalse(recovery.ready(5999));
        assertTrue(recovery.ready(6000));
        assertEquals(1, recovery.remainingSeconds(5999));
    }

    @Test void repeatedFailuresBackOffToOneAttemptPerMinute() {
        var recovery = new OrderDeskRecovery();
        recovery.failed(1000);
        recovery.failed(6000);
        assertFalse(recovery.ready(20999));
        assertTrue(recovery.ready(21000));
        for (long now = 21000; now < 300000; now += 60000) {
            recovery.failed(now);
            assertFalse(recovery.ready(now + 59999));
            assertTrue(recovery.ready(now + 60000));
        }
    }

    @Test void aSuccessfulReadReleasesTheWatchAndResetsBackoff() {
        var recovery = new OrderDeskRecovery();
        recovery.failed(1000);
        recovery.failed(6000);
        recovery.recovered();
        assertFalse(recovery.pending());
        assertFalse(recovery.ready(100000));
        recovery.failed(100000);
        assertTrue(recovery.ready(105000));
    }

    @Test void navigationFailuresDoNotUseUpItemCollectionAttempts() {
        for (String detail : new String[]{
                "ABORTED: No progress at stage 0 of OWN_ORDERS on orders (page 1)",
                "ABORTED: Your Orders did not open (page: orders (page 1))",
                "No phase progress; orderStage=1, ticks=500"}) {
            assertTrue(OrderDeskRecovery.navigationFailure(detail));
        }
        assertFalse(OrderDeskRecovery.navigationFailure("Nothing arrived from the order"));
        assertFalse(OrderDeskRecovery.navigationFailure("The order was not on Your Orders"));
        assertFalse(OrderDeskRecovery.navigationFailure("No progress at stage 3 of COLLECT_ORDER"));
        assertFalse(OrderDeskRecovery.navigationFailure(null));
    }
}
