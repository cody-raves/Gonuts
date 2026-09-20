package dev.doughbay.storage;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetailSplitTest {
    private static Position lot(int quantity, long cost) {
        return new Position(1, "REAL", "minecraft:diamond", StackBucket.of(quantity), quantity,
                cost, 0, 1_000, 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
    }

    @Test
    void aSingleComesOffAtItsShareOfTheCost() {
        RetailSplit split = RetailSplit.of(lot(533, 2_611_700), 2, 3, 15_000);
        assertEquals(1, split.single().quantity());
        assertEquals(4_900, split.single().purchasePrice());
        assertEquals(532, split.remainder().quantity());
        assertEquals(2_611_700 - 4_900, split.remainder().purchasePrice());
    }

    @Test
    void aWholeStackComesOffAtItsShareAndNothingIsLostOrInvented() {
        Position parent = lot(533, 2_611_700);
        RetailSplit split = RetailSplit.of(parent, 2, 3, 15_000, 64);
        assertEquals(64, split.single().quantity());
        assertEquals(64 * 4_900L, split.single().purchasePrice());
        assertEquals(469, split.remainder().quantity());
        assertEquals(parent.quantity(), split.single().quantity() + split.remainder().quantity());
        assertEquals(parent.purchasePrice(), split.single().purchasePrice() + split.remainder().purchasePrice());
    }

    @Test
    void theRoundingStaysWithTheRemainder() {
        // 1,000 over 3 is 333 each; the odd one stays in the lot rather than vanishing.
        RetailSplit split = RetailSplit.of(lot(3, 1_000), 2, 3, 500, 2);
        assertEquals(666, split.single().purchasePrice());
        assertEquals(334, split.remainder().purchasePrice());
    }

    @Test
    void aSplitMustLeaveSomethingBehind() {
        assertThrows(IllegalArgumentException.class, () -> RetailSplit.of(lot(64, 64_000), 2, 3, 2_000, 64));
        assertThrows(IllegalArgumentException.class, () -> RetailSplit.of(lot(64, 64_000), 2, 3, 2_000, 0));
    }
}
