package dev.doughbay.storage;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Full raw API page payloads, for replaying parses after API changes. */
public final class RawArchiveRepository {

    private final Database db;

    public RawArchiveRepository(Database db) {
        this.db = db;
    }

    public void archive(long fetchedAt, String endpoint, String rawJson) throws SQLException {
        String sql = "INSERT INTO raw_pages (fetched_at, endpoint, raw_json) VALUES (?,?,?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, fetchedAt);
            ps.setString(2, endpoint);
            ps.setString(3, rawJson);
            ps.executeUpdate();
        }
    }
}
