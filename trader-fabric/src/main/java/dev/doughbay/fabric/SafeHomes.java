package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * leaves the guard on the random teleport.
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
     * The next home to run to: never the one just used, and otherwise chosen
     * by the hour so the pattern is not a simple cycle. Empty when none are
     * configured.
     */
    public static String next() {
        List<String> all = homes;
        if (all.isEmpty()) return "";
        if (all.size() == 1) return all.get(0);
        List<String> choices = new ArrayList<>(all);
        choices.remove(lastUsed);
        int index = (int) ((System.currentTimeMillis() / 3_600_000L + choices.size()) % choices.size());
        return choices.get(index);
    }

    public static void noteUsed(String name) {
        lastUsed = name;
        lastUsedAt = System.currentTimeMillis();
    }
}
