package dev.doughbay.fabric;

import dev.doughbay.storage.Database;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * How busy the server is, and what that has been worth.
 *
 * <p>The tab list is a packet, not a screen: the client keeps the online
 * players whether or not anybody holds Tab, so the count can be sampled every
 * minute and written to the ledger. Paired with the sales in the same minute,
 * a week of samples says what a crowded server is worth against a quiet one:
 * the model returns a factor, 1.0 at the usual population, above 1 when there
 * are more buyers about than normal and below when the server has emptied.
 *
 * <p>The session uses it the way it uses the demand clock: as a multiplier on
 * a candidate's score and a divisor on its expected hold, so a busy hour buys
 * more freely and a dead one waits.
 */
public final class PopulationModel {
    private static final long SAMPLE_MILLIS = 2 * 60_000L;
    private static final long REBUILD_MILLIS = 10 * 60_000L;
    private static final long WINDOW_MILLIS = 7 * 24 * 3_600_000L;
    /**
     * Buckets of five thousand players across the network; index 0 is 0-4999,
     * index 1 is 5000-9999, and so on to sixty thousand. When the ping fails
     * the shard's own count is used, in buckets of a hundred.
     */
    private static final int NETWORK_BUCKET = 5_000;
    private static final int SHARD_BUCKET = 100;
    private static final int BUCKETS = 12;
    /** The network is pinged once a sample: the population moves slowly, but the evening ramp should not be blurred. */
    private static final long PING_MILLIS = 2 * 60_000L;
    /** A bucket needs this many minutes of history before its number is trusted. */
    private static final int MIN_SAMPLES = 30;

    private final Path databasePath;
    private volatile int players;
    private volatile int network;
    private volatile long pingedAt;
    private volatile long sampledAt;
    private volatile double[] factors = new double[BUCKETS];
    private volatile double salesPerHourNow;
    private volatile String status = "warming up";
    private volatile long rebuiltAt;

    PopulationModel(Path databasePath) {
        this.databasePath = databasePath;
        java.util.Arrays.fill(factors, 1.0);
        Thread t = new Thread(this::loop, "doughbay-population");
        t.setDaemon(true);
        t.start();
    }

    /** Players on this shard right now, or 0 before the first sample. */
    public int players() {
        return players;
    }

    /** Players across the whole network, which is what one auction house serves; 0 when the ping has not answered. */
    public int networkPlayers() {
        return network;
    }

    /** The count the model buckets on: the network when the ping works, this shard otherwise. */
    private int marketPopulation() {
        return network > 0 ? network : players;
    }

    public long sampledAt() {
        return sampledAt;
    }

    public String status() {
        return status;
    }

    /** Sales an hour the ledger saw at this population, over the last week. */
    public double salesPerHourAtThisPopulation() {
        return salesPerHourNow;
    }

    /**
     * How this population trades against the ordinary one: 1.0 when unknown,
     * 1.4 when a server this full has been selling half again as fast, 0.6
     * when it has been slow. Held between 0.4 and 2.0.
     */
    public double factorNow() {
        int b = bucket(marketPopulation());
        if (b < 0) return 1.0;
        return factors[b];
    }

    private int bucket(int count) {
        if (count <= 0) return -1;
        int width = network > 0 ? NETWORK_BUCKET : SHARD_BUCKET;
        return Math.min(BUCKETS - 1, count / width);
    }

    private int bucketWidth() {
        return network > 0 ? NETWORK_BUCKET : SHARD_BUCKET;
    }

