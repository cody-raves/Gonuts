package dev.doughbay.fabric.automation;

import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.fabric.Tuning;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class RecoveredPurchaseStopTest {
    @Test
    void playerStopOverridesRecoveredStockAutoResumeEvenAfterItsDelay() throws Exception {
        var controller = recoveredController();
        double previous = Tuning.get("session.auto_resume");
        try {
            Tuning.set("session.auto_resume", 1);
            assertTrue(controller.mayAutoListRecoveredPurchase(10_000));
            set(controller, "pausedByPlayer", true);
            assertFalse(controller.mayAutoListRecoveredPurchase(10_000));
            assertFalse(controller.mayAutoListRecoveredPurchase(1_000_000));
            set(controller, "armed", true);
            assertFalse(controller.mayAutoListRecoveredPurchase(1_000_000),
                    "arming alone must not override an explicit player stop");
        } finally {
            Tuning.set("session.auto_resume", previous);
        }
    }

    @Test
    void recoveredStockStillHonorsStartupDelayAndOptIn() throws Exception {
        var controller = recoveredController();
        double previous = Tuning.get("session.auto_resume");
        try {
            Tuning.set("session.auto_resume", 0);
            assertFalse(controller.mayAutoListRecoveredPurchase(10_000));
            set(controller, "armed", true);
            assertFalse(controller.mayAutoListRecoveredPurchase(4_999));
            assertTrue(controller.mayAutoListRecoveredPurchase(5_000));
            set(controller, "state", AutomationSessionController.State.LISTING);
            assertFalse(controller.mayAutoListRecoveredPurchase(10_000));
        } finally {
            Tuning.set("session.auto_resume", previous);
        }
    }

    private static AutomationSessionController recoveredController() throws Exception {
        var controller = new AutomationSessionController(new AutomatedExecutionDriver());
        set(controller, "state", AutomationSessionController.State.PAUSED);
        set(controller, "recoveredSession", true);
        set(controller, "sessionOpen", true);
        set(controller, "stateChangedAtMillis", 0L);
        return controller;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
