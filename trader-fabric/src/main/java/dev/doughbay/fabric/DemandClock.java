package dev.doughbay.fabric;

import dev.doughbay.core.model.StackBucket;
import dev.doughbay.storage.Database;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * When each market actually sells. Every completed sale in the ledger has a
 * timestamp, so each market gets its own 24-hour curve: sales per hour of
 * the day over the last week, expressed as a factor against that market's
 * own average. 1.0 is an ordinary hour, 2.0 is the market's peak, 0.3 is
 * a dead hour when its buyers are asleep. The session ranks candidates by
 * the factor for the current hour, stretches its hold estimate by it, and
 * leaves a market alone in its dead hours: money parked there waits for
 * buyers who are not coming until later.
 *
 * <p>Rebuilt every ten minutes on a background thread from the local ledger.
 */
public final class DemandClock {
    private static final long REFRESH_MILLIS = 10 * 60_000L;
    private static final long WINDOW_MILLIS = 7 * 24 * 3_600_000L;
    /** A market needs this many sales in the window before its curve is trusted. */
    private static final int MIN_SALES = 40;

    private final Path databasePath;
    private volatile Map<String, double[]> curves = Map.of();
    private volatile long refreshedAt;

    DemandClock(Path databasePath) {
        this.databasePath = databasePath;
        Thread thread = new Thread(this::loop, "doughbay-demand-clock");
        thread.setDaemon(true);
        thread.start();
    }

    public long refreshedAt() {
        return refreshedAt;
    }

    public int markets() {
        return curves.size();
    }

    /**
     * The demand factor for a market right now, or 1.0 when the market has
     * too little history to say. Never below 0.1 nor above 3.0.
     */
    public double factorNow(String itemId, int count) {
        double[] curve = curves.get(key(itemId, count));
        if (curve == null) return 1.0;
        int hour = Instant.now().atZone(ZoneId.systemDefault()).getHour();
        return curve[hour];
    }

    /** The whole curve, for a chart; null when unknown. */
    public double[] curve(String itemId, int count) {
        return curves.get(key(itemId, count));
    }

    static String key(String itemId, int count) {
        return itemId + "|" + StackBucket.of(Math.max(1, count)).name();
    }

    private void loop() {
        while (true) {
            try {
                refresh();
            } catch (Throwable t) {
                DoughBayClient.LOGGER.warn("DoughBay demand clock failed: {}", t.toString());
            }
            try {
                Thread.sleep(REFRESH_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void refresh() throws Exception {
        long now = System.currentTimeMillis();
        Map<String, int[]> counts = new HashMap<>();
        ZoneId zone = ZoneId.systemDefault();
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT item_key, item_count, sold_at FROM transactions WHERE sold_at > ? AND is_outlier = 0")) {
            ps.setLong(1, now - WINDOW_MILLIS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String itemKey = rs.getString(1);
                    int hash = itemKey.indexOf('#');
                    String itemId = hash >= 0 ? itemKey.substring(0, hash) : itemKey;
                    int hour = Instant.ofEpochMilli(rs.getLong(3)).atZone(zone).getHour();
                    counts.computeIfAbsent(key(itemId, rs.getInt(2)), k -> new int[24])[hour]++;
                }
            }
        }
        Map<String, double[]> built = new HashMap<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            int total = 0;
            for (int n : c) total += n;
            if (total < MIN_SALES) continue;
            double average = total / 24.0;
            double[] curve = new double[24];
            for (int h = 0; h < 24; h++) {
                // Smoothed over the neighbouring hours so one quiet bin does
                // not read as a dead hour.
                double smoothed = (c[(h + 23) % 24] + 2.0 * c[h] + c[(h + 1) % 24]) / 4.0;
                curve[h] = Math.max(0.1, Math.min(3.0, smoothed / average));
            }
            built.put(e.getKey(), curve);
        }
        curves = Map.copyOf(built);
        refreshedAt = now;
        DoughBayClient.LOGGER.info("DoughBay demand clock: {} market curves from the last 7 days", built.size());
    }
}