    private void loop() {
        long lastRebuild = 0;
        while (true) {
            try {
                Thread.sleep(SAMPLE_MILLIS);
                sample();
                long now = System.currentTimeMillis();
                if (now - lastRebuild >= REBUILD_MILLIS) {
                    lastRebuild = now;
                    rebuild(now);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                status = "population sampling failed: " + e.getMessage();
            }
        }
    }

    /**
     * One row a minute: this shard's tab list, which the client already has,
     * and every few minutes the network's own count from a status ping.
     */
    private void sample() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        ClientPacketListener connection = client.getConnection();
        if (connection == null) return;
        int count = connection.getListedOnlinePlayers().size();
        if (count <= 0) return;
        players = count;
        long now = System.currentTimeMillis();
        if (now - pingedAt >= PING_MILLIS) {
            pingedAt = now;
            net.minecraft.client.multiplayer.ServerData server = client.getCurrentServer();
            String address = server == null ? "" : server.ip;
            if (!address.isBlank()) {
                String host = address;
                int port = 25565;
                int colon = address.lastIndexOf(':');
                if (colon > 0 && colon < address.length() - 1) {
                    try {
                        port = Integer.parseInt(address.substring(colon + 1));
                        host = address.substring(0, colon);
                    } catch (NumberFormatException ignored) {
                        // an address without a port
                    }
                }
                int pinged = ServerPing.players(host, port, 4000);
                if (pinged > 0) network = pinged;
            }
        }
        sampledAt = now;
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "INSERT INTO population (at, players, network) VALUES (?, ?, ?)")) {
            ps.setLong(1, sampledAt);
            ps.setInt(2, count);
            ps.setInt(3, network);
            ps.executeUpdate();
        } catch (Exception e) {
            status = "could not record the population: " + e.getMessage();
        }
    }

    /**
     * The week's samples, bucketed by hundreds of players, each paired with
     * the sales that happened in those same minutes. The factor is a bucket's
     * sales rate against the whole week's rate.
     */
    private void rebuild(long now) {
        long from = now - WINDOW_MILLIS;
        int[] minutes = new int[BUCKETS];
        int[] sales = new int[BUCKETS];
        try (Database db = new Database(databasePath)) {
            List<long[]> samples = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT at, CASE WHEN network > 0 THEN network ELSE players END FROM population WHERE at > ? ORDER BY at")) {
                ps.setLong(1, from);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) samples.add(new long[] {rs.getLong(1), rs.getLong(2)});
                }
            }
            if (samples.size() < MIN_SAMPLES) {
                status = samples.size() + " samples; needs " + MIN_SAMPLES;
                return;
            }
            List<Long> sold = new ArrayList<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT closed_at FROM positions WHERE mode='REAL' AND status='SOLD' AND closed_at > ? ORDER BY closed_at")) {
                ps.setLong(1, from);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) sold.add(rs.getLong(1));
                }
            }
            for (long[] s : samples) {
                int b = bucket((int) s[1]);
                if (b >= 0) minutes[b]++;
            }
            // Each sale belongs to the sample minute it fell in.
            int i = 0;
            for (long at : sold) {
                while (i + 1 < samples.size() && samples.get(i + 1)[0] <= at) i++;
                long[] s = samples.get(i);
                if (Math.abs(at - s[0]) > 5 * SAMPLE_MILLIS) continue;   // the client was away
                int b = bucket((int) s[1]);
                if (b >= 0) sales[b]++;
            }
            int totalMinutes = 0;
            int totalSales = 0;
            for (int b = 0; b < BUCKETS; b++) {
                totalMinutes += minutes[b];
                totalSales += sales[b];
            }
            if (totalMinutes < MIN_SAMPLES || totalSales == 0) {
                status = "not enough sales yet at any population";
                return;
            }
            double overall = (double) totalSales / totalMinutes;
            double[] next = new double[BUCKETS];
            StringBuilder shape = new StringBuilder();
            for (int b = 0; b < BUCKETS; b++) {
                if (minutes[b] < MIN_SAMPLES) {
                    next[b] = 1.0;
                    continue;
                }
                double rate = (double) sales[b] / minutes[b];
                next[b] = Math.max(0.4, Math.min(2.0, rate / overall));
                if (shape.length() > 0) shape.append(' ');
                shape.append(b * bucketWidth()).append('+').append(String.format(java.util.Locale.ROOT, "x%.2f", next[b]));
            }
            factors = next;
            int b = bucket(marketPopulation());
            salesPerHourNow = b >= 0 && minutes[b] >= MIN_SAMPLES ? (double) sales[b] / minutes[b] * 60 : overall * 60;
            rebuiltAt = now;
            status = (network > 0 ? network + " on the network" : players + " on this shard")
                    + " · x" + String.format(java.util.Locale.ROOT, "%.2f", factorNow())
                    + " · " + totalMinutes * 2 + " min sampled" + (shape.length() == 0 ? "" : " · " + shape);
        } catch (Exception e) {
            status = "population model failed: " + e.getMessage();
        }
    }

    public long rebuiltAt() {
        return rebuiltAt;
    }
}
