package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * The safe places to run to.
 *
 * <p>A random teleport lands you somewhere unknown, at night, possibly worse
 * than where you were. A named home is lit, enclosed and yours. Several of
 * them, rotated, also mean that somebody who found you once has not found you
 * for good.
 *
 * <p>Read from {@code homes.json} next to the other config:
 * {@code {"homes": ["base", "vault", "north"]}}. An empty or missing file
 * cannot supply a home route. Home mode never silently becomes random teleport.
 */
public final class SafeHomes {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile List<String> homes = List.of();
    private static volatile java.util.Set<String> allies = java.util.Set.of();
    private static volatile String lastUsed = "";
    private static volatile long lastUsedAt;
    private static Path file;

    private SafeHomes() {
    }

    private static volatile long reloadedAt;
    private static volatile long seenModified;

    /**
     * Re-reads the file while the game runs, so a name added to the ally list
     * takes effect without a restart. Called from the client tick.
     */
    public static void poll() {
        Path f = file;
        if (f == null) return;
        long now = System.currentTimeMillis();
        if (now - reloadedAt < 3000) return;
        reloadedAt = now;
        try {
            if (!Files.exists(f)) return;
            long modified = Files.getLastModifiedTime(f).toMillis();
            if (modified == seenModified) return;
            seenModified = modified;
            load(f.getParent());
        } catch (Exception e) {
            // an unreadable file keeps whatever was already loaded
        }
    }

    public static void load(Path directory) {
        file = directory.resolve("homes.json");
        List<String> read = new ArrayList<>();
        try {
            if (Files.exists(file)) {
                JsonNode node = MAPPER.readTree(Files.readString(file)).path("homes");
                for (JsonNode n : node) {
                    String name = n.asText("").strip();
                    if (!name.isEmpty() && name.matches("[A-Za-z0-9_-]{1,32}")) read.add(name);
                }
            }
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay could not read homes.json: {}", e.toString());
        }
        homes = List.copyOf(read);
        java.util.Set<String> friends = new java.util.HashSet<>();
        try {
            if (Files.exists(file)) {
                for (JsonNode n : MAPPER.readTree(Files.readString(file)).path("allies")) {
                    String name = n.asText("").strip().toLowerCase(java.util.Locale.ROOT);
                    if (!name.isEmpty()) friends.add(name);
                }
            }
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay could not read the ally list: {}", e.toString());
        }
        allies = java.util.Set.copyOf(friends);
        if (!allies.isEmpty()) DoughBayClient.LOGGER.info("DoughBay: {} ally/allies the guard ignores: {}", allies.size(), allies);
        if (!homes.isEmpty()) {
            DoughBayClient.LOGGER.info("DoughBay: {} safe home(s) to rotate between: {}", homes.size(), homes);
        }
    }

    /** Players the evasion guard treats as harmless. */
    public static boolean isAlly(String name) {
        return name != null && allies.contains(name.strip().toLowerCase(java.util.Locale.ROOT));
    }

    public static java.util.Set<String> allies() {
        return allies;
    }

    public static List<String> homes() {
        return homes;
    }

    public static String lastUsed() {
        return lastUsed;
    }

    public static long lastUsedAt() {
        return lastUsedAt;
    }

    /**
     * The next home in entered order, wrapping at the end. Empty when none are
     * configured. A single saved home is reused.
     */
    public static String next() {
        List<String> all = homes;
        if (all.isEmpty()) return "";
        for (int i = 0; i < all.size(); i++)
            if (all.get(i).equalsIgnoreCase(lastUsed)) return all.get((i + 1) % all.size());
        return all.getFirst();
    }

    /** UI accepts names, not commands. Reject the entire edit rather than saving a partial list. */
    public static List<String> parseNames(String text) {
        if (text == null || text.length() > 2048) throw new IllegalArgumentException("Enter up to 32 home names");
        var unique = new LinkedHashMap<String, String>();
        for (String token : text.strip().split("[\\s,]+")) {
            if (token.isEmpty()) continue;
            if (!token.matches("[A-Za-z0-9_-]{1,32}"))
                throw new IllegalArgumentException("Use home names only, such as base, vault, farm; no slash or command");
            unique.putIfAbsent(token.toLowerCase(Locale.ROOT), token);
            if (unique.size() > 32) throw new IllegalArgumentException("Use at most 32 home names");
        }
        return List.copyOf(unique.values());
    }

    /** Preserve allies and unrelated fields; never replace an unreadable file with defaults. */
    public static synchronized void saveHomes(String text) throws IOException {
        List<String> names = parseNames(text);
        Path f = file;
        if (f == null) throw new IOException("Home settings have not loaded yet");
        var root = MAPPER.createObjectNode();
        if (Files.exists(f)) {
            if (Files.size(f) > 65_536) throw new IOException("Home settings file is too large");
            JsonNode existing = MAPPER.readTree(Files.readString(f));
            if (existing == null || !existing.isObject()) throw new IOException("Home settings need repair before saving");
            root = (com.fasterxml.jackson.databind.node.ObjectNode) existing;
        }
        var array = root.putArray("homes");
        names.forEach(array::add);
        Path temporary = Files.createTempFile(f.getParent(), "homes-", ".tmp");
        try {
            Files.writeString(temporary, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            Files.move(temporary, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        homes = names;
        seenModified = Files.getLastModifiedTime(f).toMillis();
    }

    /** Rotates attempts in entered order. The names refer to homes already set on the server. */
    public static String teleportCommand(boolean useHomes) {
        String home = useHomes ? next() : "";
        if (home.isEmpty()) return useHomes ? "" : "rtp";
        noteUsed(home);
        return "home " + home;
    }

    public static void noteUsed(String name) {
        lastUsed = name;
        lastUsedAt = System.currentTimeMillis();
    }
}
