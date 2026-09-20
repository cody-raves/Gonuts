package dev.doughbay.server;

import dev.doughbay.storage.Database;

import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** API keys for the data server: minted by the admin, one per person, revocable. */
public final class ServerStore {
    public record ApiKey(String key, String name, long createdAt, boolean revoked, long requests) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Key validity cached for a short while so every request does not hit the database. */
    private static final Map<String, long[]> VALID_UNTIL = new ConcurrentHashMap<>();
    private static final long CACHE_MILLIS = 30_000;

    private ServerStore() {
    }

    static void ensureTables(Database db) throws SQLException {
        try (Statement st = db.connection().createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS api_keys (
                        api_key    TEXT PRIMARY KEY,
                        name       TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        revoked    INTEGER NOT NULL DEFAULT 0,
                        requests   INTEGER NOT NULL DEFAULT 0
                    )""");
        }
    }

    static ApiKey create(Database db, String name) throws SQLException {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        String key = "db_" + HexFormat.of().formatHex(bytes);
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO api_keys(api_key, name, created_at) VALUES (?, ?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, name == null || name.isBlank() ? "unnamed" : name.strip());
            ps.setLong(3, now);
            ps.executeUpdate();
        }
        return new ApiKey(key, name, now, false, 0);
    }

    static boolean revoke(Database db, String key) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE api_keys SET revoked = 1 WHERE api_key = ?")) {
            ps.setString(1, key);
            VALID_UNTIL.remove(key);
            return ps.executeUpdate() > 0;
        }
    }

    static List<ApiKey> list(Database db) throws SQLException {
        List<ApiKey> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT api_key, name, created_at, revoked, requests FROM api_keys ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new ApiKey(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4) != 0, rs.getLong(5)));
            }
        }
        return out;
    }

    /** Whether the key exists and is not revoked; cached briefly. */
    static boolean valid(Database db, String key) throws SQLException {
        if (key == null || key.isBlank()) return false;
        long now = System.currentTimeMillis();
        long[] cached = VALID_UNTIL.get(key);
        if (cached != null && cached[0] > now) return cached[1] == 1;
        boolean ok = false;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT revoked FROM api_keys WHERE api_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                ok = rs.next() && rs.getInt(1) == 0;
            }
        }
        VALID_UNTIL.put(key, new long[] {now + CACHE_MILLIS, ok ? 1 : 0});
        return ok;
    }

    static void countRequests(Database db, String key, long n) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE api_keys SET requests = requests + ? WHERE api_key = ?")) {
            ps.setLong(1, n);
            ps.setString(2, key);
            ps.executeUpdate();
        }
    }
}
