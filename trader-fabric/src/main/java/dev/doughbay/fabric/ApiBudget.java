package dev.doughbay.fabric;

import dev.doughbay.storage.Database;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * One API key, several clients, one budget.
 *
 * <p>The rate limiter inside the API client counts only its own requests, so
 * two clients sharing a key would each spend the whole allowance and the
 * server would answer 429 to both. They share a ledger, so the ledger is where
 * they can see each other: every client writes a heartbeat, and takes an equal
 * share of the budget among the clients whose heartbeats are fresh.
 *
 * <p>Nothing here reads the key itself; it only counts who is using one.
 */
public final class ApiBudget {
    /** A client is counted while its heartbeat is this fresh. */
    private static final long ALIVE_MILLIS = 90_000L;
    /**
     * A client is still <em>there</em> for much longer than it is counted.
     *
     * <p>The heartbeat rides on the market feed loop, and one turn of that loop
     * takes as long as the budget lets it: with the key split two ways it ran
     * to eighty seconds, which is inside the window for sharing the budget but
     * only barely. Asking the same question of who exists made the two clients
     * take turns declaring each other dead - each one seized the Discord panel,
     * the other's beat landed, and it handed it straight back.
     *
     * <p>Quota is the one thing worth being twitchy about, because a client
     * that has gone quiet should not be holding requests it cannot spend.
     * Identity is not: a client says goodbye when it stops, so a row that is
     * still here really is a client that is still running.
     */
    private static final long PRESENT_MILLIS = 5 * 60_000L;
    private static final long BEAT_MILLIS = 20_000L;

    private final Path databasePath;
    private final String clientId = UUID.randomUUID().toString();
    private volatile int share;
    private volatile int clients = 1;
    private volatile long beatAt;
    private volatile java.util.Set<String> roster = java.util.Set.of();

    public ApiBudget(Path databasePath, int totalPerMinute) {
        this.databasePath = databasePath;
        this.share = totalPerMinute;
    }

    public int share() {
        return share;
    }

    public int clients() {
        return clients;
    }

    /** "90 of 180 a minute, 2 clients", for the status line. */
    public String describe(int totalPerMinute) {
        return clients <= 1
                ? share + " requests a minute"
                : share + " of " + totalPerMinute + " a minute, split between " + clients + " clients";
    }

    /**
     * Says this client is alive and works out its share. Called from the feed
     * loop; cheap enough to call often, and does its writing every 20 seconds.
     */
    private volatile long myBalance = -1;

    /** The balance this client reports to the hive, so the others know who is flush. */
    public void setBalance(long balance) {
        myBalance = balance;
    }

