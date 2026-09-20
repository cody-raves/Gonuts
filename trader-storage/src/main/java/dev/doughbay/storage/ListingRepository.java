package dev.doughbay.storage;

import dev.doughbay.core.model.Listing;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Point-in-time snapshots of active listings. */
public final class ListingRepository {

    private final Database db;

    public ListingRepository(Database db) {
        this.db = db;
    }

    public void insertSnapshot(Listing listing, String rawJson) throws SQLException {
        String sql = """
                INSERT INTO listing_snapshots
                (observed_at, listing_key, seller_uuid, seller_name, item_key, item_id,
                 item_count, total_price, unit_price, time_left, raw_json)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, listing.observedAt());
            ps.setString(2, listing.listingKey());
            ps.setString(3, listing.sellerUuid());
            ps.setString(4, listing.sellerName());
            ps.setString(5, listing.itemKey());
            ps.setString(6, listing.itemId());
            ps.setInt(7, listing.itemCount());
            ps.setLong(8, listing.totalPrice());
            ps.setDouble(9, listing.unitPrice());
            if (listing.timeLeftMillis() == null) {
                ps.setNull(10, java.sql.Types.INTEGER);
            } else {
                ps.setLong(10, listing.timeLeftMillis());
            }
            ps.setString(11, rawJson);
            ps.executeUpdate();
        }
    }

    /**
     * The most recent snapshot of each listing for an item, observed at or
     * after {@code since}. Approximates the current order book.
     */
    /**
     * Throws away snapshots older than the horizon, and says how many went.
     *
     * <p>Every listing the feed returns is written down on every sweep, and
     * nothing has ever deleted one: twenty-six million rows in eight days,
     * fourteen of the sixteen gigabytes in the file, growing a couple of
     * gigabytes a day for ever.
     *
     * <p>Nobody reads the old ones. Every query against this table filters on
     * {@code observed_at} being recent - the rival tracker wants what somebody
     * listed lately, the charts want the last stretch - so the far end of it
     * is not history anybody consults, it is exhaust. It is also the feed the
     * trading side explicitly does not trust for decisions, which the sales
     * table serves instead.
     *
     * <p>SQLite does not give the space back on a delete; the pages are freed
     * for reuse but the file stays the size it was. A {@code VACUUM} after a
     * large prune is what actually shrinks it, and that rewrites the whole
     * database, so it is worth doing once rather than after every sweep.
     */
    public int pruneSnapshotsBefore(long horizonMillis) throws SQLException {
        try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                "DELETE FROM listing_snapshots WHERE observed_at < ?")) {
            ps.setLong(1, horizonMillis);
            return ps.executeUpdate();
        }
    }

    /** The oldest snapshot still kept, or 0 when the table is empty. */
    public long oldestSnapshotAt() throws SQLException {
        try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                "SELECT MIN(observed_at) FROM listing_snapshots");
             java.sql.ResultSet r = ps.executeQuery()) {
            return r.next() ? r.getLong(1) : 0L;
        }
    }

    public List<Listing> latestForItem(String itemKey, long since) throws SQLException {
        String sql = """
                SELECT s.observed_at, s.listing_key, s.seller_uuid, s.seller_name, s.item_key,
                       s.item_id, s.item_count, s.total_price, s.time_left
                FROM listing_snapshots s
                JOIN (SELECT listing_key, MAX(observed_at) AS latest
                      FROM listing_snapshots
                      WHERE item_key = ? AND observed_at >= ?
                      GROUP BY listing_key) m
                  ON s.listing_key = m.listing_key AND s.observed_at = m.latest
                WHERE s.item_key = ?
                ORDER BY s.unit_price""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, itemKey);
            ps.setLong(2, since);
            ps.setString(3, itemKey);
            try (ResultSet rs = ps.executeQuery()) {
                List<Listing> listings = new ArrayList<>();
                while (rs.next()) {
                    long timeLeft = rs.getLong(9);
                    boolean timeLeftNull = rs.wasNull();
                    listings.add(new Listing(
                            rs.getString(2), rs.getLong(1), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getInt(7), rs.getLong(8),
                            timeLeftNull ? null : timeLeft));
                }
                return listings;
            }
        }
    }
}
