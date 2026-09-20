package dev.doughbay.fabric.discord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The running account of what the bot is doing, for a Discord channel of its
 * own: orders placed and collected, listings, sales, payments, and every time
 * the guard moved the player and who was near.
 *
 * <p>Lines are queued from wherever the thing actually happened and posted by
 * the bridge in batches, a few seconds apart. One message per event would run
 * into Discord's rate limit within a minute of a busy session - fifteen
 * singles a minute is ordinary - so a batch is one message of many lines.
 *
 * <p>Off until a feed channel is set on the Discord tab, and nothing is
 * queued while it is off.
 */
public final class DiscordFeed {
    /** Discord's limit is 2000 characters a message; this leaves room for the note about dropped lines. */
    static final int MESSAGE_CHARS = 1900;
    static final int MAX_QUEUED = 400;

    private static final ConcurrentLinkedDeque<String> LINES = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger DROPPED = new AtomicInteger();
    private static volatile boolean enabled;

    private DiscordFeed() {
    }

    static void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            LINES.clear();
            DROPPED.set(0);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    /** One line for the channel. Safe from any thread; a no-op while the feed is off. */
    public static void post(String line) {
        if (!enabled || line == null || line.isBlank()) return;
        LINES.addLast(clean(line));
        while (LINES.size() > MAX_QUEUED) {
            if (LINES.pollFirst() != null) DROPPED.incrementAndGet();
        }
    }

    /** The next message's worth of lines, oldest first; empty when there is nothing to say. */
    static String nextMessage() {
        List<String> batch = new ArrayList<>();
        int length = 0;
        String line;
        while ((line = LINES.peekFirst()) != null) {
            if (!batch.isEmpty() && length + line.length() + 1 > MESSAGE_CHARS) break;
            LINES.pollFirst();
            batch.add(line);
            length += line.length() + 1;
        }
        int dropped = DROPPED.getAndSet(0);
        if (dropped > 0) batch.add(0, "_(" + dropped + " earlier line(s) skipped: the feed fell behind)_");
        return String.join("\n", batch);
    }

    /** Keeps a line to one row and out of trouble: no pings, no markdown surprises from a player's name. */
    static String clean(String line) {
        String text = line.replace('\r', ' ').replace('\n', ' ').replace("@", "@​").strip();
        return text.length() <= MESSAGE_CHARS ? text : text.substring(0, MESSAGE_CHARS - 1) + "…";
    }

    /** 1,100,000 as "1.1M", 43,500 as "43.5K", 5,500 as "5,500": the way the game itself abbreviates. */
    public static String money(long amount) {
        long a = Math.abs(amount);
        String sign = amount < 0 ? "-" : "";
        if (a >= 1_000_000_000L) return sign + trim(a / 1_000_000_000.0) + "B";
        if (a >= 1_000_000L) return sign + trim(a / 1_000_000.0) + "M";
        if (a >= 10_000L) return sign + trim(a / 1_000.0) + "K";
        return sign + String.format(Locale.ROOT, "%,d", a);
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** "minecraft:deepslate_diamond_ore" as "deepslate diamond ore". */
    public static String item(String itemId) {
        String id = itemId == null ? "" : itemId;
        int hash = id.indexOf('#');
        if (hash >= 0) id = id.substring(0, hash);
        return id.replace("minecraft:", "").replace('_', ' ');
    }
}
