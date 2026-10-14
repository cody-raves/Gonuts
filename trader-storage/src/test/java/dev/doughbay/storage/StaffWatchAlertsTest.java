package dev.doughbay.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class StaffWatchAlertsTest {
    @TempDir Path directory;

    @Test void upgradesExistingV16LedgerWithoutLosingHistory() throws Exception {
        Path file = directory.resolve("existing-v16.db");
        try (var db = new Database(file); var statement = db.connection().createStatement()) {
            statement.execute("DROP TABLE staff_watch_alerts");
            statement.execute("DROP TABLE position_id_allocator");
            statement.execute("DELETE FROM schema_version WHERE version > 16");
            statement.execute("INSERT INTO evasion_events(at,client,who,action) VALUES (50,'Bot','Player','existing escape')");
        }
        // This is the production upgrade path that fresh-database tests missed.
        for (int reopen = 0; reopen < 2; reopen++) {
            try (var db = new Database(file); var statement = db.connection().createStatement()) {
                assertTrue(StaffWatchAlerts.pending(db.connection()).isEmpty());
                try (var rows = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                    assertTrue(rows.next());
                    assertEquals(19, rows.getInt(1));
                }
                assertTrue(PositionIdAllocator.reserve(db.connection(), 1) >= PositionIdAllocator.FIRST_ID);
                try (var rows = statement.executeQuery("SELECT action FROM evasion_events WHERE at=50")) {
                    assertTrue(rows.next());
                    assertEquals("existing escape", rows.getString(1));
                }
                StaffWatchAlerts.record(db.connection(), 100 + reopen, "Bot", "Staff", "disconnected");
                var pending = StaffWatchAlerts.pending(db.connection());
                assertEquals(1, pending.size());
                StaffWatchAlerts.delivered(db.connection(), pending.getFirst().id(), 200 + reopen);
            }
        }
    }

    @Test void upgradingV16ThatAlreadyHasQueuePreservesPendingAndDeliveredRows() throws Exception {
        Path file = directory.resolve("v16-with-queue.db");
        long deliveredId;
        try (var db = new Database(file); var statement = db.connection().createStatement()) {
            StaffWatchAlerts.record(db.connection(), 100, "Bot", "StaffA", "disconnected");
            deliveredId = StaffWatchAlerts.pending(db.connection()).getFirst().id();
            StaffWatchAlerts.delivered(db.connection(), deliveredId, 200);
            StaffWatchAlerts.record(db.connection(), 300, "Bot", "StaffB", "disconnected");
            statement.execute("DELETE FROM schema_version WHERE version > 16");
        }
        try (var db = new Database(file); var statement = db.connection().createStatement()) {
            var pending = StaffWatchAlerts.pending(db.connection());
            assertEquals(1, pending.size());
            assertEquals("StaffB", pending.getFirst().who());
            try (var rows = statement.executeQuery("SELECT delivered_at FROM staff_watch_alerts WHERE id=" + deliveredId)) {
                assertTrue(rows.next());
                assertEquals(200, rows.getLong(1));
            }
        }
    }

    @Test void twoClientsReachSharedEvasionHistoryAndDurableDiscordQueue() throws Exception {
        Path file = directory.resolve("hive.db");
        try (var first = new Database(file)) {
            StaffWatchAlerts.record(first.connection(), 100, "FirstBot", "StaffA", "Staff Watch: disconnected");
        }
        try (var second = new Database(file)) {
            StaffWatchAlerts.record(second.connection(), 200, "SecondBot", "StaffB", "Staff Watch: disconnected");
            var alerts = StaffWatchAlerts.pending(second.connection());
            assertEquals(2, alerts.size());
            assertEquals("FirstBot", alerts.getFirst().client());
            try (var statement = second.connection().createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM evasion_events WHERE action LIKE 'Staff Watch:%'")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            StaffWatchAlerts.delivered(second.connection(), alerts.getFirst().id(), 300);
        }
        // A failed/unsent message survives reopening; the confirmed message is not replayed.
        try (var host = new Database(file)) {
            var remaining = StaffWatchAlerts.pending(host.connection());
            assertEquals(1, remaining.size());
            assertEquals("StaffB", remaining.getFirst().who());
        }
    }

    @Test void historyAndNotificationInsertAreAtomic() throws Exception {
        try (var db = Database.inMemory()) {
            try (var statement = db.connection().createStatement()) {
                statement.execute("DROP TABLE staff_watch_alerts");
            }
            assertThrows(java.sql.SQLException.class, () ->
                    StaffWatchAlerts.record(db.connection(), 100, "Bot", "Staff", "disconnected"));
            try (var statement = db.connection().createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM evasion_events")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            assertTrue(db.connection().getAutoCommit());
        }
    }
}
