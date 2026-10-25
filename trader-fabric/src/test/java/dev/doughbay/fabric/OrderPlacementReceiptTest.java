package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderPlacementReceiptTest {
    @Test void exactAcceptedAmountIsNotThePlannedAmountOrRoundedLabel() {
        assertEquals(1024,AutomatedExecutionDriver.placementReceiptQuantity("You ordered 1,024 End Crystals","End Crystal").orElseThrow());
        assertEquals(192,AutomatedExecutionDriver.placementReceiptQuantity("You ordered 192 End Crystal","End Crystal").orElseThrow());
        assertEquals(1024,AutomatedExecutionDriver.placementReceiptQuantity("You ordered 1,024 Blocks of Diamond","Block of Diamond").orElseThrow());
        assertEquals(192,AutomatedExecutionDriver.placementReceiptQuantity("You ordered 192 Bottles o' Enchanting","Bottle o' Enchanting").orElseThrow());
        assertTrue(AutomatedExecutionDriver.placementReceiptQuantity("You ordered 1K End Crystals","End Crystal").isEmpty());
        assertTrue(AutomatedExecutionDriver.placementReceiptQuantity("You ordered 0 End Crystal","End Crystal").isEmpty());
    }
    @Test void unrelatedItemOrChatCannotConfirmOurPlacement() {
        assertTrue(AutomatedExecutionDriver.placementReceiptQuantity("You ordered 1024 Obsidian","End Crystal").isEmpty());
        assertTrue(AutomatedExecutionDriver.placementReceiptQuantity("player: You ordered 1024 End Crystal","End Crystal").isEmpty());
        assertTrue(AutomatedExecutionDriver.placementReceiptQuantity("You ordered 1,02,4 End Crystal","End Crystal").isEmpty());
    }
}
