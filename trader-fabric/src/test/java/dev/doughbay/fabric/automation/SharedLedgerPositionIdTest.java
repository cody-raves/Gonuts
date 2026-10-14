package dev.doughbay.fabric.automation;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.storage.AutomationPersistenceWorker;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
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

    @Test
    void twoAccountsOnOneLedgerNeverMintTheSamePositionId() throws Exception {
        AutomationSessionController alice = controllerAs("Alice");
        AutomationSessionController bob = controllerAs("Bob");
        // Launched a few milliseconds apart, as two clients started by the same
        // script are. Each retail peel burns two ids, so the earlier client
        // overtakes the later one within a single batch of singles.
        setField(alice, "nextPositionId", 1_791_999_128_679L);
        setField(bob, "nextPositionId", 1_791_999_128_682L);

        Set<Long> aliceIds = mint(alice, 64);
        Set<Long> bobIds = mint(bob, 64);

        assertEquals(64, aliceIds.size(), "a client must never reuse one of its own ids");
        assertEquals(64, bobIds.size(), "a client must never reuse one of its own ids");
        assertTrue(Collections.disjoint(aliceIds, bobIds),
                "clients sharing a ledger must mint ids in separate spaces; overlap was "
                        + aliceIds.stream().filter(bobIds::contains).toList());
        assertTrue(aliceIds.stream().allMatch(id -> id > 0), "position ids stay positive");
        assertTrue(bobIds.stream().allMatch(id -> id > 0), "position ids stay positive");
    }

    private static AutomationSessionController controllerAs(String account) {
        // The worker is never started: nothing here touches SQLite, only the
        // account identity the controller mints ids for.
        AutomationPersistenceWorker persistence =
                new AutomationPersistenceWorker(Path.of("shared-ledger.db"));
        persistence.setClient(account);
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

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
