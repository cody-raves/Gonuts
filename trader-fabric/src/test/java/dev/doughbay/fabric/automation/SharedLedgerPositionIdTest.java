package dev.doughbay.fabric.automation;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.storage.AutomationPersistenceWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two clients trading on one shared ledger mint position ids from the same
 * global id column. Seeding each client's counter from the wall clock put the
 * two counters a few milliseconds apart, so the busier client walked straight
 * into ids the other had already consumed and the ledger refused the write
 * ("Retail child position id is already in use").
 */
final class SharedLedgerPositionIdTest {
    @TempDir Path temp;

    @Test
    void twoAccountsOnOneLedgerNeverMintTheSamePositionId() throws Exception {
        AutomationPersistenceWorker first = workerAs("Trader2476");
        AutomationPersistenceWorker second = workerAs("Trader4130");
        try {
        AutomationSessionController alice = controllerAs(first);
        AutomationSessionController bob = controllerAs(second);
        // These two legal names collide under the former 19-bit account hash.
        // Their workers now reserve separate ranges on the same SQLite ledger.

        Set<Long> aliceIds = mint(alice, 64);
        Set<Long> bobIds = mint(bob, 64);

        assertEquals(64, aliceIds.size(), "a client must never reuse one of its own ids");
        assertEquals(64, bobIds.size(), "a client must never reuse one of its own ids");
        assertTrue(Collections.disjoint(aliceIds, bobIds),
                "clients sharing a ledger must mint ids in separate spaces; overlap was "
                        + aliceIds.stream().filter(bobIds::contains).toList());
        assertTrue(aliceIds.stream().allMatch(id -> id > 0), "position ids stay positive");
        assertTrue(bobIds.stream().allMatch(id -> id > 0), "position ids stay positive");
        } finally { closeAndAwait(first); closeAndAwait(second); }
    }

    private static void closeAndAwait(AutomationPersistenceWorker worker) throws Exception {
        worker.close();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (worker.status().loadState() != dev.doughbay.storage.AutomationPersistencePort.LoadState.CLOSED
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(dev.doughbay.storage.AutomationPersistencePort.LoadState.CLOSED,
                worker.status().loadState());
    }

    private AutomationPersistenceWorker workerAs(String account) throws Exception {
        AutomationPersistenceWorker persistence =
                new AutomationPersistenceWorker(temp.resolve("shared-ledger.db"));
        persistence.setClient(account);
        persistence.start();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!persistence.status().ready() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(persistence.status().ready(), persistence.status().detail());
        return persistence;
    }

    private static AutomationSessionController controllerAs(AutomationPersistenceWorker persistence) {
        AutomationSessionController controller =
                new AutomationSessionController(new AutomatedExecutionDriver());
        controller.setPersistence(persistence);
        return controller;
    }

    /** Takes {@code count} fresh ids out of the controller's own allocator. */
    private static Set<Long> mint(AutomationSessionController controller, int count)
            throws ReflectiveOperationException {
        Method supersede = AutomationSessionController.class.getDeclaredMethod(
                "supersedeQuantity", Position.class, int.class, long.class);
        supersede.setAccessible(true);
        Position one = new Position(7, "REAL", "minecraft:wither_skeleton_skull",
                StackBucket.of(1), 1, 10_000, 20_000, 1_000, 0, 0, 0,
                Double.NaN, PositionStatus.PURCHASED);
        Set<Long> ids = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            Position minted = (Position) supersede.invoke(controller, one, 2 + i, 1_000L);
            ids.add(minted.positionId());
        }
        return ids;
    }

}
