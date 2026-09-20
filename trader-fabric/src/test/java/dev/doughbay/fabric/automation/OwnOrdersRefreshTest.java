package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnOrdersRefreshTest {
    @Test void firstReadDoesNotNeedAFillOrPublicSweep() {
        assertTrue(AutomationSessionController.ownOrdersRefreshDue(1_000, 0, 0));
    }

    @Test void idleDeskReconsidersPricesAfterNinetySeconds() {
        assertFalse(AutomationSessionController.ownOrdersRefreshDue(90_999, 1_000, 500));
        assertTrue(AutomationSessionController.ownOrdersRefreshDue(91_000, 1_000, 500));
    }

    @Test void failedOrRefusedReadCannotCauseARetryEveryTick() {
        assertFalse(AutomationSessionController.ownOrdersRefreshDue(100_001, 0, 100_000));
        assertFalse(AutomationSessionController.ownOrdersRefreshDue(189_999, 1_000, 100_000));
        assertTrue(AutomationSessionController.ownOrdersRefreshDue(190_000, 1_000, 100_000));
    }

    @Test void cooldownStartsFromCompletionWhenAReadWasSlow() {
        assertFalse(AutomationSessionController.ownOrdersRefreshDue(195_000, 120_000, 100_000));
        assertTrue(AutomationSessionController.ownOrdersRefreshDue(210_000, 120_000, 100_000));
        assertFalse(AutomationSessionController.ownOrdersRefreshDue(90_000, 120_000, 100_000));
    }
}
