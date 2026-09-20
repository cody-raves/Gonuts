package dev.doughbay.storage;

import dev.doughbay.core.model.Sale;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Completed transactions. Inserts are idempotent on transaction_hash so
 * overlapping API pages never create duplicates.
 */
public final class TransactionRepository {

    private final Database db;

    public TransactionRepository(Database db) {
        this.db = db;
    }

    /** @return true when the row was new, false when it was a duplicate. */
    public boolean insertIfAbsent(Sale sale, String rawJson) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO transactions
                (transaction_hash, sold_at, seller_uuid, seller_name, item_key, item_id,
                 item_count, total_price, unit_price, raw_json)
                VALUES (?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, sale.transactionHash());
            ps.setLong(2, sale.soldAt());
            ps.setString(3, sale.sellerUuid());
            ps.setString(4, sale.sellerName());
            ps.setString(5, sale.itemKey());
            ps.setString(6, sale.itemId());
            ps.setInt(7, sale.itemCount());
            ps.setLong(8, sale.totalPrice());
            ps.setDouble(9, sale.unitPrice());
            ps.setString(10, rawJson);
            return ps.executeUpdate() > 0;
        }
    }

    public List<Sale> findByItemKeySince(String itemKey, long since) throws SQLException {
        String sql = """
                SELECT transaction_hash, sold_at, seller_uuid, seller_name, item_key, item_id,
                       item_count, total_price
                FROM transactions
                WHERE item_key = ? AND sold_at >= ? AND is_outlier = 0
                ORDER BY sold_at""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, itemKey);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                return readSales(rs);
            }
        }
    }

    /**
     * Completed-sale counts per market, newest window only.
     *
     * <p>Aggregated in SQL on purpose: this answers "which markets are worth
     * computing" from an index without materialising a single Sale, so the
     * caller can bound its work before touching the rows themselves.
     */
    public Map<String, Integer> countByItemKeySince(long since) throws SQLException {
        String sql = """
                SELECT item_key, COUNT(*) AS sales
                FROM transactions
                WHERE sold_at >= ? AND is_outlier = 0
                GROUP BY item_key""";
        Map<String, Integer> counts = new HashMap<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    counts.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return counts;
    }

    /**
     * Completed-sale counts per market broken out by hour of day (UTC), newest
     * window only. Each value is a length-24 array, index 0 = UTC 00:00, giving
     * a market's daily shape so selection can weight the current hour.
     *
     * <p>Aggregated in SQL like {@link #countByItemKeySince}, for the same
     * reason: the shape falls out of an index without materialising any Sale.
     */
    public Map<String, int[]> hourOfDaySalesByItem(long since) throws SQLException {
        String sql = """
                SELECT item_key,
                       CAST(strftime('%H', sold_at / 1000, 'unixepoch') AS INTEGER) AS hour,
                       COUNT(*) AS sales
                FROM transactions
                WHERE sold_at >= ? AND is_outlier = 0
                GROUP BY item_key, hour""";
        Map<String, int[]> out = new HashMap<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String item = rs.getString(1);
                    int hour = rs.getInt(2);
                    int sales = rs.getInt(3);
                    if (item == null || hour < 0 || hour > 23) continue;
                    out.computeIfAbsent(item, k -> new int[24])[hour] = sales;
                }
            }
        }
        return out;
    }

    /**
     * The most recent {@code limit} sales for one market, oldest first.
     *
     * <p>A busy market can hold hundreds of thousands of sales inside the
     * analysis window, and loading all of them to compute percentiles is what
     * turns a growing archive into an out-of-memory failure. Truncating to the
     * newest rows is safe because valuation is recency-weighted: with a
     * six-hour half-life, a sale from days ago carries a weight in the
     * billionths and cannot move a percentile.
     */
    public List<Sale> findRecentByItemKeySince(String itemKey, long since, int limit)
            throws SQLException {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        String sql = """
                SELECT transaction_hash, sold_at, seller_uuid, seller_name, item_key, item_id,
                       item_count, total_price
                FROM transactions
                WHERE item_key = ? AND sold_at >= ? AND is_outlier = 0
                ORDER BY sold_at DESC
                LIMIT ?""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, itemKey);
            ps.setLong(2, since);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<Sale> newestFirst = readSales(rs);
                // Every other read hands back oldest-first; keep that contract
                // so callers cannot silently depend on the ordering flipping.
                Collections.reverse(newestFirst);
                return newestFirst;
            }
        }
    }

    public List<Sale> findAllSince(long since) throws SQLException {
        String sql = """
                SELECT transaction_hash, sold_at, seller_uuid, seller_name, item_key, item_id,
                       item_count, total_price
                FROM transactions WHERE sold_at >= ? AND is_outlier = 0 ORDER BY sold_at""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                return readSales(rs);
            }
        }
    }

    /** Outliers are flagged, never deleted. */
    public void markOutliers(Collection<String> transactionHashes) throws SQLException {
        String sql = "UPDATE transactions SET is_outlier = 1 WHERE transaction_hash = ?";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            for (String hash : transactionHashes) {
                ps.setString(1, hash);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public long count() throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM transactions");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public long countDistinctMarkets() throws SQLException {
        try (PreparedStatement ps = db.connection()
                .prepareStatement("SELECT COUNT(DISTINCT item_key) FROM transactions");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public long countOutliers() throws SQLException {
        try (PreparedStatement ps = db.connection()
                .prepareStatement("SELECT COUNT(*) FROM transactions WHERE is_outlier = 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** [oldest, newest] sold_at, or null when the table is empty. */
    public long[] soldAtRange() throws SQLException {
        try (PreparedStatement ps = db.connection()
                .prepareStatement("SELECT MIN(sold_at), MAX(sold_at) FROM transactions");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getLong(1) != 0) {
                return new long[]{rs.getLong(1), rs.getLong(2)};
            }
            return null;
        }
    }

    private static List<Sale> readSales(ResultSet rs) throws SQLException {
        List<Sale> sales = new ArrayList<>();
        while (rs.next()) {
            sales.add(new Sale(
                    rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getInt(7), rs.getLong(8)));
        }
        return sales;
    }
}
