package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderFillTrackerTest {

    private static OrderFillTracker.Row row(String item, long price, int total, int delivered) {
        return new OrderFillTracker.Row(item, price, total, delivered);
    }

    @Test
    void aRisingDeliveredCountIsAFill() {
        OrderFillTracker t = new OrderFillTracker();
        t.read(List.of(row("minecraft:map", 3200, 640, 100)), 1_000, true);
        List<OrderFillTracker.Fill> fills = t.read(List.of(row("minecraft:map", 3200, 640, 228)), 2_000, true);
        assertEquals(1, fills.size());
        assertEquals(128, fills.get(0).units());
        assertEquals(3200, fills.get(0).unitPrice());
        assertEquals(2_000, fills.get(0).at());
    }

    @Test
    void aFirstSightingOrWarmUpReadIsNeverAFill() {
        OrderFillTracker t = new OrderFillTracker();
        assertTrue(t.read(List.of(row("minecraft:map", 3200, 640, 300)), 1_000, true).isEmpty());
        assertTrue(t.read(List.of(row("minecraft:tnt", 2000, 64, 10)), 1_000, false).isEmpty());
        assertTrue(t.read(List.of(row("minecraft:tnt", 2000, 64, 50)), 2_000, false).isEmpty());
    }

    @Test
    void aPartialReadThatSawOnlyOneOfAPairIsNotAFill() {
        OrderFillTracker t = new OrderFillTracker();
        t.read(List.of(row("minecraft:hopper", 1100, 192, 0), row("minecraft:hopper", 1100, 192, 150)), 1_000, true);
        // Only the full one was seen this time: counts differ, so no comparison.
        assertTrue(t.read(List.of(row("minecraft:hopper", 1100, 192, 150)), 2_000, true).isEmpty());
        // Both again, one of them has moved: summed and compared.
        List<OrderFillTracker.Fill> fills = t.read(
                List.of(row("minecraft:hopper", 1100, 192, 64), row("minecraft:hopper", 1100, 192, 150)), 3_000, true);
        assertTrue(fills.isEmpty(), "the pair was last seen as one order, so this read only re-learns it");
        fills = t.read(List.of(row("minecraft:hopper", 1100, 192, 128), row("minecraft:hopper", 1100, 192, 150)), 4_000, true);
        assertEquals(64, fills.get(0).units());
    }

    @Test
    void ordersMissingFromAReadKeepTheirLastState() {
        OrderFillTracker t = new OrderFillTracker();
        t.read(List.of(row("minecraft:map", 3200, 640, 100)), 1_000, true);
        assertTrue(t.read(List.of(), 2_000, true).isEmpty());
        assertEquals(40, t.read(List.of(row("minecraft:map", 3200, 640, 140)), 3_000, true).get(0).units());
    }

    @Test
    void boxesAndNonsenseRowsAreIgnored() {
        OrderFillTracker t = new OrderFillTracker();
        t.read(List.of(row("minecraft:shulker_box#ab12", 9000, 1, 0), row("minecraft:map", 0, 64, 0)), 1_000, true);
        assertTrue(t.read(List.of(row("minecraft:shulker_box#ab12", 9000, 1, 1), row("minecraft:map", 0, 64, 64)),
                2_000, true).isEmpty());
    }

    @Test
    void aSightingFromLongAgoIsRelearnedNotCredited() {
        OrderFillTracker t = new OrderFillTracker();
        t.read(List.of(row("minecraft:map", 3200, 640, 100)), 0, true);
        long later = OrderFillTracker.MAX_GAP_MILLIS + 1;
        assertTrue(t.read(List.of(row("minecraft:map", 3200, 640, 600)), later, true).isEmpty());
        assertEquals(20, t.read(List.of(row("minecraft:map", 3200, 640, 620)), later + 60_000, true).get(0).units());
    }
}
