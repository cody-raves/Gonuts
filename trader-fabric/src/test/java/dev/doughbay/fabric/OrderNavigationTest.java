package dev.doughbay.fabric;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderNavigationTest {
    @Test
    void deferredClicksKeepEveryOrderStepOnItsCurrentPage() throws Exception {
        var driver = new AutomatedExecutionDriver();
        // Your Orders, New Order, Edit Order, Collect, Cancel, Confirm Cancel.
        for (int[] transition : new int[][] {{0, 1}, {1, 2}, {1, 3}, {3, 5}, {3, 4}, {4, 1}}) {
            field("ownStage").setInt(driver, transition[0]);
            for (int tick = 0; tick < 20; tick++) {
                assertFalse(driver.advanceOwnOrderStep(false, transition[1]));
                assertEquals(transition[0], field("ownStage").getInt(driver),
                        "waiting for a click must not become waiting for an unopened page");
            }
            assertTrue(driver.advanceOwnOrderStep(true, transition[1]));
            assertEquals(transition[1], field("ownStage").getInt(driver));
        }
    }

    @Test
    void lateOrdersPageRestartsAuctionNavigationWithoutResettingItsRetryBudget() throws Exception {
        var driver = new AutomatedExecutionDriver();
        field("cancelStage").setInt(driver, 1);
        field("wrongPageReopens").setInt(driver, 2);
        assertTrue(driver.resetCancelNavigationOnWrongPage("Orders (Page 1)"));
        assertEquals(0, field("cancelStage").getInt(driver));
        assertEquals(2, field("wrongPageReopens").getInt(driver));
        field("cancelStage").setInt(driver, 1);
        assertFalse(driver.resetCancelNavigationOnWrongPage("Auction (Page 1)"));
        assertFalse(driver.resetCancelNavigationOnWrongPage("Auction -> Your Items"));
        assertEquals(1, field("cancelStage").getInt(driver));
    }

    @Test
    void capturedYourItemsControlWinsOverAnEarlierUnnamedOrdersChest() {
        assertEquals(51, AutomatedExecutionDriver.selectOwnItemsControl(List.of(
                control(46, "", "Orders", "Click to view your orders"),
                control(48, "Quick Buy", "Click to view"),
                control(51, "Your Items", "Click to view"))));
    }

    @Test
    void loreCanIdentifyAnUnnamedOwnListingsControl() {
        assertEquals(51, AutomatedExecutionDriver.selectOwnItemsControl(List.of(
                control(46, "", "Orders"), control(51, "", "Your Items", "Click to view"))));
    }

    @Test
    void emptyOrAmbiguousControlsNeverFallBackToAnArbitraryChest() {
        assertEquals(-1, AutomatedExecutionDriver.selectOwnItemsControl(List.of(
                control(46, "", "Orders"), control(51, "", "Click to view"))));
        assertEquals(-1, AutomatedExecutionDriver.selectOwnItemsControl(List.of(
                control(46, "Your Items", "Orders"))));
        assertEquals(-1, AutomatedExecutionDriver.selectOwnItemsControl(List.of(
                control(46, "Your Items"), control(51, "Your Listings"))));
        assertEquals(-1, AutomatedExecutionDriver.selectOwnItemsControl(List.of(
                control(46, "New Listing"), control(51, "Recently Listed"))));
    }

    private static AutomatedExecutionDriver.OwnItemsControl control(int slot, String name, String... lore) {
        return new AutomatedExecutionDriver.OwnItemsControl(slot, name, List.of(lore));
    }

    private static Field field(String name) throws Exception {
        Field field = AutomatedExecutionDriver.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
