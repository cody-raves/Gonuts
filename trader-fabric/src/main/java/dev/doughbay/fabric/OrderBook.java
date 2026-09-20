package dev.doughbay.fabric;

import dev.doughbay.storage.Database;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The order house as last read: every open buy order with the parts it
 * demands, its price per item and what it still wants. Kept in memory for
 * the session and the Orders tab, and written to the ledger so the history
 * of what buyers were paying survives, enchanted gear included.
 */
public final class OrderBook {
    /** One buy order. {@code remaining} is what it still wants; {@code parts} the tooltip's requirement lines. */
    public record Order(long observedAt, String itemId, String itemKey, String descriptorJson, List<String> parts,
                        long unitPrice, long delivered, long total, int page) {
        public long remaining() {
            return Math.max(0, total - delivered);
        }

        public long value() {
            return remaining() * unitPrice;
        }
    }

    private final Path databasePath;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doughbay-order-book");
        t.setDaemon(true);
        return t;
    });
    private volatile List<Order> orders = List.of();
    private volatile long readAt;
    private volatile int pagesRead;
    private volatile String status = "not read yet";

    private volatile long lastFullAt;
    private final Path readStore;

    OrderBook(Path databasePath) {
        this.databasePath = databasePath;
        this.readStore = databasePath.resolveSibling("orders-read.txt");
        load();
    }

    /** When the whole house was last swept, or 0. */
    public long lastFullAt() {
        return lastFullAt;
    }

    /**
     * The book as the last session left it: every order seen in the last hour
     * comes back from the ledger, with the read times, so a relaunch carries
     * on the schedule instead of sweeping two hundred pages again.
     */
    private void load() {
        long now = System.currentTimeMillis();
        try {
            if (java.nio.file.Files.exists(readStore)) {
                for (String line : java.nio.file.Files.readAllLines(readStore)) {
                    String[] kv = line.strip().split("=", 2);
                    if (kv.length != 2) continue;
                    if (kv[0].equals("readAt")) readAt = Long.parseLong(kv[1].trim());
                    if (kv[0].equals("fullAt")) lastFullAt = Long.parseLong(kv[1].trim());
                    if (kv[0].equals("pages")) pagesRead = Integer.parseInt(kv[1].trim());
                }
            }
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay could not read the order book times: {}", e.toString());
        }
        if (readAt <= 0 || now - readAt >= KEEP_MILLIS) {
            readAt = 0;
            return;
        }
        java.util.LinkedHashMap<String, Order> merged = new java.util.LinkedHashMap<>();
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT observed_at, item_id, item_key, descriptor_json, parts, unit_price, delivered, total, page "
                             + "FROM orders WHERE observed_at > ? ORDER BY observed_at")) {
            ps.setLong(1, now - KEEP_MILLIS);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String parts = rs.getString(5);
                    Order o = new Order(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            parts == null || parts.isEmpty() ? List.of() : List.of(parts.split(";")),
                            rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getInt(9));
                    merged.put(o.itemKey() + "|" + String.join(";", o.parts()) + "|" + o.unitPrice() + "|" + o.total(), o);
                }
            }
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay could not reload the order book: {}", e.toString());
            readAt = 0;
            return;
        }
        orders = List.copyOf(merged.values());
        status = orders.size() + " open from the last read " + (now - readAt) / 60_000 + " min ago";
        DoughBayClient.LOGGER.info("DoughBay order book: {} order(s) reloaded from the read {} min ago", orders.size(), (now - readAt) / 60_000);
    }

    private void saveTimes() {
        try {
            java.nio.file.Files.writeString(readStore, String.join(System.lineSeparator(), "readAt=" + readAt, "fullAt=" + lastFullAt, "pages=" + pagesRead, ""));
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay could not save the order book times: {}", e.toString());
        }
    }

    public List<Order> orders() {
        return orders;
    }

    public long readAt() {
        return readAt;
    }

    public int pagesRead() {
        return pagesRead;
    }

    public String status() {
        return status;
    }

    /** Orders seen this long ago are still assumed open unless a newer read says otherwise. */
    private static final long KEEP_MILLIS = 60 * 60_000L;

    /**
     * A completed read merges into the book: an order seen again is replaced
     * by its newer state, orders not seen for an hour drop out. A read of the
     * newest pages therefore accumulates a picture of the whole house.
     */
    public void record(List<Order> read, int pages, long now) {
        record(read, pages, now, false);
    }

    public void record(List<Order> read, int pages, long now, boolean full) {
        if (full) lastFullAt = now;
        java.util.LinkedHashMap<String, Order> merged = new java.util.LinkedHashMap<>();
        for (Order o : orders) {
            if (now - o.observedAt() < KEEP_MILLIS) merged.put(o.itemKey() + "|" + String.join(";", o.parts()) + "|" + o.unitPrice() + "|" + o.total(), o);
        }
        for (Order o : read) merged.put(o.itemKey() + "|" + String.join(";", o.parts()) + "|" + o.unitPrice() + "|" + o.total(), o);
        orders = List.copyOf(merged.values());
        readAt = now;
        pagesRead = pages;
        status = read.size() + " read on " + pages + " page(s), " + orders.size() + " open in the last hour";
        saveTimes();
        writer.submit(() -> {
            // One transaction per thousand rows: nine thousand separate commits
            // held the file long enough to lock the bot's own ledger writes out.
            try (Database db = new Database(databasePath);
                 PreparedStatement ps = db.connection().prepareStatement(
                         "INSERT INTO orders (observed_at, item_id, item_key, descriptor_json, parts, unit_price, "
                                 + "delivered, total, page) VALUES (?,?,?,?,?,?,?,?,?)")) {
                db.connection().setAutoCommit(false);
                int pending = 0;
                for (Order o : read) {
                    ps.setLong(1, o.observedAt());
                    ps.setString(2, o.itemId());
                    ps.setString(3, o.itemKey());
                    ps.setString(4, o.descriptorJson());
                    ps.setString(5, String.join(";", o.parts()));
                    ps.setLong(6, o.unitPrice());
                    ps.setLong(7, o.delivered());
                    ps.setLong(8, o.total());
                    ps.setInt(9, o.page());
                    ps.addBatch();
                    if (++pending >= 1000) {
                        ps.executeBatch();
                        db.connection().commit();
                        pending = 0;
                    }
                }
                ps.executeBatch();
                db.connection().commit();
            } catch (Exception e) {
                DoughBayClient.LOGGER.warn("DoughBay could not record the order book: {}", e.toString());
            }
        });
    }

    /** An order the page no longer shows: gone from the book until a read sees it again. */
    public void drop(Order gone) {
        if (gone == null) return;
        java.util.List<Order> kept = new java.util.ArrayList<>();
        for (Order o : orders) {
            if (o.itemKey().equals(gone.itemKey()) && o.unitPrice() == gone.unitPrice() && o.parts().equals(gone.parts())) continue;
            kept.add(o);
        }
        orders = List.copyOf(kept);
    }

    public void noteFailure(String detail) {
        status = "read failed: " + detail;
    }
}
