package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Follows other players with the server's own /follow command and writes
 * down what the server then tells every follower: what the player listed,
 * ordered and bought, at what price, and when they came and went.
 *
 * <p>With no public feed the bot only knows the prices of what it trades
 * itself. A follow is a game feature any player has, it needs no approval,
 * and its notices carry item, quantity and price for both the auction and the
 * order house: "traderone ordered 128 Nether Wart for $ 50K", then
 * "traderone listed 64 Nether Wart for $ 64.9K" a few minutes later, is a
 * rival's whole margin on an item, read from chat.
 *
 * <p>Two jobs, both plain. Names from {@code follow-list.txt} are followed one
 * at a time through the driver's queue for outside commands, in the gaps
 * between trades, and the server's answer to each is kept in
 * {@code follow-state.tsv}. Every notice that comes back is appended to
 * {@code follow-events.tsv}. Nothing here prices or trades on what it reads.
 *
 * <p>It stops sending the moment the server answers a follow with anything it
 * does not recognise - that is how a limit will announce itself - and says
 * what the answer was. Off until switched on.
 */
public final class Follows {
    /** One notice about a followed player. {@code price} is the total the server showed, rounded as it rounds. */
    public record Event(String player, String kind, int quantity, String item, long price) { }

    /** What the server said to a /follow. */
    public enum Reply { FOLLOWED, NOT_FOUND, UNFOLLOWED, REFUSED, UNRELATED }

    private static final String NAME = "(\\.?[A-Za-z0-9_]{2,20})";
    private static final Pattern TRADE = Pattern.compile(
            "^" + NAME + " (listed|ordered|bought) ([0-9][0-9,]*) (.+?) for \\$ ?([0-9][0-9.,]*)\\s*([KkMmBb]?)$");
    private static final Pattern PRESENCE = Pattern.compile("^" + NAME + " (joined|left) the game$");
    private static final Pattern FOLLOWED = Pattern.compile("(?i)^you (?:are now following|followed) " + NAME + "\\.?$");
    private static final Pattern UNFOLLOWED = Pattern.compile("(?i)^you (?:unfollowed|are no longer following) " + NAME + "\\.?$");
    private static final Pattern NOT_FOUND = Pattern.compile("(?i)^no player named " + NAME + " was found\\.?$");

    private static final long ANSWER_MILLIS = 15_000L;
    private static final long QUEUE_MILLIS = 120_000L;
    private static final int SILENT_LIMIT = 3;

