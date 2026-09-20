package dev.doughbay.storage;

import dev.doughbay.core.model.MarketStats;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Latest computed stats per (item_key, stack_bucket). */
public final class MarketStatsRepository {

    private final Database db;

    public MarketStatsRepository(Database db) {
        this.db = db;
    }

    public void upsert(MarketStats s) throws SQLException {
        String sql = """
                INSERT INTO market_stats
                (item_key, stack_bucket, window_millis, sample_count, outlier_count, unique_sellers,
                 newest_sale_at, lower_bound, quick_sale_price, weighted_median, patient_sale_price,
                 upper_bound, sales_per_hour, robust_volatility, trend, confidence, calculated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(item_key, stack_bucket) DO UPDATE SET
                    window_millis=excluded.window_millis, sample_count=excluded.sample_count,
                    outlier_count=excluded.outlier_count, unique_sellers=excluded.unique_sellers,
                    newest_sale_at=excluded.newest_sale_at, lower_bound=excluded.lower_bound,
                    quick_sale_price=excluded.quick_sale_price, weighted_median=excluded.weighted_median,
                    patient_sale_price=excluded.patient_sale_price, upper_bound=excluded.upper_bound,
                    sales_per_hour=excluded.sales_per_hour, robust_volatility=excluded.robust_volatility,
                    trend=excluded.trend, confidence=excluded.confidence,
                    calculated_at=excluded.calculated_at""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, s.itemKey());
            ps.setString(2, s.bucket().label());
            ps.setLong(3, s.windowMillis());
            ps.setInt(4, s.sampleCount());
            ps.setInt(5, s.outlierCount());
            ps.setInt(6, s.uniqueSellers());
            ps.setLong(7, s.newestSaleAt());
            setNullableDouble(ps, 8, s.lowerBound());
            setNullableDouble(ps, 9, s.quickSalePrice());
            setNullableDouble(ps, 10, s.weightedMedian());
            setNullableDouble(ps, 11, s.patientSalePrice());
            setNullableDouble(ps, 12, s.upperBound());
            ps.setDouble(13, s.salesPerHour());
            ps.setDouble(14, s.robustVolatility());
            ps.setDouble(15, s.trend());
            ps.setDouble(16, s.confidence());
            ps.setLong(17, s.calculatedAt());
            ps.executeUpdate();
        }
    }

    private static void setNullableDouble(PreparedStatement ps, int index, double value)
            throws SQLException {
        if (Double.isNaN(value)) {
            ps.setNull(index, java.sql.Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }
}
