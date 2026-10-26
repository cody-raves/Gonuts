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
    public enum Reply { FOLLOWED, NOT_FOUND, NO_SUCH_PLAYER, UNFOLLOWED, REFUSED, UNRELATED }

    private static final String NAME = "(\\.?[A-Za-z0-9_]{2,20})";
    private static final Pattern TRADE = Pattern.compile(
            "^" + NAME + " (listed|ordered|bought) ([0-9][0-9,]*) (.+?) for \\$ ?([0-9][0-9.,]*)\\s*([KkMmBb]?)$");
    private static final Pattern PRESENCE = Pattern.compile("^" + NAME + " (joined|left) the game$");
    private static final Pattern FOLLOWED = Pattern.compile("(?i)^you (?:are now following|followed) " + NAME + "\\.?$");
    private static final Pattern UNFOLLOWED = Pattern.compile("(?i)^you (?:unfollowed|are no longer following) " + NAME + "\\.?$");
    private static final Pattern NOT_FOUND = Pattern.compile("(?i)^no player named " + NAME + " was found\\.?$");
    /** The server's other way of saying it: no name in it, so it only counts straight after our own follow. */
    private static final Pattern NO_SUCH_PLAYER = Pattern.compile("(?i)^that player does not exist\\.?$");
    /** A sale into somebody's order: a total, no item. */
    private static final Pattern SOLD = Pattern.compile(
            "^" + NAME + " sold multiple items for \\$ ?([0-9][0-9.,]*)\\s*([KkMmBb]?)$");

    /** Money changing hands, for a followed player who has not switched payments to private. */
    private static final Pattern WAS_PAID = Pattern.compile(
            "^" + NAME + " was paid \\$ ?([0-9][0-9.,]*)\\s*([KkMmBb]?) by " + NAME + "$");
    private static final Pattern PAID = Pattern.compile(
            "^" + NAME + " paid " + NAME + " \\$ ?([0-9][0-9.,]*)\\s*([KkMmBb]?)$");
    private static final Pattern BOUNTY = Pattern.compile(
            "^" + NAME + " added \\$ ?([0-9][0-9.,]*)\\s*([KkMmBb]?) to " + NAME + "'s bounty$");

    /** Our own receipts, which name the people who trade with us: a buyer, and a seller into one of our orders. */
    private static final Pattern BOUGHT_OURS = Pattern.compile("^" + NAME + " bought your .+ for \\$.*$");
    private static final Pattern DELIVERED_TO_US = Pattern.compile("^" + NAME + " delivered you [0-9][0-9,]* .+$");

    private static final long ANSWER_MILLIS = 15_000L;
    private static final long QUEUE_MILLIS = 120_000L;
    private static final int SILENT_LIMIT = 3;
    private static final long KEEP_MILLIS = 24 * 3_600_000L;
    private static final int KEEP_MOST = 400_000;

    /** A notice as the rest of the mod reads it: the item by id, or "" when the name matched no item. */
    public record Seen(long at, String player, String kind, int quantity, String itemId, long price) { }

    /** The player a line of our own names as having traded with us, or null. */
    public static String counterparty(String line) {
        if (line == null) return null;
        String text = line.strip();
        Matcher m = BOUGHT_OURS.matcher(text);
        if (m.matches()) return m.group(1);
        m = DELIVERED_TO_US.matcher(text);
        if (m.matches() && !m.group(1).equalsIgnoreCase("players")) return m.group(1);
        return null;
    }

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
    /** Local policy refusals, distinct from anything the server confirmed. */
    private final java.util.Set<String> commandRefused = new java.util.HashSet<>();
    /** An explicit button request takes precedence over automatic counterparties. */
    private final java.util.LinkedHashSet<String> requestedBots = new java.util.LinkedHashSet<>();
    /** People who bought from us or delivered to our orders, waiting their turn to be followed. */
    private final java.util.LinkedHashSet<String> traders = new java.util.LinkedHashSet<>();
    /** Lower-cased name to when it was followed, for readers on other threads. */
    private final Map<String, Long> followedSince = new java.util.concurrent.ConcurrentHashMap<>();
    /** The last day of notices, oldest first, for the rival screens. */
    private final ArrayDeque<Seen> recent = new ArrayDeque<>();
    private boolean historyLoaded;
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
        this(directory, directory, driver);
    }

    Follows(Path directory, Path accountRecords, AutomatedExecutionDriver driver) {
        this.listFile = directory.resolve("follow-list.txt");
        this.stateFile = accountRecords.resolve("follow-state.tsv");
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
        m = SOLD.matcher(text);
        if (m.matches() && !m.group(1).equalsIgnoreCase("you")) {
            long price = money(m.group(2), m.group(3));
            return price < 0 ? null : new Event(m.group(1), "sold", 0, "", price);
        }
        // Money. The other party goes where an item's name would: who pays
        // whom is how accounts that belong together give themselves away.
        m = WAS_PAID.matcher(text);
        if (m.matches() && !m.group(1).equalsIgnoreCase("you")) {
            long amount = money(m.group(2), m.group(3));
            return amount < 0 ? null : new Event(m.group(1), "was_paid", 0, m.group(4), amount);
        }
        m = PAID.matcher(text);
        if (m.matches() && !m.group(1).equalsIgnoreCase("you")) {
            long amount = money(m.group(3), m.group(4));
            return amount < 0 ? null : new Event(m.group(1), "paid", 0, m.group(2), amount);
        }
        m = BOUNTY.matcher(text);
        if (m.matches() && !m.group(1).equalsIgnoreCase("you")) {
            long amount = money(m.group(2), m.group(3));
            return amount < 0 ? null : new Event(m.group(1), "bounty", 0, m.group(4), amount);
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
        if (NO_SUCH_PLAYER.matcher(text).matches()) return Reply.NO_SUCH_PLAYER;
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

    synchronized void observeChat(String text) {
        if (text == null || Tuning.get("follow.enabled") < 0.5) return;
        long now = System.currentTimeMillis();
        Event event = parse(text);
        if (event != null) {
            var community=dev.doughbay.fabric.hosted.CommunityClient.INSTANCE;
            if(event.kind().equals("paid"))community.observe(dev.doughbay.fabric.hosted.CommunityWire.payment(event.player(),event.item(),event.price(),now));
            else if(event.kind().equals("was_paid"))community.observe(dev.doughbay.fabric.hosted.CommunityWire.payment(event.item(),event.player(),event.price(),now));
            else community.observe(dev.doughbay.fabric.hosted.CommunityWire.follow(event.player(),event.kind(),ItemNames.resolve(event.item()),event.item(),event.quantity(),event.price(),now));
            append(eventsFile, String.join("\t", Long.toString(now), event.player(), event.kind(),
                    Integer.toString(event.quantity()), event.item(), Long.toString(event.price())));
            remember(seen(now, event));
            // The other side of a payment is the half of it nobody is watching
            // yet: followed too, whatever else they pay or are paid shows up.
            if (money(event.kind()) && Tuning.get("follow.money") >= 0.5
                    && !state.containsKey(event.item().toLowerCase(Locale.ROOT)) && traders.size() < 2_000) {
                traders.add(event.item());
            }
            return;
        }
        // Whoever buys from us or fills our orders is trading right now, and
        // the line spells the name exactly as the server wants it. A buyer's
        // purchases are the one thing a list of sellers never shows: what
        // things actually sell for.
        if (Tuning.get("follow.auto") >= 0.5) {
            String trader = counterparty(text);
            if (trader != null && !state.containsKey(trader.toLowerCase(Locale.ROOT)) && traders.size() < 2_000) {
                traders.add(trader);
            }
        }
        if (pending == null) return;
        Reply reply = classify(text, pending);
        // "That player does not exist" names nobody, and a /pay to a name the
        // server does not know is answered the same way. It is ours only when
        // it comes straight after our own follow went out. Unread, three of
        // these in a row looked like the server going quiet and stopped the
        // whole list at 292 names with no limit anywhere in sight.
        if (reply == Reply.NO_SUCH_PLAYER) {
            boolean ours = pendingSentAt > 0 && now - pendingSentAt < 5_000L;
            // The send is noticed a tick late; a reply that beats it is still ours.
            if (!ours && pendingSentAt == 0 && !driver.externalQueued("follow " + pending)) ours = true;
            reply = ours ? Reply.NOT_FOUND : Reply.UNRELATED;
        }
        switch (reply) {
            case NO_SUCH_PLAYER -> { }
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
                followedSince.remove(name.toLowerCase(Locale.ROOT));
                retries.addFirst(name);
                nextSendAt = now + gapMillis();
            }
            case REFUSED -> stop("the server answered /follow " + pending + " with: " + text.strip());
            case UNRELATED -> { }
        }
    }

    synchronized void tick(Minecraft client) {
        if (Tuning.get("follow.enabled") < 0.5) return;
        if (client == null || client.player == null || client.getConnection() == null) return;
        long now = System.currentTimeMillis();
        if (!historyLoaded) {
            // In the world, so the item names are there to read the file with.
            historyLoaded = true;
            writer.execute(this::loadHistory);
        }
        if (now - listReadAt > 30_000L) readList(now);
        if (pending != null) {
            String command = "follow " + pending;
            if (driver.externalQueued(command)) {
                if (now - pendingSince > QUEUE_MILLIS && driver.withdrawExternal(command)) {
                    // Never got a turn; try again later rather than hold the queue.
                    if (knownBots().contains(pending)) requestedBots.add(pending);
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
        String next = nextRequestedName();
        if (next == null) return;
        queueFollow(client, next, now);
    }

    synchronized void queueFollow(Minecraft client, String next, long now) {
        if (!OutgoingCommandText.valid("follow " + next)) {
            stop("command text refused: only ASCII letters, digits and spaces are allowed");
            return;
        }
        pending = next;
        pendingSince = now;
        pendingSentAt = 0;
        driver.sendWhenClear(client, "follow " + next);
    }

    /** Select intent independently of transport; menus and trade safety still gate sending. */
    synchronized String nextRequestedName() {
        String next = retries.pollFirst();
        while (next != null && !commandNameAllowed(next)) next = retries.pollFirst();
        while (next == null && !requestedBots.isEmpty()) {
            String name = requestedBots.iterator().next();
            requestedBots.remove(name);
            if (!state.containsKey(name.toLowerCase(Locale.ROOT))
                    && !name.equalsIgnoreCase(pending) && commandNameAllowed(name)) next = name;
        }
        // People trading with us right now come before a list drawn from old records.
        while (next == null && !traders.isEmpty()) {
            String trader = traders.iterator().next();
            traders.remove(trader);
            if (!state.containsKey(trader.toLowerCase(Locale.ROOT)) && commandNameAllowed(trader)) next = trader;
        }
        if (next == null) {
            for (String name : wanted) {
                if (!state.containsKey(name.toLowerCase(Locale.ROOT)) && commandNameAllowed(name)) {
                    next = name;
                    break;
                }
            }
        }
        return next;
    }

    private boolean commandNameAllowed(String name) {
        if (OutgoingCommandText.valid("follow " + name)) return true;
        // Refuse this name without rewriting it or terminating unrelated allowed intent.
        commandRefused.add(name.toLowerCase(Locale.ROOT));
        return false;
    }

    /** Every notice since {@code since}, oldest first. Safe from any thread. */
    public List<Seen> recent(long since) {
        synchronized (recent) {
            List<Seen> out = new ArrayList<>();
            for (Seen s : recent) if (s.at() >= since) out.add(s);
            return out;
        }
    }

    /** Money from one player to another, as against a trade or a login. */
    static boolean money(String kind) {
        return "paid".equals(kind) || "was_paid".equals(kind);
    }

    /** Lower-cased name to when the server confirmed the follow. A copy, safe from any thread. */
    public Map<String, Long> followedSince() {
        return Map.copyOf(followedSince);
    }

    /**
     * Puts {@code names} ahead of the list to be followed, skipping anyone
     * already asked about. Returns how many were added.
     */
    public synchronized int request(java.util.Collection<String> names) {
        int added = 0;
        for (String name : names) {
            if (name == null || !name.matches(NAME) || state.containsKey(name.toLowerCase(Locale.ROOT))) continue;
            if (traders.size() >= 2_000) break;
            if (traders.add(name)) added++;
        }
        return added;
    }

    private static Seen seen(long at, Event event) {
        return seen(at, event, ItemNames::resolve);
    }

    static Seen seen(long at, Event event, java.util.function.Function<String, String> resolve) {
        // A payment has no item: the other party is carried where its id would be.
        if (money(event.kind())) return new Seen(at, event.player(), event.kind(), 0, event.item(), event.price());
        String id = event.quantity() > 0 ? resolve.apply(event.item()) : null;
        return new Seen(at, event.player(), event.kind(), event.quantity(), id == null ? "" : id, event.price());
    }

    private void remember(Seen s) {
        synchronized (recent) {
            recent.addLast(s);
            long oldest = System.currentTimeMillis() - KEEP_MILLIS;
            while (!recent.isEmpty() && (recent.size() > KEEP_MOST || recent.peekFirst().at() < oldest)) {
                recent.pollFirst();
            }
        }
    }

    /** The last day of the events file, so a restart does not start the rival screens from nothing. */
    private void loadHistory() {
        try {
            if (!Files.exists(eventsFile)) return;
            long oldest = System.currentTimeMillis() - KEEP_MILLIS;
            List<Seen> loaded = new ArrayList<>();
            int unnamed = 0;
            try (java.io.BufferedReader reader = Files.newBufferedReader(eventsFile, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    if (p.length < 6) continue;
                    try {
                        long at = Long.parseLong(p[0]);
                        if (at < oldest) continue;
                        Seen s = seen(at, new Event(p[1], p[2], Integer.parseInt(p[3]), p[4], Long.parseLong(p[5])));
                        if (s.quantity() > 0 && s.itemId().isEmpty()) unnamed++;
                        loaded.add(s);
                    } catch (NumberFormatException ignored) {
                        // A torn last line from a session that ended mid-write.
                    }
                }
            }
            synchronized (recent) {
                // Whatever arrived while this was reading is newer than all of it.
                List<Seen> live = new ArrayList<>(recent);
                recent.clear();
                recent.addAll(loaded);
                recent.addAll(live);
                while (recent.size() > KEEP_MOST) recent.pollFirst();
            }
            DoughBayClient.LOGGER.info("DoughBay follows: {} notice(s) from the last day read back; {} name an item the client does not know",
                    loaded.size(), unnamed);
        } catch (IOException | RuntimeException e) {
            DoughBayClient.LOGGER.warn("DoughBay follows: the notices on file could not be read back: {}", e.toString());
        }
    }

    /** A line for the status screens and the log. */
    private static List<String> knownBots;

    /**
     * The accounts the mod ships with: the busiest sellers on the server when
     * its sales were last public, which trade the way bots do and whose
     * orders and listings are the best price feed a follower can have.
     */
    public static synchronized List<String> knownBots() {
        if (knownBots == null) {
            List<String> names = new ArrayList<>();
            try (java.io.InputStream in = Follows.class.getResourceAsStream("/doughbay/known-bots.txt")) {
                if (in != null) {
                    for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                        String name = line.strip();
                        if (!name.isEmpty() && !name.startsWith("#") && name.matches(NAME)) names.add(name);
                    }
                }
            } catch (IOException e) {
                DoughBayClient.LOGGER.warn("DoughBay follows: the bundled list could not be read: {}", e.toString());
            }
            knownBots = List.copyOf(names);
        }
        return knownBots;
    }

    /** The bundled names that are not on a follow list already, whatever case either is written in. */
    static List<String> notYetListed(List<String> listLines, List<String> bundled) {
        java.util.Set<String> have = new java.util.HashSet<>();
        for (String line : listLines) {
            String name = line.strip();
            if (!name.isEmpty() && !name.startsWith("#")) have.add(name.toLowerCase(Locale.ROOT));
        }
        List<String> missing = new ArrayList<>();
        for (String name : bundled) if (have.add(name.toLowerCase(Locale.ROOT))) missing.add(name);
        return missing;
    }

    /**
     * Puts the bundled accounts on this player's follow list, after whatever
     * is on it already and without touching that. Returns how many were added;
     * the list is read again at once, and they are followed in turn like any
     * other name on it.
     */
    public synchronized int addKnownBots() throws IOException {
        List<String> lines = Files.exists(listFile)
                ? Files.readAllLines(listFile, StandardCharsets.UTF_8) : new ArrayList<>();
        List<String> missing = notYetListed(lines, knownBots());
        if (missing.isEmpty()) return 0;
        StringBuilder out = new StringBuilder();
        if (lines.isEmpty()) {
            out.append("# Players to follow, one per line, in order.\n")
                    .append("# Edit freely: the bot re-reads this file within 30 seconds. Lines starting with # are ignored.\n");
        } else if (!lines.get(lines.size() - 1).isBlank()) {
            // The file may not end in a line break; the first new name must not be glued to the last old one.
            String all = Files.readString(listFile, StandardCharsets.UTF_8);
            if (!all.endsWith("\n")) out.append("\n");
        }
        for (String name : missing) out.append(name).append("\n");
        Files.writeString(listFile, out.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        listReadAt = 0;
        listModified = -1;
        DoughBayClient.LOGGER.info("DoughBay follows: {} known bot account(s) added to the follow list", missing.size());
        return missing.size();
    }

    /**
     * Adds the bundled accounts to the follow list and sees to it that they
     * will actually be followed: following on, and the cap on how many raised
     * to hold them if it was lower. Never past the configured maximum. Returns
     * how many names were added.
     */
    public synchronized int followKnownBots() throws IOException {
        int added = addKnownBots();
        listModified = -1;
        readList(System.currentTimeMillis());
        stopped = null;
        silent = 0;
        capAnnounced = false;
        if (pending == null) nextSendAt = 0;
        for (String name : knownBots()) {
            String key = name.toLowerCase(Locale.ROOT);
            String outcome = state.get(key);
            if ("FOLLOWED".equals(outcome) || !commandNameAllowed(name)) continue;
            if ("NO_REPLY".equals(outcome) || "NOT_FOUND".equals(outcome)) state.remove(key);
            if (!state.containsKey(key) && !name.equalsIgnoreCase(pending)) requestedBots.add(name);
        }
        java.util.Set<String> required = new java.util.HashSet<>();
        state.forEach((name, outcome) -> { if ("FOLLOWED".equals(outcome)) required.add(name); });
        for (String name : wanted) required.add(name.toLowerCase(Locale.ROOT));
        if (pending != null) required.add(pending.toLowerCase(Locale.ROOT));
        if (Tuning.get("follow.enabled") < 0.5) Tuning.set("follow.enabled", 1);
        if (Tuning.get("follow.max") < required.size()) Tuning.set("follow.max", Math.min(1000, required.size()));
        DoughBayClient.LOGGER.info("DoughBay follows: known bots requested; {}. Close menus to allow queued commands", status());
        return added;
    }

    public synchronized String status() {
        long followed = state.values().stream().filter("FOLLOWED"::equals).count();
        long queued = wanted.stream().map(n -> n.toLowerCase(Locale.ROOT)).distinct()
                .filter(n -> !state.containsKey(n) && !n.equalsIgnoreCase(pending))
                .filter(n -> OutgoingCommandText.valid("follow " + n)).count();
        long unavailable = state.values().stream().filter("NOT_FOUND"::equals).count();
        long unanswered = state.values().stream().filter("NO_REPLY"::equals).count();
        return followed + " confirmed here; " + queued + " list names waiting"
                + (unavailable == 0 ? "" : "; " + unavailable + " unavailable")
                + (unanswered == 0 ? "" : "; " + unanswered + " unanswered")
                + (commandRefused.isEmpty() ? "" : "; " + commandRefused.size() + " names refused by command policy")
                + (pending == null ? "" : "; /follow " + pending
                + (driver.externalQueued("follow " + pending) ? " waiting for a safe command gap" : " awaiting reply"))
                + (followed >= Tuning.get("follow.max") ? "; at configured follow cap" : "")
                + (stopped == null ? "" : "; stopped: " + stopped);
    }

    private long gapMillis() {
        return (long) (Tuning.get("follow.gap_sec") * 1000);
    }

    private void settle(String outcome, long now) {
        String name = pending;
        pending = null;
        if (!"NO_REPLY".equals(outcome)) silent = 0;
        state.put(name.toLowerCase(Locale.ROOT), outcome);
        if ("FOLLOWED".equals(outcome)) followedSince.put(name.toLowerCase(Locale.ROOT), now);
        else followedSince.remove(name.toLowerCase(Locale.ROOT));
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
                    if ("FOLLOWED".equals(parts[1])) {
                        long at = 0;
                        try {
                            if (parts.length >= 3) at = Long.parseLong(parts[2].strip());
                        } catch (NumberFormatException ignored) {
                            // An old line with no time: followed before anything on file.
                        }
                        followedSince.put(parts[0].toLowerCase(Locale.ROOT), at);
                    } else {
                        followedSince.remove(parts[0].toLowerCase(Locale.ROOT));
                    }
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