    private final Path listFile;
    private final Path stateFile;
    private final Path eventsFile;
    private final AutomatedExecutionDriver driver;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doughbay-follows");
        t.setDaemon(true);
        return t;
    });

    /** Lower-cased name to what the server answered: FOLLOWED, NOT_FOUND, NO_REPLY. */
    private final Map<String, String> state = new LinkedHashMap<>();
    private final ArrayDeque<String> retries = new ArrayDeque<>();
    private List<String> wanted = List.of();
    private long listReadAt;
    private long listModified = -1;

    private String pending;
    private long pendingSince;
    private long pendingSentAt;
    private long nextSendAt;
    private int silent;
    private String stopped;
    private boolean capAnnounced;

    Follows(Path directory, AutomatedExecutionDriver driver) {
        this.listFile = directory.resolve("follow-list.txt");
        this.stateFile = directory.resolve("follow-state.tsv");
        this.eventsFile = directory.resolve("follow-events.tsv");
        this.driver = driver;
        loadState();
    }

    /** A listed, ordered or bought notice, or a join or leave; null for any other line. */
    public static Event parse(String line) {
        if (line == null) return null;
        String text = line.strip();
        Matcher m = TRADE.matcher(text);
        if (m.matches()) {
            // "You listed 64 Obsidian for $ 48K" is our own receipt, not a notice about someone.
            if (m.group(1).equalsIgnoreCase("you")) return null;
            int quantity;
            try {
                quantity = Integer.parseInt(m.group(3).replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
            long price = money(m.group(5), m.group(6));
            if (quantity <= 0 || price < 0) return null;
            return new Event(m.group(1), m.group(2), quantity, m.group(4).strip(), price);
        }
        m = PRESENCE.matcher(text);
        if (m.matches() && !m.group(1).equalsIgnoreCase("you")) {
            return new Event(m.group(1), m.group(2), 0, "", 0);
        }
        return null;
    }

    /** "93.7" with "K" is 93,700; -1 when it is not a number. */
    static long money(String digits, String suffix) {
        try {
            double value = Double.parseDouble(digits.replace(",", ""));
            double scale = switch (suffix == null ? "" : suffix.toUpperCase(Locale.ROOT)) {
                case "K" -> 1_000d;
                case "M" -> 1_000_000d;
                case "B" -> 1_000_000_000d;
                default -> 1d;
            };
            return Math.round(value * scale);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** How a line answers the follow that is waiting on {@code name}. */
    public static Reply classify(String line, String name) {
        if (line == null || name == null) return Reply.UNRELATED;
        String text = line.strip();
        Matcher m = FOLLOWED.matcher(text);
        if (m.matches()) return m.group(1).equalsIgnoreCase(name) ? Reply.FOLLOWED : Reply.UNRELATED;
        m = UNFOLLOWED.matcher(text);
        if (m.matches()) return m.group(1).equalsIgnoreCase(name) ? Reply.UNFOLLOWED : Reply.UNRELATED;
        m = NOT_FOUND.matcher(text);
        if (m.matches()) return m.group(1).equalsIgnoreCase(name) ? Reply.NOT_FOUND : Reply.UNRELATED;
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("follow")) return Reply.UNRELATED;
        if (lower.contains("already")) return Reply.FOLLOWED;
        // The server speaking to us about following, in words not seen before:
        // a limit, a cooldown, a refusal. A player's chat line carries a name
        // and a separator in front and does not start like this.
        if (lower.startsWith("you ") || lower.startsWith("usage") || lower.contains("limit")
                || lower.contains("maximum") || lower.contains("cannot") || lower.contains("can't")) {
            return Reply.REFUSED;
        }
        return Reply.UNRELATED;
    }

    void observeChat(String text) {
        if (text == null || Tuning.get("follow.enabled") < 0.5) return;
        long now = System.currentTimeMillis();
        Event event = parse(text);
        if (event != null) {
            append(eventsFile, String.join("\t", Long.toString(now), event.player(), event.kind(),
                    Integer.toString(event.quantity()), event.item(), Long.toString(event.price())));
            return;
        }
        if (pending == null) return;
        Reply reply = classify(text, pending);
        switch (reply) {
            case FOLLOWED -> settle("FOLLOWED", now);
            case NOT_FOUND -> {
                // Bedrock players carry a dot the old records dropped.
                String name = pending;
                settle("NOT_FOUND", now);
                if (!name.startsWith(".") && !state.containsKey("." + name.toLowerCase(Locale.ROOT))) {
                    retries.addLast("." + name);
                }
            }
            case UNFOLLOWED -> {
                // The command toggles: this one was followed already and has
                // just been dropped. Put it back, once.
                String name = pending;
                DoughBayClient.LOGGER.warn("DoughBay follows: {} was already followed and /follow dropped it; following again", name);
                pending = null;
                state.remove(name.toLowerCase(Locale.ROOT));
                retries.addFirst(name);
                nextSendAt = now + gapMillis();
            }
            case REFUSED -> stop("the server answered /follow " + pending + " with: " + text.strip());
            case UNRELATED -> { }
        }
    }

    void tick(Minecraft client) {
        if (Tuning.get("follow.enabled") < 0.5) return;
        if (client == null || client.player == null || client.getConnection() == null) return;
        long now = System.currentTimeMillis();
        if (now - listReadAt > 30_000L) readList(now);
        if (pending != null) {
            String command = "follow " + pending;
            if (driver.externalQueued(command)) {
                if (now - pendingSince > QUEUE_MILLIS && driver.withdrawExternal(command)) {
                    // Never got a turn; try again later rather than hold the queue.
                    pending = null;
                    nextSendAt = now + 60_000L;
                }
                return;
            }
            if (pendingSentAt == 0) pendingSentAt = now;
            if (now - pendingSentAt < ANSWER_MILLIS) return;
            DoughBayClient.LOGGER.info("DoughBay follows: no answer to /follow {} in {} s", pending, ANSWER_MILLIS / 1000);
            settle("NO_REPLY", now);
            if (++silent >= SILENT_LIMIT) stop(SILENT_LIMIT + " follows in a row went unanswered");
            return;
        }
        if (stopped != null || now < nextSendAt || driver.externalCommandQueued()) return;
        int cap = (int) Tuning.get("follow.max");
        long followed = state.values().stream().filter("FOLLOWED"::equals).count();
        if (followed >= cap) {
            if (!capAnnounced) {
                capAnnounced = true;
                DoughBayClient.LOGGER.info("DoughBay follows: {} followed, the cap in settings; not following more", followed);
            }
            return;
        }
        capAnnounced = false;
        String next = retries.pollFirst();
        if (next == null) {
            for (String name : wanted) {
                if (!state.containsKey(name.toLowerCase(Locale.ROOT))) {
                    next = name;
                    break;
                }
            }
        }
        if (next == null) return;
        pending = next;
        pendingSince = now;
        pendingSentAt = 0;
        driver.sendWhenClear(client, "follow " + next);
    }

    /** A line for the status screens and the log. */
    public String status() {
        long followed = state.values().stream().filter("FOLLOWED"::equals).count();
        return followed + " followed of " + wanted.size() + " listed" + (stopped == null ? "" : "; stopped: " + stopped);
    }

    private long gapMillis() {
        return (long) (Tuning.get("follow.gap_sec") * 1000);
    }

    private void settle(String outcome, long now) {
        String name = pending;
        pending = null;
        if (!"NO_REPLY".equals(outcome)) silent = 0;
        state.put(name.toLowerCase(Locale.ROOT), outcome);
        append(stateFile, name + "\t" + outcome + "\t" + now);
        nextSendAt = now + gapMillis();
        if ("FOLLOWED".equals(outcome)) {
            long followed = state.values().stream().filter("FOLLOWED"::equals).count();
            if (followed % 25 == 0) DoughBayClient.LOGGER.info("DoughBay follows: {} followed so far", followed);
        }
    }

    private void stop(String reason) {
        stopped = reason;
        pending = null;
        DoughBayClient.LOGGER.warn("DoughBay follows: stopped following new players - {}. "
                + "Notices from players already followed are still recorded", reason);
    }

    private void readList(long now) {
        listReadAt = now;
        try {
            if (!Files.exists(listFile)) {
                wanted = List.of();
                return;
            }
            long modified = Files.getLastModifiedTime(listFile).toMillis();
            if (modified == listModified) return;
            listModified = modified;
            List<String> names = new ArrayList<>();
            for (String line : Files.readAllLines(listFile, StandardCharsets.UTF_8)) {
                String name = line.strip();
                if (name.isEmpty() || name.startsWith("#")) continue;
                if (name.matches(NAME)) names.add(name);
            }
            wanted = List.copyOf(names);
            // A fresh list is a fresh instruction: a stop from the old one is lifted.
            stopped = null;
            silent = 0;
            DoughBayClient.LOGGER.info("DoughBay follows: {} name(s) on the follow list, {} already answered",
                    wanted.size(), wanted.stream().filter(n -> state.containsKey(n.toLowerCase(Locale.ROOT))).count());
        } catch (IOException e) {
            DoughBayClient.LOGGER.warn("DoughBay follows: the follow list could not be read: {}", e.toString());
        }
    }

    private void loadState() {
        try {
            if (!Files.exists(stateFile)) return;
            for (String line : Files.readAllLines(stateFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t");
                // A name that only went unanswered is asked again next session.
                if (parts.length >= 2 && !"NO_REPLY".equals(parts[1])) {
                    state.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
                }
            }
        } catch (IOException e) {
            DoughBayClient.LOGGER.warn("DoughBay follows: the follow record could not be read: {}", e.toString());
        }
    }

    private void append(Path file, String line) {
        writer.execute(() -> {
            try {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                DoughBayClient.LOGGER.warn("DoughBay follows: could not write {}: {}", file.getFileName(), e.toString());
            }
        });
    }
}
