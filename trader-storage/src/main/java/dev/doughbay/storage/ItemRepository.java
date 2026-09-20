package dev.doughbay.storage;

import dev.doughbay.core.model.ItemFingerprint;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Catalog of observed markets and their commodity eligibility. */
public final class ItemRepository {

    private final Database db;

    public ItemRepository(Database db) {
        this.db = db;
    }

    public void upsertSeen(ItemFingerprint fingerprint, boolean commodityEligible, long seenAt)
            throws SQLException {
        String sql = """
                INSERT INTO items (item_key, item_id, display_name, metadata_json,
                                   commodity_eligible, first_seen, last_seen)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(item_key) DO UPDATE SET
                    last_seen = MAX(last_seen, excluded.last_seen),
                    commodity_eligible = excluded.commodity_eligible""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, fingerprint.itemKey());
            ps.setString(2, fingerprint.itemId());
            ps.setString(3, fingerprint.displayName());
            ps.setString(4, fingerprint.canonicalForm());
            ps.setInt(5, commodityEligible ? 1 : 0);
            ps.setLong(6, seenAt);
            ps.setLong(7, seenAt);
            ps.executeUpdate();
        }
    }
}
