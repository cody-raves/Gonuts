package dev.doughbay.fabric.automation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderReadWatchdogTest {
    @Test
    void controllerDoesNotAbortAnOrderCommandStillQueuedAfterTwoMinutes() throws Exception {
        var driver = new dev.doughbay.fabric.AutomatedExecutionDriver();
        var controller = new AutomationSessionController(driver);
        var pending = driver.getClass().getDeclaredField("resendCommand");
        pending.setAccessible(true);
        pending.set(driver, "orders");
        var sentAt = driver.getClass().getDeclaredField("lastCommandSentAt");
        sentAt.setAccessible(true);
        sentAt.setLong(driver, 90_000);
        var observe = AutomationSessionController.class.getDeclaredMethod("observeOrdersTerminal",
                net.minecraft.client.Minecraft.class, long.class);
        observe.setAccessible(true);
        observe.invoke(controller, null, 100_000L);
        observe.invoke(controller, null, 220_000L);
        assertTrue(driver.commandPending());
        assertTrue(controller.snapshot().detail().contains("Waiting to send"));
        pending.set(driver, null);
        sentAt.setLong(driver, 220_000);
        observe.invoke(controller, null, 239_999L);
        assertTrue(controller.snapshot().detail().contains("command sent"));
    }

    @Test
    void serverHoldDoesNotConsumeThePageProgressTimeout() {
        var watchdog = new OrderReadWatchdog();
        for (long now = 100_000; now <= 220_000; now += 10_000) {
            assertFalse(watchdog.stalled(now, true, 90_000, 0));
        }
        assertEquals(0, watchdog.startedAt());
        assertFalse(watchdog.stalled(220_000, false, 220_000, 0));
        assertFalse(watchdog.stalled(240_000, false, 220_000, 0));
        assertTrue(watchdog.stalled(240_001, false, 220_000, 0));
        assertEquals(220_000, watchdog.startedAt());
    }

    @Test
    void queuedRetryStartsAFreshWaitOnlyWhenDispatched() {
        var watchdog = new OrderReadWatchdog();
        assertFalse(watchdog.stalled(100_000, false, 100_000, 0));
        assertFalse(watchdog.stalled(119_000, true, 100_000, 0));
        assertFalse(watchdog.stalled(200_000, true, 100_000, 0));
        assertFalse(watchdog.stalled(220_000, false, 210_000, 0));
        assertTrue(watchdog.stalled(230_001, false, 210_000, 0));
    }

    @Test
    void pageProgressKeepsLongSweepAliveButADroppedNavigationStillExpires() {
        var watchdog = new OrderReadWatchdog();
        assertFalse(watchdog.stalled(100_000, false, 100_000, 0));
        for (int page = 1; page <= 134; page++) {
            assertFalse(watchdog.stalled(100_000 + page * 1_000, false, 100_000, page));
        }
        assertFalse(watchdog.stalled(254_000, false, 100_000, 134));
        assertTrue(watchdog.stalled(254_001, false, 100_000, 134));
        watchdog.reset();
        assertFalse(watchdog.stalled(300_000, false, 300_000, 0));
        assertEquals(300_000, watchdog.startedAt());
    }
}
