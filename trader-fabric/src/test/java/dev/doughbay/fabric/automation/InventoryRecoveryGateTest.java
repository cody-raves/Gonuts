package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryRecoveryGateTest {
    @Test void aLateAuctionMenuAndCursorCorrectionCannotUnlockTrading() {
        var gate = new InventoryRecoveryGate();
        Object player = new Object(), inventory = new Object(), auction = new Object();
        assertFalse(gate.hold(player, inventory, false, true, 1000));
        assertTrue(gate.hold(player, inventory, true, true, 1100));
        assertTrue(gate.hold(player, inventory, false, true, 1200));
        assertTrue(gate.hold(player, auction, false, false, 1500));
        assertTrue(gate.hold(player, auction, true, false, 2000));
        assertTrue(gate.hold(player, inventory, false, true, 2100));
        assertTrue(gate.hold(player, inventory, false, true, 2200));
        assertTrue(gate.hold(player, inventory, false, true, 3199));
        assertFalse(gate.hold(player, inventory, false, true, 3200));
        assertFalse(gate.pending());
    }
    @Test void interruptedSplitWaitsEvenWhenTheNewMenuHasAnEmptyCursor() {
        var gate = new InventoryRecoveryGate();
        Object player = new Object(), menu = new Object();
        gate.begin();
        assertTrue(gate.hold(player, menu, false, true, 1000));
        assertTrue(gate.hold(player, menu, false, true, 1999));
        assertFalse(gate.hold(player, menu, false, true, 2000));
    }
    @Test void correctionRestartsTheFullSettlingInterval() {
        var gate = new InventoryRecoveryGate();
        Object player = new Object(), menu = new Object();
        gate.begin();
        gate.hold(player, menu, false, true, 1000);
        assertTrue(gate.hold(player, menu, true, true, 1999));
        assertTrue(gate.hold(player, menu, false, true, 2000));
        assertTrue(gate.hold(player, menu, false, true, 2999));
        assertFalse(gate.hold(player, menu, false, true, 3000));
    }
    @Test void splitClicksRequireEveryExpectedQuantity() {
        assertTrue(PeelInventoryState.matches(0, 58, 58, 0, 0));
        assertTrue(PeelInventoryState.matches(1, 58, 0, 0, 58));
        assertTrue(PeelInventoryState.matches(2, 58, 0, 1, 57));
        assertFalse(PeelInventoryState.matches(1, 58, 58, 0, 0), "pickup never applied");
        assertFalse(PeelInventoryState.matches(2, 58, 0, 0, 58), "single never landed");
        assertFalse(PeelInventoryState.matches(2, 58, -1, 1, 57), "another item occupies source");
        assertFalse(PeelInventoryState.matches(1, 58, 0, 1, 58), "destination became occupied");
    }
}
