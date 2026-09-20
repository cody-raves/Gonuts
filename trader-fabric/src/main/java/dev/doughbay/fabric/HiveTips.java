package dev.doughbay.fabric;

import dev.doughbay.storage.Database;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * The hive's tip board: a deal one client found but cannot take is handed to
 * whichever sibling can.
 *
 * <p>Each client hunts for itself off the shared feed, so most deals both see
 * on their own. The gap this closes is the one that is wasted: a client pinned
 * at its slot cap, or out of cash, keeps finding good buys it has no room or
 * money to make. Rather than let those pass, it posts them here, and a sibling
 * with a free slot and the cash grabs them.
 *
 * <p>Because a client only ever posts what it will not act on itself, the two
 * never chase the same deal - the poster has already stepped aside, so there is
 * no race to the bottom on price. The rich-but-full client becomes the eyes;
 * the one with hands to spare does the buying.
 *
 * <p>Tips go stale in minutes - an auction listing is bought or gone - so they
 * carry a short life and old ones are ignored and swept. Off unless {@code
 * hive.share_deals} is on, and never without the shared key that a hive needs
 * to mean anything.
 */
public final class HiveTips {

    private final Path databasePath;
    private volatile long sweptAt;

    public HiveTips(Path databasePath) {
        this.databasePath = databasePath;
    }

    private static boolean enabled() {
        return DoughBayClient.multiClient() && Tuning.get("hive.share_deals") >= 0.5;
    }

    private static String me() {
        try {
            String n = Minecraft.getInstance().getUser().getName();
            return n == null ? "" : n;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static long ttlMillis() {
        long sec = (long) Tuning.get("hive.tip_ttl_sec");
        return (sec > 0 ? sec : 90) * 1000L;
    }

    /** A deal for the hive: the item, the stack, and what it looked worth. */
    public record Tip(long tipId, String itemKey, int itemCount, long buyPrice,
                      long sellPrice, long profit, String postedBy, long postedAt) {
    }

    /**
     * Offers a deal this client will not take to the hive.
     *
     * <p>Idempotent within the life of a tip: the same item and stack is not
     * posted twice while an earlier one is still open, so a client scanning the
     * same market every few seconds does not flood the board.
     */
    public void post(String itemKey, int itemCount, long buyPrice, long sellPrice, long profit) {
        if (!enabled() || itemKey == null || itemKey.isBlank() || profit <= 0) return;
        String me = me();
        if (me.isEmpty()) return;
        long now = System.currentTimeMillis();
        try (Database db = new Database(databasePath)) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT COUNT(*) FROM hive_tips WHERE item_key=? AND item_count=? "
                            + "AND status='OPEN' AND posted_at > ?")) {
                ps.setString(1, itemKey);
                ps.setInt(2, itemCount);
                ps.setLong(3, now - ttlMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) return;   // already on the board
                }
            }
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "INSERT INTO hive_tips (item_key, item_count, buy_price, sell_price, profit, "
                            + "posted_by, posted_at, status) VALUES (?,?,?,?,?,?,?,'OPEN')")) {
                ps.setString(1, itemKey);
                ps.setInt(2, itemCount);
                ps.setLong(3, buyPrice);
                ps.setLong(4, sellPrice);
                ps.setLong(5, profit);
                ps.setString(6, me);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            DoughBayClient.LOGGER.info("DoughBay hive: tipped the others on {} x{} (buy {}, ~{} profit)",
                    itemKey, itemCount, buyPrice, profit);
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay hive could not post a tip: {}", e.toString());
        }
    }

    /**
     * The freshest open tips from somebody else, best profit first.
     *
     * <p>Never this client's own tips - it would not have posted a deal it
     * could take - and never a stale one.
     */
    public List<Tip> open() {
        if (!enabled()) return List.of();
        String me = me();
        if (me.isEmpty()) return List.of();
        long cutoff = System.currentTimeMillis() - ttlMillis();
        List<Tip> out = new ArrayList<>();
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT tip_id, item_key, item_count, buy_price, sell_price, profit, posted_by, posted_at "
                             + "FROM hive_tips WHERE status='OPEN' AND posted_by<>? AND posted_at > ? "
                             + "ORDER BY profit DESC LIMIT 20")) {
            ps.setString(1, me);
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Tip(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4),
                            rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getLong(8)));
                }
            }
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay hive could not read the tip board: {}", e.toString());
        }
        return out;
    }

    /**
     * Takes a tip off the board so no other sibling also acts on it.
     *
     * <p>Atomic: the update only touches a row still OPEN, so two clients that
     * read the same tip cannot both claim it - the second update changes
     * nothing and returns false, and that client moves on to the next.
     */
    public boolean claim(long tipId) {
        if (!enabled() || tipId <= 0) return false;
        String me = me();
        if (me.isEmpty()) return false;
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "UPDATE hive_tips SET status='CLAIMED', claimed_by=? WHERE tip_id=? AND status='OPEN'")) {
            ps.setString(1, me);
            ps.setLong(2, tipId);
            boolean took = ps.executeUpdate() == 1;
            if (took) {
                DoughBayClient.LOGGER.info("DoughBay hive: took tip #{} off the board", tipId);
            }
            return took;
        } catch (Exception e) {
            return false;
        }
    }

    /** Clears out tips too old to matter; cheap, and only now and then. */
    public void sweep() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (now - sweptAt < 60_000L) return;
        sweptAt = now;
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "DELETE FROM hive_tips WHERE posted_at < ?")) {
            ps.setLong(1, now - Math.max(ttlMillis() * 4, 600_000L));
            ps.executeUpdate();
        } catch (Exception ignored) {
            // a full board is not worth a crash; it is swept next time
        }
    }
}
