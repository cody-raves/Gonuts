package dev.doughbay.fabric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What is on the shelf, remembered between visits.
 *
 * <p>The shelf is only visible while you are standing on it, which is a few
 * seconds every three quarters of an hour. Every decision about it is taken
 * during the other forty-four minutes: whether a stack that will not sell
 * should be put away, whether something put away last night is worth bringing
 * back now, whether there are enough chests left to keep the whole thing
 * running. None of those can be answered by looking, so the answer has to be
 * written down.
 *
 * <p>It is written down on disk rather than held in memory, because the
 * question outlives the session. Stock put away on Tuesday is still there on
 * Thursday, and a client that restarts in between would otherwise believe the
 * shelf was empty and quietly abandon everything on it - which is the same
 * failure that lost seventeen listings on the auction house, in a place where
 * nothing would ever have found them again.
 */
public final class Shelf {
    private static final Object LOCK = new Object();
    private static Path file;
    private static final Map<String, Integer> HELD = new LinkedHashMap<>();
    /**
     * Position ids whose stock the shelf is holding or is about to.
     *
     * <p>Kept on disk with the contents, and for the same reason. A shelved
     * position is a purchase with no stack in the pack - which is precisely
     * the shape of a purchase whose stock has gone missing, so anything
     * sweeping for stranded purchases will find every shelved one and write it
     * off. That would empty the ledger of the shelf's entire contents while
     * leaving the stock sitting in the chest, which is the same way seventeen
     * listings became untouchable this morning.
     *
     * <p>So the ledger has to be able to say "that one is on the shelf" after
     * a restart, and it can only do that if somebody wrote it down.
     */
    private static final java.util.Set<Long> WAITING = new java.util.LinkedHashSet<>();
    private static long readAt;

    private Shelf() {
    }

    static void bind(Path configDirectory) {
        synchronized (LOCK) {
            file = configDirectory.resolve("shelf.json");
            load();
        }
    }

    /** What the shelf held when it was last open, item id to count. */
    public static Map<String, Integer> held() {
        synchronized (LOCK) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(HELD));
        }
    }

    /** Whether this position's stock is the shelf's responsibility. */
    public static boolean holds(long positionId) {
        synchronized (LOCK) {
            return WAITING.contains(positionId);
        }
    }

    /** The shelf takes charge of a position's stock. */
    public static void take(long positionId) {
        synchronized (LOCK) {
            if (WAITING.add(positionId)) save();
        }
    }

    /** The stock is back in the ledger's hands. */
    public static void release(long positionId) {
        synchronized (LOCK) {
            if (WAITING.remove(positionId)) save();
        }
    }

    /** Every position the shelf is holding, for the session to pick up on start. */
    public static java.util.Set<Long> waiting() {
        synchronized (LOCK) {
            return java.util.Set.copyOf(WAITING);
        }
    }

    /** How many of one thing are on the shelf, or zero. */
    public static int heldCount(String itemId) {
        synchronized (LOCK) {
            return HELD.getOrDefault(itemId, 0);
        }
    }

    /** When the shelf was last actually looked at, or 0. */
    public static long lastSeenAt() {
        synchronized (LOCK) {
            return readAt;
        }
    }

    /**
     * Replaces the record wholesale with what the open shelf actually holds.
     *
     * <p>Not a running total kept by adding and subtracting. The shelf is the
     * only authority on its own contents, and a tally maintained from this
     * side drifts the moment a click is missed or a stack merges - which is
     * exactly how the auction slot count spent a day being wrong. When the
     * real thing is in front of you, write down the real thing.
     */
    public static void observed(Map<String, Integer> contents) {
        synchronized (LOCK) {
            HELD.clear();
            HELD.putAll(contents);
            readAt = System.currentTimeMillis();
            save();
        }
    }

    private static void load() {
        if (file == null || !Files.exists(file)) return;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(Files.readString(file));
            HELD.clear();
            WAITING.clear();
            var contents = root.has("held") ? root.get("held") : root;
            contents.fieldNames().forEachRemaining(k -> HELD.put(k, contents.get(k).asInt()));
            if (root.has("waiting")) root.get("waiting").forEach(n -> WAITING.add(n.asLong()));
            DoughBayClient.LOGGER.info("DoughBay shelf: {} kind(s) of stock on the shelf {}, {} position(s) in its care",
                    HELD.size(), HELD, WAITING.size());
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay shelf: could not read {}: {}", file, e.toString());
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Files.createDirectories(file.getParent());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("held", HELD);
            out.put("waiting", WAITING);
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay shelf: could not write {}: {}", file, e.toString());
        }
    }
}
