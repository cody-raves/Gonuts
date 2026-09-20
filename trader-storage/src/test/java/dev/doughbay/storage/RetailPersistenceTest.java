package dev.doughbay.storage;

import dev.doughbay.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class RetailPersistenceTest {
    @TempDir Path temp;
    private static Position batch() {
        return new Position(1, "REAL", "minecraft:diamond_block", StackBucket.X64, 64,
                640_037, 1_280_000, 10_000, 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
    }
    private static AutomationSessionCheckpoint cp(Position p) {
        return new AutomationSessionCheckpoint(true, "CONTINUOUS", "PREPARING_LIST", 1,
                640_037, 9_000, 11_000, "retail test", "", p, AutomationUncertainExposure.none());
    }

    @Test void allSixtyFourSinglesRetainEveryCoinAndOnePurchaseAcrossRestart() throws Exception {
        Path file = temp.resolve("retail.db");
        long next = 2;
        try (Database db = new Database(file)) {
            var repo = new PositionRepository(db);
            repo.setClient("Alice");
            Position remaining = batch();
            repo.saveRetail(cp(remaining), new RetailLot(1, 1, 20_000), null, null);
            while (remaining.quantity() > 1) {
                RetailSplit split = RetailSplit.of(remaining, next++, next++, 20_000);
                repo.saveRetail(cp(split.single()), new RetailLot(split.single().positionId(), 1, 20_000), remaining, split.remainder());
                remaining = split.remainder();
            }
        }
        try (Database db = new Database(file)) {
            var repo = new PositionRepository(db);
            repo.setClient("Alice");
            var recovered = repo.loadAutomationRecovery();
            assertEquals(64, recovered.openPositions().size());
            assertTrue(recovered.openPositions().stream().allMatch(p -> p.quantity() == 1));
            assertEquals(640_037, recovered.openPositions().stream().mapToLong(Position::purchasePrice).sum());
            assertEquals(640_037, recovered.committedSpend());
            assertEquals(1, recovered.tradesStarted());
            assertEquals(64, repo.retailLots().size());
            assertTrue(repo.retailLots().values().stream().allMatch(lot -> lot.rootPositionId() == 1));
            repo.setClient("Bob");
            assertTrue(repo.retailLots().isEmpty());
            assertTrue(repo.findOpen("REAL").isEmpty());
        }
    }

    @Test void rejectedChildCollisionLeavesTheEntireParentUntouched() throws Exception {
        try (Database db = new Database(temp.resolve("conflict.db"))) {
            var repo = new PositionRepository(db);
            Position root = batch();
            repo.saveRetail(cp(root), new RetailLot(1, 1, 20_000), null, null);
            var split = RetailSplit.of(root, 2, 3, 20_000);
            repo.upsert(split.remainder()); // Even an identical identity may belong to another operation.
            assertThrows(SQLException.class, () -> repo.saveRetail(cp(split.single()), new RetailLot(2, 1, 20_000), root, split.remainder()));
            assertEquals(PositionStatus.PURCHASED, repo.findAll("REAL").stream().filter(p -> p.positionId() == 1).findFirst().orElseThrow().status());
            assertTrue(repo.findAll("REAL").stream().noneMatch(p -> p.positionId() == 2));
            assertEquals(1, repo.retailLots().size());
        }
    }

    @Test void splitCannotChangeTheOwnerOrCostAndCannotBeAppliedTwice() throws Exception {
        try (Database db = new Database(temp.resolve("owner.db"))) {
            var repo = new PositionRepository(db);
            repo.setClient("Alice");
            Position root = batch();
            repo.saveRetail(cp(root), new RetailLot(1, 1, 20_000), null, null);
            var split = RetailSplit.of(root, 2, 3, 20_000);
            repo.setClient("Bob");
            assertThrows(SQLException.class, () -> repo.saveRetail(cp(root), new RetailLot(1, 1, 20_000), null, null));
            assertThrows(SQLException.class, () -> repo.saveRetail(cp(split.single()), new RetailLot(2, 1, 20_000), root, split.remainder()));
            repo.setClient("Alice");
            repo.saveRetail(cp(split.single()), new RetailLot(2, 1, 20_000), root, split.remainder());
            assertThrows(SQLException.class, () -> repo.saveRetail(cp(split.single()), new RetailLot(2, 1, 20_000), root, split.remainder()));
            assertEquals(640_037, repo.findOpen("REAL").stream().mapToLong(Position::purchasePrice).sum());
            assertEquals(64, repo.findOpen("REAL").stream().mapToInt(Position::quantity).sum());
            assertEquals(1, repo.loadAutomationRecovery().tradesStarted());
        }
    }

    @Test void writeFailureAfterParentCloseRollsBackBothChildrenAndParent() throws Exception {
        try (Database db = new Database(temp.resolve("rollback.db"))) {
            var repo = new PositionRepository(db);
            Position root = batch();
            repo.saveRetail(cp(root), new RetailLot(1, 1, 20_000), null, null);
            try (var st = db.connection().createStatement()) {
                st.execute("CREATE TRIGGER fail_remainder BEFORE INSERT ON retail_positions WHEN NEW.position_id=3 BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
            }
            var split = RetailSplit.of(root, 2, 3, 20_000);
            assertThrows(SQLException.class, () -> repo.saveRetail(cp(split.single()), new RetailLot(2, 1, 20_000), root, split.remainder()));
            assertEquals(java.util.List.of(root), repo.findOpen("REAL"));
            assertEquals(1, repo.findAll("REAL").size());
            assertEquals(1, repo.retailLots().size());
        }
    }

    @Test void workerAcknowledgesMetadataAndBothChildrenBeforeListingMayProceed() throws Exception {
        var worker = new AutomationPersistenceWorker(temp.resolve("worker.db"));
        worker.setClient("Alice");
        worker.start();
        try {
            await(() -> worker.status().ready());
            Position root = batch();
            long first = worker.submitRetail(cp(root), new RetailLot(1, 1, 20_000), null, null);
            await(() -> worker.status().durable(first));
            assertEquals(1, worker.retailLots().size());
            var split = RetailSplit.of(root, 2, 3, 20_000);
            long generation = worker.submitRetail(cp(split.single()), new RetailLot(2, 1, 20_000), root, split.remainder());
            assertTrue(generation > first);
            await(() -> worker.status().durable(generation));
            assertEquals(2, worker.retailLots().size());
            assertEquals(2, worker.status().recovery().openPositions().size());
            assertEquals(1, worker.status().recovery().tradesStarted());
        } finally {
            worker.close();
            await(() -> worker.status().loadState() == AutomationPersistencePort.LoadState.CLOSED
                    || worker.status().loadState() == AutomationPersistencePort.LoadState.FAILED);
        }
    }
    private static void await(java.util.function.BooleanSupplier check) throws Exception {
        long until = System.nanoTime() + 5_000_000_000L;
        while (!check.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(check.getAsBoolean());
    }
}
