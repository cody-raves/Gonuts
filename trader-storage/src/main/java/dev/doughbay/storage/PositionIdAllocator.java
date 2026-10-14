package dev.doughbay.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Durable leases shared by every updated writer of a ledger. Never called on the render thread. */
final class PositionIdAllocator {
    // Separate from legacy millisecond IDs, still exactly representable by JSON/JavaScript numbers.
    static final long FIRST_ID = 1L << 50;
    private static final long LAST_ID = (1L << 53) - 1;

    static long reserve(Connection connection, int count) throws SQLException {
        if (count <= 0 || count > 8192) throw new IllegalArgumentException("invalid position ID lease size");
        // This single write acquires SQLite's writer lock before looking at the existing high-water mark.
        // RETURNING is fully consumed/closed before returning; autocommit durably burns the whole lease.
        try (var statement = connection.prepareStatement("""
                UPDATE position_id_allocator
                SET next_id = MAX(next_id, COALESCE((SELECT MAX(position_id) + 1 FROM positions), ?)) + ?
                WHERE singleton_id = 1
                  AND MAX(next_id, COALESCE((SELECT MAX(position_id) + 1 FROM positions), ?)) <= ?
                RETURNING next_id - ?
                """)) {
            statement.setLong(1, FIRST_ID);
            statement.setInt(2, count);
            statement.setLong(3, FIRST_ID);
            statement.setLong(4, LAST_ID - count + 1);
            statement.setInt(5, count);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Position ID space is exhausted");
                long first = rows.getLong(1);
                if (rows.next()) throw new SQLException("Invalid position ID allocator rows");
                return first;
            }
        }
    }
}
