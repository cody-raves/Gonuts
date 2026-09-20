package dev.doughbay.storage;

import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.Sale;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryTest {

    private Database db;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private static Sale sale(String hash, long soldAt, long price) {
        return new Sale(hash, soldAt, "uuid-1", "Seller", "minecraft:redstone",
                "minecraft:redstone", 64, price);
    }

    @Test
    void duplicateTransactionsAreStoredOnce() throws Exception {
        TransactionRepository repo = new TransactionRepository(db);
        Sale s = sale("hash-1", 1000, 5000);
        assertTrue(repo.insertIfAbsent(s, "{}"));
        // Same record arriving again from an overlapping page: rejected.
        assertFalse(repo.insertIfAbsent(s, "{}"));
        assertEquals(1, repo.count());
    }

    @Test
    void outliersAreFlaggedAndExcludedFromQueriesButNotDeleted() throws Exception {
        TransactionRepository repo = new TransactionRepository(db);
        repo.insertIfAbsent(sale("h1", 1000, 5000), "{}");
        repo.insertIfAbsent(sale("h2", 2000, 90_000_000), "{}");
        repo.markOutliers(Set.of("h2"));

        List<Sale> visible = repo.findByItemKeySince("minecraft:redstone", 0);
        assertEquals(1, visible.size());
        assertEquals("h1", visible.get(0).transactionHash());
        // The row itself still exists.
        assertEquals(2, repo.count());
    }

    @Test
    void listingSnapshotsKeepOnlyLatestPerListingInOrderBook() throws Exception {
        ListingRepository repo = new ListingRepository(db);
        Listing first = new Listing("key-1", 1000, "u", "n", "minecraft:redstone",
                "minecraft:redstone", 64, 5000, null);
        Listing later = new Listing("key-1", 2000, "u", "n", "minecraft:redstone",
                "minecraft:redstone", 64, 4500, 60_000L);
        Listing other = new Listing("key-2", 1500, "u2", "n2", "minecraft:redstone",
                "minecraft:redstone", 64, 6000, null);
        repo.insertSnapshot(first, "{}");
        repo.insertSnapshot(later, "{}");
        repo.insertSnapshot(other, "{}");

        List<Listing> book = repo.latestForItem("minecraft:redstone", 0);
        assertEquals(2, book.size());
        // key-1 must appear once, at its most recent price.
        Listing key1 = book.stream().filter(l -> l.listingKey().equals("key-1")).findFirst().orElseThrow();
        assertEquals(4500, key1.totalPrice());
    }

    @Test
    void positionsRoundTrip() throws Exception {
        PositionRepository repo = new PositionRepository(db);
        Position p = new Position(0, "PAPER", "minecraft:redstone", StackBucket.X64, 64,
                3000, 9000, 100, 100, 0, 0, Double.NaN, PositionStatus.SIMULATED_LISTED);
        Position stored = repo.insert(p);
        assertTrue(stored.positionId() > 0);

        List<Position> open = repo.findOpen("PAPER");
        assertEquals(1, open.size());
        assertTrue(Double.isNaN(open.get(0).realizedProfit()));

        repo.update(stored.closed(PositionStatus.LIKELY_SOLD, 200, 9000, 6000.0));
        assertTrue(repo.findOpen("PAPER").isEmpty());
        List<Position> all = repo.findAll("PAPER");
        assertEquals(1, all.size());
        assertEquals(PositionStatus.LIKELY_SOLD, all.get(0).status());
        assertEquals(6000.0, all.get(0).realizedProfit(), 0.001);
    }

    @Test
    void automationUpsertCannotOverwriteDifferentPositionIdentity() throws Exception {
        PositionRepository repo = new PositionRepository(db);
        Position paper = repo.insert(new Position(0, "PAPER", "minecraft:redstone",
                StackBucket.X64, 64, 3000, 9000, 100, 0, 0, 0,
                Double.NaN, PositionStatus.SIMULATED_PURCHASED));
        Position collision = new Position(paper.positionId(), "REAL", "minecraft:obsidian",
                StackBucket.X64, 64, 8000, 12000, 200, 0, 0, 0,
                Double.NaN, PositionStatus.PURCHASED);

        assertThrows(java.sql.SQLException.class, () -> repo.upsert(collision));
        Position unchanged = repo.findAll("PAPER").getFirst();
        assertEquals("minecraft:redstone", unchanged.itemKey());
        assertEquals(PositionStatus.SIMULATED_PURCHASED, unchanged.status());
    }

    @Test
    void automationCheckpointRetainsUncertainBuyBeforePositionExists() throws Exception {
        PositionRepository repo = new PositionRepository(db);
        AutomationUncertainExposure uncertain = new AutomationUncertainExposure(
                true, "BUYING", "listing-7", "minecraft:ender_pearl",
                "minecraft:ender_pearl", 16, 4100, 8950,
                "Seller", 1_000);
        repo.saveAutomationCheckpoint(new AutomationSessionCheckpoint(
                true, "CONTINUOUS", "BUYING", 0, 0,
                900, 1_100, "BUY intent durable before command", "",
                null, uncertain));

        AutomationSessionRecovery recovery = repo.loadAutomationRecovery();
        assertTrue(recovery.sessionOpen());
        assertTrue(recovery.unresolvedExposure());
        assertTrue(recovery.uncertainExposure().active());
        assertEquals("listing-7", recovery.uncertainExposure().listingKey());
        assertEquals(4100, recovery.uncertainExposure().buyPrice());
        assertEquals(1, recovery.tradesStarted());
        assertEquals(4100, recovery.committedSpend());
        assertTrue(recovery.openPositions().isEmpty());
    }

    @Test
    void automationRecoveryReconstructsCountersAndKeepsResolvedSessionOpen() throws Exception {
        PositionRepository repo = new PositionRepository(db);
        Position purchased = new Position(7, "REAL", "minecraft:redstone",
                StackBucket.X64, 64, 3000, 9000, 1_000, 0, 0, 0,
                Double.NaN, PositionStatus.PURCHASED);
        repo.saveAutomationCheckpoint(new AutomationSessionCheckpoint(
                true, "CONTINUOUS", "PREPARING_LIST", 0, 0,
                900, 1_100, "purchase verified", "", purchased,
                AutomationUncertainExposure.none()));

        AutomationSessionRecovery recovered = repo.loadAutomationRecovery();
        assertEquals(1, recovered.tradesStarted());
        assertEquals(3000, recovered.committedSpend());
        assertEquals(1, recovered.openPositions().size());

        Position resolved = purchased.closed(
                PositionStatus.CANCELLED, 1_200, 0, Double.NaN);
        repo.saveAutomationCheckpoint(new AutomationSessionCheckpoint(
                true, "CONTINUOUS", "PAUSED", 1, 3000,
                900, 1_200, "manual verification resolved exposure", "",
                resolved, AutomationUncertainExposure.none()));

        AutomationSessionRecovery afterResolution = repo.loadAutomationRecovery();
        assertTrue(afterResolution.sessionOpen());
        assertFalse(afterResolution.unresolvedExposure());
        assertEquals(1, afterResolution.tradesStarted());
        assertEquals(3000, afterResolution.committedSpend());
    }

    @Test
    void manualResolutionIsTwoStepAuditedAndCannotResetSessionCaps() throws Exception {
        PositionRepository repo = new PositionRepository(db);
        AutomationUncertainExposure uncertain = new AutomationUncertainExposure(
                true, "BUYING", "listing-8", "minecraft:ender_pearl",
                "minecraft:ender_pearl", 16, 4100, 8950,
                "Seller", 1_000);
        repo.saveAutomationCheckpoint(new AutomationSessionCheckpoint(
                true, "CONTINUOUS", "BUYING", 2, 8_200,
                900, 1_100, "uncertain buy", "", null, uncertain));

        AutomationSessionCheckpoint resolved = new AutomationSessionCheckpoint(
                true, "CONTINUOUS", "PAUSED", 3, 12_300,
                900, 1_300, "player externally verified no exposure", "",
                null, AutomationUncertainExposure.none());
        repo.resolveAutomationExposure(new AutomationManualResolution(
                0, 1_200, 1_300, 1_250,
                "Inventory and exact active listing inspected; no owned item/listing found",
                "Player completed second explicit recovery confirmation"), resolved);

        AutomationSessionRecovery recovery = repo.loadAutomationRecovery();
        assertTrue(recovery.sessionOpen());
        assertFalse(recovery.unresolvedExposure());
        assertEquals(3, recovery.tradesStarted());
        assertEquals(12_300, recovery.committedSpend());
        try (var statement = db.connection().createStatement();
             var rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM automation_resolution_audit")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void automationRuntimeIsDurableMonotonicAndResetsOnlyForNewSession() throws Exception {
        PositionRepository repo = new PositionRepository(db);
        repo.saveAutomationCheckpoint(new AutomationSessionCheckpoint(
                true, "CONTINUOUS", "MONITORING", 1, 4_100,
                1_000, 1_500, 5_000, 2_000,
                "active runtime", "", null, AutomationUncertainExposure.none()));

        AutomationRuntimeSummary first = repo.automationRuntimeSummary();
        assertEquals(1_500, first.currentSessionActiveMillis());
        assertEquals(5_000, first.lifetimeActiveMillis());
        assertTrue(first.sessionOpen());
        AutomationSessionRecovery recovered = repo.loadAutomationRecovery();
        assertEquals(1_500, recovered.sessionActiveMillis());
        assertEquals(5_000, recovered.lifetimeActiveMillis());

        // A stale same-session write cannot move either counter backwards.
        repo.saveAutomationCheckpoint(new AutomationSessionCheckpoint(
                true, "CONTINUOUS", "PAUSED", 1, 4_100,
                1_000, 1_000, 4_000, 9_000,
                "stale counters", "", null, AutomationUncertainExposure.none()));
        AutomationRuntimeSummary afterStale = repo.automationRuntimeSummary();
        assertEquals(1_500, afterStale.currentSessionActiveMillis());
        assertEquals(5_000, afterStale.lifetimeActiveMillis());

        // A later session gets a fresh session counter, but lifetime remains
        // monotonic and includes the new active interval.
        repo.saveAutomationCheckpoint(new AutomationSessionCheckpoint(
                true, "SINGLE", "SCANNING", 0, 0,
                10_000, 100, 5_100, 10_100,
                "new session", "", null, AutomationUncertainExposure.none()));
        AutomationRuntimeSummary next = repo.automationRuntimeSummary();
        assertEquals(100, next.currentSessionActiveMillis());
        assertEquals(5_100, next.lifetimeActiveMillis());
        assertEquals(10_000, next.sessionStartedAt());
    }

    @Test
    void loadingRuntimeNeverConvertsFutureOrOfflineWallTimeIntoActiveTime() throws Exception {
        PositionRepository repo = new PositionRepository(db);
        repo.saveAutomationCheckpoint(new AutomationSessionCheckpoint(
                true, "CONTINUOUS", "MONITORING", 1, 4_100,
                1_000, 250, 750, Long.MAX_VALUE,
                "future wall label", "", null, AutomationUncertainExposure.none()));

        AutomationSessionRecovery recovery = repo.loadAutomationRecovery();
        assertEquals(250, recovery.sessionActiveMillis());
        assertEquals(750, recovery.lifetimeActiveMillis());
        assertEquals(250, repo.automationRuntimeSummary().currentSessionActiveMillis());
    }

    @Test
    void balanceHistoryRoundTrips() throws Exception {
        PositionRepository repo = new PositionRepository(db);
        repo.recordBalance(1000, "PAPER", 2_000_000, 0);
        repo.recordBalance(2000, "PAPER", 2_050_000, 3_000);
        List<PositionRepository.BalancePoint> history = repo.balanceHistory("PAPER");
        assertEquals(2, history.size());
        assertEquals(2_050_000, history.get(1).balance());
    }
}
