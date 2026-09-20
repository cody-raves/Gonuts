package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderFailureClassificationTest {
    @Test
    void transportAndNavigationFailuresDoNotPermanentlyExcludeAnOrderableItem() {
        for (String detail : new String[] {null,
                "ABORTED: No progress at stage 2 of PLACE_ORDER on orders -> your orders",
                "ABORTED: No phase progress for 45s; operation=PLACE_ORDER",
                "ABORTED: Command /orders was not dispatched within 45 seconds",
                "ABORTED: Your Orders did not open"}) {
            assertFalse(AutomationSessionController.unsupportedOrderItem(detail));
        }
        assertTrue(AutomationSessionController.unsupportedOrderItem("ABORTED: No item button matches Unsupported Item"));
    }
}
