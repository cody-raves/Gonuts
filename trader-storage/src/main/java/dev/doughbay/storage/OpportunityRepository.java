package dev.doughbay.storage;

import dev.doughbay.core.model.Opportunity;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Audit log of every detected opportunity. */
public final class OpportunityRepository {

    private final Database db;

    public OpportunityRepository(Database db) {
        this.db = db;
    }

    public void insert(Opportunity o, long detectedAt) throws SQLException {
        String sql = """
                INSERT INTO opportunities
                (detected_at, listing_key, item_key, item_count, buy_price, recommended_sell_price,
                 expected_profit, expected_roi, estimated_hold_hours, sale_probability,
                 confidence, score, reasons, status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, 'DETECTED')""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, detectedAt);
            ps.setString(2, o.listing().listingKey());
            ps.setString(3, o.listing().itemKey());
            ps.setInt(4, o.listing().itemCount());
            ps.setLong(5, o.buyPrice());
            ps.setLong(6, o.recommendedSellPrice());
            ps.setDouble(7, o.expectedNetProfit());
            ps.setDouble(8, o.expectedRoiPercent());
            ps.setDouble(9, o.estimatedHoldHours());
            ps.setDouble(10, o.saleProbability());
            ps.setDouble(11, o.confidence());
            ps.setDouble(12, o.score());
            ps.setString(13, String.join(" | ", o.reasons()));
            ps.executeUpdate();
        }
    }
}
