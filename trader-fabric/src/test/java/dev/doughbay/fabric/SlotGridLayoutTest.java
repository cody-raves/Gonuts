package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlotGridLayoutTest {
    @Test void allNinetySlotsFillTheHudWidthWithoutUnevenGaps() {
        var grid = SlotGridLayout.fit(264, 90);
        assertEquals(0, grid.left(0));
        assertEquals(264, grid.right(17));
        assertEquals(5, grid.rows());
        assertTrue(grid.cellHeight() >= 12);
        for (int i = 0; i < 90; i++) {
            assertTrue(grid.left(i) >= 0 && grid.right(i) <= 264);
            assertTrue(grid.top(i) + grid.cellHeight() <= grid.height());
            if (i % grid.columns() != grid.columns() - 1)
                assertEquals(grid.gap(), grid.left(i + 1) - grid.right(i));
        }
    }

    @Test void fortyFiveSlotsAndPartialLastRowsStayWithinBounds() {
        for (int slots : new int[]{1, 30, 45, 46, 90}) {
            var grid = SlotGridLayout.fit(224, slots);
            assertEquals(slots, grid.slots());
            assertEquals(grid.height(), grid.top(slots - 1) + grid.cellHeight());
            assertEquals(224, grid.right(grid.columns() - 1));
        }
    }
}
