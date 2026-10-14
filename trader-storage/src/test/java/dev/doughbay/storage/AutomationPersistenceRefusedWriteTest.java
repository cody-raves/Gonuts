package dev.doughbay.storage;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refused durable write is a rejected row, not a broken ledger. The writer
 * has to stay alive so the session can still be stopped durably and later
 * checkpoints still land; losing the writer thread is what turned one rejected
 * retail child into two hours of PAUSED/auto-resume with nothing sold.
 */
class AutomationPersistenceRefusedWriteTest {

    @TempDir
    Path temp;

    @Test
    void aChildIdTakenByAnotherClientDoesNotKillTheWriterForGood() throws Exception {
        Path file = temp.resolve("shared.db");
        // Bob, the other client on this shared ledger, already owns id 2.
        try (Database database = new Database(file)) {
            PositionRepository bob = new PositionRepository(database);
            bob.setClient("Bob");
            bob.upsert(new Position(2, "REAL", "minecraft:wither_skeleton_skull",
                    StackBucket.of(1), 1, 10_000, 20_000, 8_000, 0, 0, 0,
                    Double.NaN, PositionStatus.PURCHASED));
        }

        AutomationPersistenceWorker worker = new AutomationPersistenceWorker(file);
        worker.setClient("Alice");
        worker.start();
        try {
            await(() -> worker.status().ready());
            Position batch = batch();
            long root = worker.submitRetail(checkpoint(batch), new RetailLot(1, 1, 20_000), null, null);
            await(() -> worker.status().durable(root));

            RetailSplit collides = RetailSplit.of(batch, 2, 3, 20_000);
            long refused = worker.submitRetail(checkpoint(collides.single()),
                    new RetailLot(2, 1, 20_000), batch, collides.remainder());
            assertTrue(refused > 0, "the colliding write was accepted onto the queue");
            await(() -> worker.status().detail().contains("already in use"));

            AutomationPersistencePort.Status afterRefusal = worker.status();
            assertEquals(AutomationPersistencePort.LoadState.READY, afterRefusal.loadState(),
                    "a rejected row must not take the whole ledger down: " + afterRefusal.detail());
            assertEquals(root, afterRefusal.lastDurableGeneration(),
                    "the refused write must not be reported as durable");
            // Nothing of the refused split may be left behind.
            assertEquals(1, worker.retailLots().size());
            assertEquals(PositionStatus.PURCHASED, stored(worker, 1).status());
            assertEquals(64, stored(worker, 1).quantity());

            // The writer still works: a split on free ids becomes durable.
            RetailSplit clean = RetailSplit.of(batch, 4, 5, 20_000);
            long next = worker.submitRetail(checkpoint(clean.single()),
                    new RetailLot(4, 1, 20_000), batch, clean.remainder());
            assertTrue(next > 0, "the worker must still accept writes after a refused one");
            await(() -> worker.status().durable(next));
            assertEquals(2, worker.retailLots().size());
            org.junit.jupiter.api.Assertions.assertFalse(worker.status().durable(refused), "A refused generation must never become durable merely because a later write succeeded");
            assertTrue(worker.status().refused(refused));
            assertTrue(worker.status().refusalOutstanding(), "An unrelated queued write must not clear the refusal latch");

            RetailSplit anotherCollision = RetailSplit.of(clean.remainder(), 2, 6, 20_000);
            long refusedAgain = worker.submitRetail(checkpoint(anotherCollision.single()),
                    new RetailLot(2, 1, 20_000), clean.remainder(), anotherCollision.remainder());
            await(() -> worker.status().refused(refusedAgain));
            long heartbeat = worker.submit(checkpoint(clean.single()));
            await(() -> worker.status().durable(heartbeat));
            assertTrue(worker.status().refused(refused));
            assertTrue(worker.status().refused(refusedAgain));
            org.junit.jupiter.api.Assertions.assertFalse(worker.status().durable(refused));
            org.junit.jupiter.api.Assertions.assertFalse(worker.status().durable(refusedAgain));
            assertTrue(worker.status().refusalOutstanding());
        } finally {
            worker.close();
            await(() -> worker.status().loadState() == AutomationPersistencePort.LoadState.CLOSED);
        }
    }

    private static Position stored(AutomationPersistenceWorker worker, long id) {
        return worker.status().recovery().openPositions().stream()
                .filter(position -> position.positionId() == id).findFirst().orElseThrow();
    }

    private static Position batch() {
        return new Position(1, "REAL", "minecraft:wither_skeleton_skull", StackBucket.X64, 64,
                640_037, 1_280_000, 10_000, 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
    }

    private static AutomationSessionCheckpoint checkpoint(Position position) {
        return new AutomationSessionCheckpoint(true, "CONTINUOUS", "PREPARING_LIST", 1,
                640_037, 9_000, 11_000, "retail", "", position,
                AutomationUncertainExposure.none());
    }

    private static void await(java.util.function.BooleanSupplier check) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!check.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(check.getAsBoolean(), "condition did not become true before the deadline");
    }
}
