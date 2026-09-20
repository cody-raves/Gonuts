package dev.doughbay.fabric.automation;

import dev.doughbay.core.automation.ContinuousAutomationPolicy;
import dev.doughbay.core.execution.ExecutionResult;
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

final class AutomationSessionControllerPersistenceTest {

    @Test
    void durableListIntentSurvivesLongConfirmationRetries() throws Exception {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller = new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        persistence.status = new AutomationPersistencePort.Status(AutomationPersistencePort.LoadState.READY,
                persistence.status.recovery(), 7, 7, "Durable");
        setField(controller, "listIntentPersistenceGeneration", 7L);
        checkWatchdog(controller, 1_000);
        checkWatchdog(controller, 121_001);
        assertEquals(7L, field(controller, "listIntentPersistenceGeneration"));
        assertTrue(persistence.status().durable((long) field(controller, "listIntentPersistenceGeneration")),
                "a retry must retain its acknowledged LIST intent after the two-minute watchdog");
        assertEquals(0L, field(controller, "pendingPersistenceSince"));
    }

    @Test
    void stalledWriteRetainsReceiptsForLateAcknowledgement() throws Exception {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller = new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        persistence.status = new AutomationPersistencePort.Status(AutomationPersistencePort.LoadState.READY,
                persistence.status.recovery(), 8, 7, "Queued");
        setField(controller, "listIntentPersistenceGeneration", 8L);
        checkWatchdog(controller, 1_000);
        checkWatchdog(controller, 121_001);
        assertEquals(8L, field(controller, "listIntentPersistenceGeneration"));
        persistence.acknowledgeAll();
        checkWatchdog(controller, 122_000);
        assertTrue(persistence.status().durable((long) field(controller, "listIntentPersistenceGeneration")));
        assertEquals(0L, field(controller, "pendingPersistenceSince"));
    }

    @Test
    void persistenceProgressRestartsStallBudget() throws Exception {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller = new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        persistence.status = new AutomationPersistencePort.Status(AutomationPersistencePort.LoadState.READY,
                persistence.status.recovery(), 9, 7, "Queued");
        checkWatchdog(controller, 1_000);
        persistence.status = new AutomationPersistencePort.Status(AutomationPersistencePort.LoadState.READY,
                persistence.status.recovery(), 9, 8, "Progress");
        checkWatchdog(controller, 120_000);
        checkWatchdog(controller, 121_001);
        assertEquals(120_000L, field(controller, "pendingPersistenceSince"));
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void checkWatchdog(AutomationSessionController controller, long now)
            throws ReflectiveOperationException {
        var method = AutomationSessionController.class.getDeclaredMethod("checkStuckPersistence", long.class);
        method.setAccessible(true);
        method.invoke(controller, now);
    }

    @Test
    void pendingRecoveredSessionCloseCannotBeFollowedByResumeOpen() {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller =
                new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        controller.tick(null, null);

        assertTrue(controller.snapshot().recoveredSession());
        assertFalse(controller.snapshot().unresolvedExposure());
        assertTrue(controller.endRecoveredSession().ok());
        assertEquals("SESSION_CLOSE_PENDING",
                controller.snapshot().manualResolutionStage());
        assertEquals(1, persistence.submissions.get());

        ExecutionResult beforeDurable = controller.resumeRecoveredSession(policy());
        assertEquals(ExecutionResult.Outcome.REFUSED, beforeDurable.outcome());
        assertEquals(1, persistence.submissions.get(),
                "resume must not enqueue OPEN behind an undurable CLOSE");

        persistence.acknowledgeAll();
        ExecutionResult durableButUnconsumed =
                controller.resumeRecoveredSession(policy());
        assertEquals(ExecutionResult.Outcome.REFUSED,
                durableButUnconsumed.outcome());
        assertEquals(1, persistence.submissions.get(),
                "resume must not enqueue OPEN behind a durable but unconsumed CLOSE");
    }

    @Test
    void rejectedCloseStillStopsRunningControllerFailClosed() throws Exception {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller =
                new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        controller.tick(null, null);

        setField(controller, "state", AutomationSessionController.State.SCANNING);
        setField(controller, "recoveredSession", false);
        persistence.rejectSubmissions = true;

        controller.stop();
        AutomationSessionController.SessionSnapshot stopped = controller.snapshot();
        assertEquals(AutomationSessionController.State.PAUSED, stopped.state());
        assertFalse(stopped.active());
        assertFalse(stopped.resumable());
        assertTrue(stopped.recoveredSession());
        assertTrue(stopped.detail().contains("could not close durably"));
        assertEquals(1, persistence.submissions.get());

        controller.tick(null, null);
        assertEquals(AutomationSessionController.State.PAUSED,
                controller.snapshot().state(),
                "a rejected CLOSE must never leave scanning able to advance next tick");
    }

    @Test
    void activeRuntimeUsesMonotonicElapsedTimeAndFreezesWhilePaused() throws Exception {
        FakePersistence persistence = new FakePersistence();
        AutomationSessionController controller =
                new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        controller.tick(null, null);

        assertEquals(250, controller.snapshot().currentSessionActiveMillis());
        assertEquals(750, controller.snapshot().lifetimeActiveMillis());

        setField(controller, "state", AutomationSessionController.State.SCANNING);
        setField(controller, "sessionOpen", true);
        setField(controller, "activeStateStartedNanos",
                System.nanoTime() - 50_000_000L);
        long whileActive = controller.snapshot().currentSessionActiveMillis();
        assertTrue(whileActive >= 295 && whileActive < 1_000,
                "active runtime should include the monotonic in-flight interval");

        controller.pause("runtime test pause");
        long paused = controller.snapshot().currentSessionActiveMillis();
        Thread.sleep(20);
        assertEquals(paused, controller.snapshot().currentSessionActiveMillis(),
                "PAUSED wall time must never become automation runtime");
        assertEquals(500, controller.snapshot().lifetimeActiveMillis()
                        - controller.snapshot().currentSessionActiveMillis(),
                "session and lifetime counters must advance by the same delta");
    }

    private static ContinuousAutomationPolicy policy() {
        return ContinuousAutomationPolicy.fromConfigValues(
                2, 100_000, 50_000, 5_000,
                12, 80, 10, 8, 120, 1);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakePersistence implements AutomationPersistencePort {
        private final AtomicInteger submissions = new AtomicInteger();
        private volatile boolean rejectSubmissions;
        private volatile Status status = new Status(
                LoadState.READY,
                new AutomationSessionRecovery(true, "CONTINUOUS", "PAUSED",
                        1, 4_100, 1_000, 250, 750, 2_000,
                        "Recovered open session", "",
                        null, java.util.List.of()),
                0, 0, "Recovery ready");

        @Override
        public void start() {
        }

        @Override
        public long submit(AutomationSessionCheckpoint checkpoint) {
            long generation = submissions.incrementAndGet();
            if (rejectSubmissions) return 0;
            status = new Status(LoadState.READY, status.recovery(),
                    generation, status.lastDurableGeneration(),
                    "Checkpoint queued");
            return generation;
        }

        @Override
        public long submitManualResolution(
                AutomationManualResolution resolution,
                AutomationSessionCheckpoint checkpoint) {
            throw new AssertionError("manual resolution was not expected");
        }

        @Override
        public Status status() {
            return status;
        }

        void acknowledgeAll() {
            status = new Status(LoadState.READY, status.recovery(),
                    status.lastAcceptedGeneration(), status.lastAcceptedGeneration(),
                    "Checkpoint durable but not consumed by controller");
        }

        @Override
        public void close() {
        }
    }
}
