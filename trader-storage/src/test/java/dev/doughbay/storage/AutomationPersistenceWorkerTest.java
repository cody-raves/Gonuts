package dev.doughbay.storage;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationPersistenceWorkerTest {

    @TempDir
    Path tempDir;

    @Test
    void databaseIsOpenedOffCallerAndGenerationAcknowledgesDurability() throws Exception {
        Path file = tempDir.resolve("market.db");
        AutomationPersistenceWorker worker = new AutomationPersistenceWorker(file);
        assertFalse(Files.exists(file));
        assertEquals(0, worker.submit(checkpoint()));

        worker.start();
        await(() -> worker.status().ready());
        long generation = worker.submit(checkpoint());
        assertTrue(generation > 0);
        await(() -> worker.status().durable(generation));
        worker.close();

        try (Database database = new Database(file)) {
            AutomationSessionRecovery recovery =
                    new PositionRepository(database).loadAutomationRecovery();
            assertTrue(recovery.unresolvedExposure());
            assertEquals(6500, recovery.committedSpend());
            assertEquals(PositionStatus.PURCHASED,
                    recovery.openPositions().getFirst().status());
        }
    }

    @Test
    void asynchronousCloseDrainsAlreadyAcceptedCheckpoint() throws Exception {
        Path file = tempDir.resolve("shutdown.db");
        AutomationPersistenceWorker worker = new AutomationPersistenceWorker(file);
        worker.start();
        await(() -> worker.status().ready());
        long generation = worker.submit(checkpoint());
        assertTrue(generation > 0);
        worker.close();
        await(() -> worker.status().loadState()
                == AutomationPersistencePort.LoadState.CLOSED);
        assertTrue(worker.status().lastDurableGeneration() >= generation);

        try (Database database = new Database(file)) {
            assertTrue(new PositionRepository(database)
                    .loadAutomationRecovery().unresolvedExposure());
        }
    }

    private static AutomationSessionCheckpoint checkpoint() {
        Position position = new Position(42, "REAL", "minecraft:obsidian",
                StackBucket.X64, 64, 6500, 9000, 10_000, 0, 0, 0,
                Double.NaN, PositionStatus.PURCHASED);
        return new AutomationSessionCheckpoint(true, "SINGLE", "PREPARING_LIST",
                1, 6500, 9_000, 10_100, "verified purchase", "",
                position, AutomationUncertainExposure.none());
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!check.ok() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(check.ok(), "condition did not become true before deadline");
    }

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}
