package dev.doughbay.storage;

import dev.doughbay.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PositionIdAllocatorTest {
    @TempDir Path temp;

    @Test void concurrentLeasesAndRestartNeverReuseEvenUnwrittenIds() throws Exception {
        Path file = temp.resolve("leases.db");
        try (Database init = new Database(file)) {
            PositionRepository positions = new PositionRepository(init);
            positions.upsert(position(PositionIdAllocator.FIRST_ID + 100, PositionStatus.SOLD));
        }
        Set<Long> all = ConcurrentHashMap.newKeySet();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int thread = 0; thread < 2; thread++) tasks.add(executor.submit(() -> {
                try (Database database = new Database(file)) {
                    for (int batch = 0; batch < 20; batch++) {
                        long first = PositionIdAllocator.reserve(database.connection(), 64);
                        assertTrue(first > PositionIdAllocator.FIRST_ID + 100);
                        for (int i = 0; i < 64; i++) assertTrue(all.add(first + i), "duplicate lease");
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
            for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
        }
        assertEquals(2560, all.size());
        try (Database restarted = new Database(file)) {
            long next = PositionIdAllocator.reserve(restarted.connection(), 64);
            assertTrue(next > Collections.max(all));
            Position inserted = new PositionRepository(restarted).insert(position(0, PositionStatus.PURCHASED));
            assertTrue(inserted.positionId() >= next + 64, "generated repository IDs must skip leased IDs");
            long later = PositionIdAllocator.reserve(restarted.connection(), 64);
            assertTrue(later > inserted.positionId());
        }
    }

    @Test void workerAllocationIsNonblockingAndUnusedLeaseSurvivesRestart() throws Exception {
        Path file = temp.resolve("worker.db");
        AutomationPersistenceWorker first = new AutomationPersistenceWorker(file);
        first.setClient("Trader2476");
        first.start(); awaitReady(first);
        long id = first.allocatePositionId();
        first.close();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (first.status().loadState() != AutomationPersistencePort.LoadState.CLOSED
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(AutomationPersistencePort.LoadState.CLOSED, first.status().loadState());
        AutomationPersistenceWorker restarted = new AutomationPersistenceWorker(file);
        restarted.setClient("Trader2476");
        restarted.start();
        try { awaitReady(restarted); assertTrue(restarted.allocatePositionId() > id + 4095); }
        finally { closeAndAwait(restarted); }
    }

    private static void closeAndAwait(AutomationPersistenceWorker worker) throws Exception {
        worker.close();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (worker.status().loadState() != AutomationPersistencePort.LoadState.CLOSED
                && worker.status().loadState() != AutomationPersistencePort.LoadState.FAILED
                && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(AutomationPersistencePort.LoadState.CLOSED, worker.status().loadState());
    }

    private static void awaitReady(AutomationPersistenceWorker worker) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!worker.status().ready() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(worker.status().ready(), worker.status().detail());
    }

    private static Position position(long id, PositionStatus status) {
        return new Position(id, "REAL", "minecraft:diamond", StackBucket.of(1), 1,
                100, 200, 1000, 0, status == PositionStatus.SOLD ? 2000 : 0,
                status == PositionStatus.SOLD ? 200 : 0,
                status == PositionStatus.SOLD ? 100 : Double.NaN, status);
    }
}
