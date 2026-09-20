package dev.doughbay.fabric;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationProgressTest {
    @Test
    void playerTickResetCannotDeferClicksUntilThePreviousWorldCatchesUp() throws Exception {
        var driver = new AutomatedExecutionDriver();
        set(driver, "lastClickTick", 100000);
        set(driver, "clickGapTicks", 8);
        assertTrue(driver.resetClickClockIfRewound(20));
        assertEquals(-1, field("lastClickTick").getInt(driver));
        assertEquals(0, field("clickGapTicks").getInt(driver));
        set(driver, "lastClickTick", 20);
        set(driver, "clickGapTicks", 8);
        assertFalse(driver.resetClickClockIfRewound(20));
        assertFalse(driver.resetClickClockIfRewound(21));
        assertEquals(20, field("lastClickTick").getInt(driver));
        assertEquals(8, field("clickGapTicks").getInt(driver));
    }

    @Test
    void onlyPublicOrderNavigationMayReopenAndOnlyOnce() throws Exception {
        var driver = new AutomatedExecutionDriver();
        for (String op : List.of("OWN_ORDERS", "PLACE_ORDER", "COLLECT_ORDER", "CANCEL_ORDER")) {
            operation(driver, op);
            set(driver, "ownStage", 0);
            set(driver, "ticksInPhase", 319);
            assertFalse(driver.shouldReopenOwnOrdersNavigation("Orders (Page 1)"));
            set(driver, "ticksInPhase", 320);
            assertTrue(driver.shouldReopenOwnOrdersNavigation("Orders (Page 1)"));
            set(driver, "ownStage", 1);
            assertFalse(driver.shouldReopenOwnOrdersNavigation("Orders (Page 1)"));
            set(driver, "ticksInPhase", 1800);
            assertTrue(driver.shouldReopenOwnOrdersNavigation("Orders (Page 1)"));
            assertFalse(driver.shouldReopenOwnOrdersNavigation("Orders -> Your Orders"));
            assertFalse(driver.shouldReopenOwnOrdersNavigation("Orders -> Edit Order"));
            set(driver, "ownNavigationReopens", 1);
            assertFalse(driver.shouldReopenOwnOrdersNavigation("Orders (Page 1)"));
            set(driver, "ownNavigationReopens", 0);
        }
        set(driver, "ownStage", 3);
        assertFalse(driver.shouldReopenOwnOrdersNavigation("Orders (Page 1)"));
        set(driver, "ownStage", 1);
        set(driver, "placeConfirmed", true);
        assertFalse(driver.shouldReopenOwnOrdersNavigation("Orders (Page 1)"));
        set(driver, "placeConfirmed", false);
        operation(driver, "BUY");
        assertFalse(driver.shouldReopenOwnOrdersNavigation("Orders (Page 1)"));
    }

    @Test
    void navigationUsesTheStableButtonWhileTradePagesStillRequireTheWholeGrid() throws Exception {
        var driver = new AutomatedExecutionDriver();
        operation(driver, "CANCEL");
        assertTrue(driver.navigationOnly("Auction (Page 1)"));
        String button = AutomatedExecutionDriver.navigationSignature(7, "Auction (Page 1)",
                51, "minecraft:chest", 1, List.of("Your Items", "Click to view"));
        assertFalse(driver.observeStableSignature(button));
        assertTrue(driver.observeStableSignature(button));
        assertFalse(driver.navigationOnly("Auction -> Your Items"), "listing identity still needs the full page");
        for (String operation : List.of("BUY", "LIST", "DRY_RUN")) {
            operation(driver, operation);
            assertFalse(driver.navigationOnly("Auction (Page 1)"));
        }
    }

    @Test
    void navigationMustReverifyAMovedRenamedOrReplacedControl() {
        var driver = new AutomatedExecutionDriver();
        String first = AutomatedExecutionDriver.navigationSignature(7, "Auction", 51,
                "minecraft:chest", 1, List.of("Your Items"));
        driver.observeStableSignature(first);
        assertTrue(driver.observeStableSignature(first));
        for (String changed : List.of(
                AutomatedExecutionDriver.navigationSignature(8, "Auction", 51, "minecraft:chest", 1, List.of("Your Items")),
                AutomatedExecutionDriver.navigationSignature(8, "Auction", 50, "minecraft:chest", 1, List.of("Your Items")),
                AutomatedExecutionDriver.navigationSignature(8, "Auction", 50, "minecraft:chest", 1, List.of("Orders")))) {
            assertFalse(driver.observeStableSignature(changed));
            assertTrue(driver.observeStableSignature(changed));
        }
    }

    @Test
    void ownOrdersNavigationDoesNotWaitForPublicOrderRowsToStopChanging() throws Exception {
        var driver = new AutomatedExecutionDriver();
        for (String op : List.of("OWN_ORDERS", "PLACE_ORDER", "COLLECT_ORDER", "CANCEL_ORDER")) {
            operation(driver, op);
            set(driver, "ownStage", 0);
            assertTrue(driver.navigationOnly("Orders (Page 1)"));
            assertFalse(driver.navigationOnly("Orders -> Your Orders"));
            assertFalse(driver.navigationOnly("Orders -> Edit Order"));
            set(driver, "ownStage", 3);
            assertFalse(driver.navigationOnly("Orders -> Collect Items"));
        }
    }

    @Test
    void onlyTheUnconfirmedNewOrderNavigationGetsOneRetry() throws Exception {
        var driver = new AutomatedExecutionDriver();
        operation(driver, "PLACE_ORDER");
        set(driver, "ownStage", 2);
        set(driver, "ticksInPhase", 159);
        assertFalse(driver.shouldRetryNewOrderNavigation("Orders -> Your Orders"));
        set(driver, "ticksInPhase", 160);
        assertTrue(driver.shouldRetryNewOrderNavigation("Orders -> Your Orders"));
        assertFalse(driver.shouldRetryNewOrderNavigation("Review Order"));
        set(driver, "placeConfirmed", true);
        assertFalse(driver.shouldRetryNewOrderNavigation("Orders -> Your Orders"));
        set(driver, "placeConfirmed", false);
        set(driver, "newOrderNavigationRetries", 1);
        assertFalse(driver.shouldRetryNewOrderNavigation("Orders -> Your Orders"));
    }

    @Test
    void deskWatchdogExcludesQueueTimeAndRefreshesOnRealStepProgress() throws Exception {
        var driver = new AutomatedExecutionDriver();
        set(driver, "phaseStartedAtMillis", 100_000L);
        set(driver, "resendCommand", "orders");
        assertFalse(driver.operationProgressStalled(220_000, 100_000, 45_000));
        set(driver, "resendCommand", null);
        set(driver, "phaseStartedAtMillis", 220_000L);
        assertFalse(driver.operationProgressStalled(264_000, 100_000, 45_000));
        set(driver, "phaseStartedAtMillis", 250_000L);
        assertFalse(driver.operationProgressStalled(280_000, 100_000, 45_000));
        assertTrue(driver.operationProgressStalled(295_001, 100_000, 45_000));
    }

    @Test
    void failedPageTurnRestartsFromPageOneOnceWithoutReplayingATransaction() throws Exception {
        var driver = new AutomatedExecutionDriver();
        operation(driver, "AUDIT");
        var phase = field("phase");
        for (Object value : phase.getType().getEnumConstants())
            if (((Enum<?>)value).name().equals("WAITING_FOR_LISTING")) phase.set(driver, value);
        set(driver, "cancelStage", 1);
        set(driver, "auditPagesTurned", 2);
        set(driver, "auditUnreadable", 3);
        ((ListingPageTurn)field("auditPageTurn").get(driver)).requested("old-page", 1000);
        assertTrue(driver.restartIncompleteListingScan());
        assertEquals(0, field("auditPagesTurned").getInt(driver));
        assertEquals(0, field("auditUnreadable").getInt(driver));
        assertEquals(0, field("cancelStage").getInt(driver));
        assertFalse(((ListingPageTurn)field("auditPageTurn").get(driver)).pending());
        assertFalse(driver.restartIncompleteListingScan(), "bounded retry");
        set(driver, "listingScanRestarts", 0);
        operation(driver, "CANCEL");
        for (Object value : phase.getType().getEnumConstants())
            if (((Enum<?>)value).name().equals("WAITING_FOR_COMPLETION")) phase.set(driver, value);
        assertFalse(driver.restartIncompleteListingScan(), "never repeat an unconfirmed cancellation");
        operation(driver, "BUY");
        assertFalse(driver.restartIncompleteListingScan());
    }

    private static void operation(AutomatedExecutionDriver driver, String name) throws Exception {
        Field f = field("operation");
        for (Object op : f.getType().getEnumConstants()) {
            if (((Enum<?>) op).name().equals(name)) { f.set(driver, op); return; }
        }
        fail("No operation " + name);
    }
    private static void set(Object target, String name, Object value) throws Exception { field(name).set(target, value); }
    private static Field field(String name) throws Exception {
        Field field = AutomatedExecutionDriver.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
