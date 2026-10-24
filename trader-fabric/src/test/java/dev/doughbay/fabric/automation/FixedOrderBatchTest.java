package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedOrderBatchTest {
    @Test void sixteenStacksMeans1024AndNeverThreeStacksOrFifteen() {
        assertEquals(new FixedOrderBatch.Decision(1024, FixedOrderBatch.Gate.READY),
                FixedOrderBatch.plan(16,64,64,100,true,16,100,1_000_000,1024));
        // The unproven native recognition path must remain closed, rather than truncate to 960.
        assertEquals(new FixedOrderBatch.Decision(0, FixedOrderBatch.Gate.QUANTITY_UNVERIFIED),
                FixedOrderBatch.plan(16,64,64,100,true,16,100,1_000_000,999));
    }
    @Test void waitsInsteadOfShrinkingForDepthListingRoomOrMoney() {
        assertEquals(FixedOrderBatch.Gate.DEPTH, FixedOrderBatch.plan(16,64,64,3,true,16,100,1_000_000,1024).gate());
        assertEquals(FixedOrderBatch.Gate.LISTING_ROOM, FixedOrderBatch.plan(16,64,64,100,true,3,100,1_000_000,1024).gate());
        assertEquals(FixedOrderBatch.Gate.BUDGET, FixedOrderBatch.plan(16,64,64,100,true,16,100,102_399,1024).gate());
        assertEquals(FixedOrderBatch.Gate.DEPTH_UNKNOWN, FixedOrderBatch.plan(16,64,64,0,true,16,100,1_000_000,1024).gate());
    }
    @Test void preservesRealStackSizesAndDoesNotSwitchToSingles() {
        assertEquals(256, FixedOrderBatch.plan(16,16,16,100,true,16,100,1_000_000,999).units());
        assertEquals(FixedOrderBatch.Gate.NOT_WHOLE_STACKS, FixedOrderBatch.plan(16,64,1,100,true,16,100,1_000_000,1024).gate());
        assertEquals(960, FixedOrderBatch.plan(15,64,64,15,true,15,100,96_000,999).units());
    }
    @Test void rejectsOverflowInvalidInputAndUnknownCapacity() {
        assertEquals(FixedOrderBatch.Gate.INVALID, FixedOrderBatch.plan(Integer.MAX_VALUE,64,64,100,true,16,100,1_000_000,1024).gate());
        assertEquals(FixedOrderBatch.Gate.BUDGET, FixedOrderBatch.plan(16,64,64,100,true,16,Long.MAX_VALUE,Long.MAX_VALUE,1024).gate());
        assertEquals(FixedOrderBatch.Gate.LISTING_ROOM, FixedOrderBatch.plan(16,64,64,100,true,-1,100,1_000_000,1024).gate());
    }
    @Test void threeDistinctFullBatchesShareDepthAndAFourthWaits() {
        int units = FixedOrderBatch.plan(16,64,64,100,true,16,100,1_000_000,1024).units();
        assertEquals(1024, units);
        var two = new ReserveStock.Book(2, 2L * units, true);
        assertTrue(OrderBatchLimit.hasRoom(two,3));
        assertTrue(OrderBatchLimit.fitsDepth(two,0,units,3L*units));
        assertFalse(OrderBatchLimit.fitsDepth(two,64,units,3L*units));
        assertFalse(OrderBatchLimit.hasRoom(new ReserveStock.Book(3,3L*units,true),3));
    }
}
