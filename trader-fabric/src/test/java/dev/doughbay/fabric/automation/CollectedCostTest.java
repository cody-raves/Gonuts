package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollectedCostTest {
    @Test void lateOneItemNoticeCannotMakeThirtyEightItemsFree() {
        long cost = CollectedCost.resize(9555, 1, 39, 9555);
        assertEquals(372645, cost);
        assertEquals(-21645, 351000 - cost, "friend log's delivery was not a 341K profit");
        assertTrue(cost * 3 > 457506, "a partial notice must not collapse the listing cap");
    }
    @Test void twentyThreeNoticesStillPriceAllSixtyFourUnits() {
        long cost = CollectedCost.resize(219765, 23, 64, 9555);
        assertEquals(611520, cost);
        assertEquals(47775, 659295 - cost);
    }
    @Test void collectedBatchCostScalesDownToThePhysicalStack() {
        assertEquals(608000, CollectedCost.resize(2194500, 231, 64, 9500));
        assertEquals(370500, CollectedCost.resize(2194500, 231, 39, 9500));
        assertEquals(640000, CollectedCost.resize(10000, 1, 64, 9500), "preserve higher known costs");
    }
    @Test void invalidCostsCannotBecomeFreeStock() {
        assertThrows(IllegalArgumentException.class, () -> CollectedCost.fullStack(39, 0));
        assertThrows(ArithmeticException.class, () -> CollectedCost.fullStack(64, Long.MAX_VALUE));
    }
}