    public void refresh(int totalPerMinute, String name, String role) {
        long now = System.currentTimeMillis();
        if (now - beatAt < BEAT_MILLIS) return;
        beatAt = now;
        try (Database db = new Database(databasePath)) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "INSERT INTO api_clients (client_id, name, role, seen_at, balance) VALUES (?,?,?,?,?) "
                            + "ON CONFLICT(client_id) DO UPDATE SET name = excluded.name, "
                            + "role = excluded.role, seen_at = excluded.seen_at, balance = excluded.balance")) {
                ps.setString(1, clientId);
                ps.setString(2, name == null ? "" : name);
                ps.setString(3, role == null ? "" : role);
                ps.setLong(4, now);
                ps.setLong(5, myBalance);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "DELETE FROM api_clients WHERE seen_at < ?")) {
                ps.setLong(1, now - 6 * ALIVE_MILLIS);
                ps.executeUpdate();
            }
            int alive = 1;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT COUNT(*) FROM api_clients WHERE seen_at > ?")) {
                ps.setLong(1, now - ALIVE_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) alive = Math.max(1, rs.getInt(1));
                }
            }
            java.util.Set<String> names = new java.util.HashSet<>();
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT name FROM api_clients WHERE seen_at > ? AND name <> ''")) {
                ps.setLong(1, now - ALIVE_MILLIS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) names.add(rs.getString(1).toLowerCase(java.util.Locale.ROOT));
                }
            }
            roster = java.util.Set.copyOf(names);
            int was = share;
            // A client with its own key has the whole budget to itself. The
            // heartbeat and the roster still matter - they are how the hive
            // knows who is alive - only the division is dropped. Two clients
            // on separate tokens were each throttling to half on the strength
            // of seeing a sibling, and the proxy had never asked them to.
            boolean ownKey = Tuning.get("api.own_key") >= 0.5;
            int now_share = ownKey ? totalPerMinute : Math.max(20, totalPerMinute / alive);
            clients = alive;
            share = now_share;
            if (was != now_share) {
                DoughBayClient.LOGGER.info(ownKey
                        ? "DoughBay API budget: {} client(s) alive but this one has its own key; it may send {} requests a minute"
                        : "DoughBay API budget: {} client(s) sharing the key; this one may send {} requests a minute",
                        alive, now_share);
            }
        } catch (Exception e) {
            // The ledger is busy or gone: keep the share we had rather than stop.
            DoughBayClient.LOGGER.debug("DoughBay API budget could not refresh: {}", e.toString());
        }
    }

    /**
     * The accounts running DoughBay right now. Their listings are the hive's
     * own stock: buying one would be paying yourself the auction's fee for the
     * privilege, so they are skipped like your own.
     */
    public java.util.Set<String> roster() {
        return roster;
    }

    /**
     * "alice 52 listed ($6.1m), bob 30 listed ($2.4m)": the hive's book
     * split by the account holding it. Empty when nothing is listed.
     */
    public String bookByClient() {
        try (Database db = new Database(databasePath)) {
            StringBuilder out = new StringBuilder();
            for (var e : new dev.doughbay.storage.PositionRepository(db).listedByClient("REAL").entrySet()) {
                if (out.length() > 0) out.append(", ");
                out.append(e.getKey()).append(' ').append(e.getValue()[0]).append(" listed (")
                        .append(money(e.getValue()[2])).append(')');
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** "$6.1m" from a plain number, for the one-line summary. */
    private static String money(long value) {
        if (value >= 1_000_000) return String.format(java.util.Locale.ROOT, "$%.1fm", value / 1_000_000.0);
        if (value >= 1_000) return String.format(java.util.Locale.ROOT, "$%.0fk", value / 1_000.0);
        return "$" + value;
    }

    /**
     * The one client that runs the Discord bot.
     *
     * <p>The configuration folder is shared, so every client reads the same
     * discord.json and every client would connect the same bot token, edit the
     * same message and answer the same button press. One of them has to own
     * it, and it has to be a choice all of them reach on their own, from what
     * they can all see: the lowest client id that is still beating. When that
     * client goes away its row ages out and the next one takes over without
     * being told.
     *
     * <p>Empty when the ledger cannot be read, which leaves whoever was
     * hosting still hosting rather than turning the bot off on a hiccup.
     */
    public String discordHost() {
        long now = System.currentTimeMillis();
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT name FROM api_clients WHERE seen_at > ? AND name <> '' "
                             + "ORDER BY client_id LIMIT 1")) {
            ps.setLong(1, now - PRESENT_MILLIS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** Whether this client is the one that hosts the bot. */
    public boolean hostsDiscord(String myName) {
        String host = discordHost();
        if (host.isBlank() || myName == null || myName.isBlank()) return true;
        return host.equalsIgnoreCase(myName);
    }

    /** Every account trading against this ledger right now, in a stable order. */
    public java.util.List<String> accounts() {
        long now = System.currentTimeMillis();
        java.util.List<String> out = new java.util.ArrayList<>();
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT DISTINCT name FROM api_clients WHERE seen_at > ? AND name <> '' ORDER BY name")) {
            ps.setLong(1, now - PRESENT_MILLIS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (Exception e) {
            // an empty list means "just this one", which is the safe reading
        }
        return out;
    }

    /** This client is going away; its share returns to the others at once. */
    public void release() {
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement("DELETE FROM api_clients WHERE client_id = ?")) {
            ps.setString(1, clientId);
            ps.executeUpdate();
        } catch (Exception e) {
            // it ages out by itself
        }
    }
}
