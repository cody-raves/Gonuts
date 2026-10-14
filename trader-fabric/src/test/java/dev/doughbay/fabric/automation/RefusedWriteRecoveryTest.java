package dev.doughbay.fabric.automation;

import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.storage.AutomationManualResolution;
import dev.doughbay.storage.AutomationPersistencePort;
import dev.doughbay.storage.AutomationSessionCheckpoint;
import dev.doughbay.storage.AutomationSessionRecovery;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger refusing one row is recoverable. The session must say so at once,
 * must not re-offer the identical refused write on the auto-resume timer, and
 * must still be able to close itself durably.
 */
final class RefusedWriteRecoveryTest {

    @Test
    void unrelatedLaterSaveCannotAcknowledgeARejectedPurchase() throws Exception {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller = new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        setField(controller, "purchasePersistenceGeneration", 8L);
        setField(controller, "buyIntentPersistenceGeneration", 7L);
        persistence.status = new AutomationPersistencePort.Status(
                AutomationPersistencePort.LoadState.READY, persistence.status.recovery(),
                9, 9, "Unrelated heartbeat durable", 8, java.util.Set.of(8L));
        var method = AutomationSessionController.class.getDeclaredMethod("processOutOfBandPersistence");
        method.setAccessible(true);
        method.invoke(controller);
        assertEquals(8L, field(controller, "purchasePersistenceGeneration"),
                "A refused purchase must not be acknowledged by a later heartbeat");
        assertEquals(7L, field(controller, "buyIntentPersistenceGeneration"),
                "The purchase failure must retain the outstanding BUY intent");
        assertTrue(persistence.status.refusalOutstanding());
        setField(controller, "state", AutomationSessionController.State.SCANNING);
        setField(controller, "sessionOpen", true);
        checkWatchdog(controller, 1000);
        assertEquals(AutomationSessionController.State.PAUSED, controller.snapshot().state());
    }

    @Test
    void refusedWritePausesAtOnceInsteadOfWaitingOutTheStallBudget() throws Exception {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller =
                new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        setField(controller, "state", AutomationSessionController.State.SCANNING);
        setField(controller, "sessionOpen", true);
        persistence.refuse(8, 7, "Automation persistence refused a write: "
                + "SQLException: Retail child position id is already in use");

        checkWatchdog(controller, 1_000);

        assertEquals(AutomationSessionController.State.PAUSED, controller.snapshot().state());
        assertTrue(controller.snapshot().detail().contains("refused"),
                controller.snapshot().detail());
        assertEquals(0L, field(controller, "pendingPersistenceSince"));
    }

    @Test
    void anOutstandingRefusalIsNotRetriedOnTheAutoResumeTimer() throws Exception {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller =
                new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        setField(controller, "state", AutomationSessionController.State.PAUSED);
        setField(controller, "sessionOpen", true);
        setField(controller, "armed", true);
        setField(controller, "policy", dev.doughbay.core.automation.ContinuousAutomationPolicy
                .fromConfigValues(2, 100_000, 50_000, 5_000, 12, 80, 10, 8, 120, 1));
        persistence.refuse(8, 7, "SQLException: Retail child position id is already in use");

        var method = AutomationSessionController.class.getDeclaredMethod(
                "autoResumeInternalPause", net.minecraft.client.Minecraft.class, long.class);
        method.setAccessible(true);
        method.invoke(controller, null, System.currentTimeMillis() + 600_000L);

        assertEquals(AutomationSessionController.State.PAUSED, controller.snapshot().state());
        assertTrue(controller.snapshot().detail().contains("refused"),
                controller.snapshot().detail());
        assertEquals(0, persistence.submissions.get(),
                "the refused write must not be handed to the ledger again on a timer");
    }

    @Test
    void sessionStillClosesDurablyWhileAWriteStandsRefused() throws Exception {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller =
                new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        controller.tick(null, null);
        setField(controller, "state", AutomationSessionController.State.SCANNING);
        setField(controller, "recoveredSession", false);
        int before = persistence.submissions.get();
        persistence.refuse(before + 1L, before,
                "SQLException: Retail child position id is already in use");

        controller.stop();

        assertEquals(before + 1, persistence.submissions.get(),
                "the session-end checkpoint must still be queued");
        assertFalse(controller.snapshot().detail().contains("Cannot end session"),
                controller.snapshot().detail());
        assertEquals(AutomationSessionController.State.PAUSED, controller.snapshot().state());
    }

    private static void checkWatchdog(AutomationSessionController controller, long now)
            throws ReflectiveOperationException {
        var method = AutomationSessionController.class.getDeclaredMethod(
                "checkStuckPersistence", long.class);
        method.setAccessible(true);
        method.invoke(controller, now);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakePersistence implements AutomationPersistencePort {
        private final AtomicInteger submissions = new AtomicInteger();
        private volatile Status status = new Status(
                LoadState.READY,
                new AutomationSessionRecovery(true, "CONTINUOUS", "PAUSED",
                        1, 4_100, 1_000, 250, 750, 2_000,
                        "Recovered open session", "", null, java.util.List.of()),
                0, 0, "Recovery ready");

        void refuse(long refused, long durable, String detail) {
            status = new Status(LoadState.READY, status.recovery(),
                    Math.max(refused, status.lastAcceptedGeneration()), durable, detail, refused);
        }

        @Override
        public void start() {
        }

        @Override
        public long submit(AutomationSessionCheckpoint checkpoint) {
            long generation = submissions.incrementAndGet();
            status = new Status(LoadState.READY, status.recovery(),
                    Math.max(generation, status.lastAcceptedGeneration()),
                    status.lastDurableGeneration(), "Checkpoint queued",
                    status.lastRefusedGeneration());
            return generation;
        }

        @Override
        public long submitManualResolution(AutomationManualResolution resolution,
                                           AutomationSessionCheckpoint checkpoint) {
            throw new AssertionError("manual resolution was not expected");
        }

        @Override
        public Status status() {
            return status;
        }

        @Override
        public void close() {
        }
    }
}
