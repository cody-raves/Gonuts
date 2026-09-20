package dev.doughbay.fabric.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.doughbay.core.automation.ContinuousAutomationPolicy;
import dev.doughbay.core.execution.ExecutionResult;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.fabric.DoughBayClient;
import dev.doughbay.fabric.MarketShare;
import dev.doughbay.fabric.MarketWatcher;
import dev.doughbay.fabric.OrderBook;
import dev.doughbay.fabric.Payroll;
import dev.doughbay.fabric.RivalIntel;
import dev.doughbay.fabric.Tuning;
import dev.doughbay.fabric.automation.AutomationSessionController;
import dev.doughbay.storage.Database;
import dev.doughbay.storage.PayrollRepository;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The command centre: one Discord message, edited every half minute with a
 * rendered page of the trader's state, and buttons, dropdowns and forms that
 * start, stop and tune it. Only the configured operator's presses count.
 */
public final class DiscordBridge {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final long REFRESH_MILLIS = 30_000;

    enum Page { MONEY, STATUS, HIVE, LISTINGS, OPPS, ORDERS, RIVALS, EVASION, PAYROLL, TUNE, SEARCH }

    private final Logger log;
    private final Path configDir;
    private final Path ledgerPath;
    private final AutomationSessionController session;

    /**
     * Listings actually up, as the server last reported them, falling back to
     * the ledger's own count when there is no fresh answer.
     *
     * <p>The panel in the game and the embed in Discord were reading different
     * numbers off the same bot - eighty-six against sixty-four - because one
     * had been moved onto the server's count and the other still totted up the
     * ledger. Two screens disagreeing about the same thing is worse than
     * either being wrong, so both take it from here now.
     */
    private int listedNow(AutomationSessionController.SessionSnapshot s) {
        int fromServer = session.serverListedSlots();
        return fromServer >= 0 ? fromServer : s.openListings();
    }
    private volatile DiscordConfig config;
    private volatile DiscordRest rest;
    private volatile DiscordGateway gateway;
    private volatile String applicationId = "";
    private volatile String status = "not configured";
    private volatile Page page = Page.MONEY;
    /**
     * Whose figures the pages show: empty is the whole hive, otherwise one
     * account. Only meaningful with more than one client trading, and the hive
     * is the default because the first question in multi mode is what the pair
     * made together.
     */
    private volatile String account = "";
    /** The newest hive-control reply already shown, so each is shown once. */
    private volatile long controlRepliesSeenAt = System.currentTimeMillis();
    private volatile String tuneGroup = "";
    private volatile String tuneKey = "";
    private volatile String searchTerm = "";
    private volatile String notice = "";
    private volatile long noticeAt;
    private final AtomicBoolean publishing = new AtomicBoolean();
    private volatile boolean refreshNow;
    private volatile String presenceText = "";
    private volatile String saleFlash = "";
    private volatile long saleFlashAt;

    private DiscordBridge(Logger log, Path configDir, Path ledgerPath, AutomationSessionController session) {
        this.log = log;
        this.configDir = configDir;
        this.ledgerPath = ledgerPath;
        this.session = session;
        this.config = DiscordConfig.load(configDir);
    }

    public static DiscordBridge start(Logger log, Path configDir, Path ledgerPath, AutomationSessionController session) {
        DiscordBridge b = new DiscordBridge(log, configDir, ledgerPath, session);
        b.connect();
        Thread t = new Thread(b::refreshLoop, "doughbay-discord");
        t.setDaemon(true);
        t.start();
        return b;
    }

    /** A sale to show under the bot's name for the next half minute. */
    public void noteSale(String itemKey, int quantity, long salePrice, double profit) {
        saleFlash = "Sold " + item(itemKey) + " x" + quantity + " for " + money(salePrice)
                + " (" + signed((long) profit) + ")";
        saleFlashAt = System.currentTimeMillis();
        refreshNow = true;
    }

    public String status() {
        DiscordGateway g = gateway;
        return g == null ? status : g.status() + " · " + status;
    }

    public DiscordConfig config() {
        return config;
    }

    /** Saves and applies a new configuration from the screen. */
    public synchronized void reconfigure(DiscordConfig c) {
        try {
            c.save(configDir);
        } catch (Exception e) {
            status = "could not save discord.json: " + e.getMessage();
            return;
        }
        DiscordGateway old = gateway;
        if (old != null) old.stop();
        gateway = null;
        config = c;
        connect();
        refreshNow = true;
    }

    private synchronized void connect() {
        DiscordConfig c = config;
        if (!c.complete()) {
            status = "not configured";
            return;
        }
        rest = new DiscordRest(c.token());
        gateway = new DiscordGateway(log, c.token(), rest, this::onInteraction, appId -> {
            applicationId = appId;
            refreshNow = true;
        });
        gateway.start();
        status = "starting";
    }

    private void refreshLoop() {
        long last = 0;
        while (true) {
            try {
                Thread.sleep(1000);
                long now = System.currentTimeMillis();
                if (!hostCheck()) continue;
                if (gateway == null || applicationId.isEmpty()) continue;
                updatePresence(now);
                if (refreshNow || now - last >= REFRESH_MILLIS) {
                    refreshNow = false;
                    last = now;
                    publish();
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                status = "publish failed: " + e.getMessage();
                log.warn("DoughBay Discord: {}", e.toString());
            }
        }
    }

    private volatile long hostCheckedAt;
    private volatile boolean hosting = true;

    /**
     * Connects the bot only on the client that owns it.
     *
     * <p>Clients in a hive share a configuration folder, so they all read the
     * same token, channel and message id. Left alone they would all connect,
     * all edit the same message half a minute apart, and all answer the same
     * button press, two of them getting an error for their trouble. One hosts;
     * the others hold their peace and take over if it goes away.
     *
     * @return whether this client should be publishing
     */
    private boolean hostCheck() {
        long now = System.currentTimeMillis();
        if (now - hostCheckedAt > 20_000) {
            hostCheckedAt = now;
            boolean want = DoughBayClient.hostsDiscord();
            if (want != hosting) {
                hosting = want;
                if (want) {
                    log.info("DoughBay Discord: this client is now hosting the panel");
                    connect();
                    refreshNow = true;
                } else {
                    log.info("DoughBay Discord: another client hosts the panel; standing down");
                    DiscordGateway g = gateway;
                    if (g != null) g.stop();
                    gateway = null;
                    status = "standing by; another client hosts the panel";
                }
            }
        }
        return hosting;
    }

    /** Discord's "playing" line: what the trader is doing right now. */
    private void updatePresence(long now) {
        DiscordGateway g = gateway;
        if (g == null) return;
        String text = presenceLine(now);
        if (text.equals(presenceText)) return;
        presenceText = text;
        // 3 is "Watching"; the trader watches the auction house for a living.
        g.presence(text, 3);
    }

    private String presenceLine(long now) {
        if (!saleFlash.isBlank() && now - saleFlashAt < 30_000) return saleFlash;
        AutomationSessionController.SessionSnapshot s = session.snapshot();
        MarketWatcher watcher = DoughBayClient.watcher();
        MarketWatcher.Snapshot m = watcher == null ? null : watcher.snapshot();
        String bank = m == null ? "" : " · " + money(m.accountBalance());
        String slots = " · " + listedNow(s) + "/" + (int) Tuning.get("slots.max");
        String what = switch (s.state()) {
            case STOPPED -> "stopped";
            case PAUSED -> "paused: " + shorten(s.detail(), 60);
            case SCANNING -> "the auction house";
            case BUYING -> "a buy: " + shorten(detailItem(s), 40);
            case PREPARING_LIST, LISTING, MONITORING -> "a listing: " + shorten(detailItem(s), 40);
            case REPRICING -> "a pull-back: " + shorten(detailItem(s), 40);
            case READING_ORDERS -> "the order house";
            case BID_DESK -> "the bid desk: " + shorten(s.detail().replace("Bid desk: ", ""), 45);
            case AUDITING_SLOTS -> "the slot check";
            case COOLDOWN -> "the cooldown";
            default -> shorten(s.detail(), 60);
        };
        return what + (s.state() == AutomationSessionController.State.STOPPED ? "" : slots + bank);
    }

    private static String detailItem(AutomationSessionController.SessionSnapshot s) {
        if (s.position() != null) return item(s.position().itemKey()) + " x" + s.position().quantity();
        return shorten(s.detail(), 40);
    }

    private static String shorten(String text, int max) {
        String t = text == null ? "" : text.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    // ---------------------------------------------------------------- publishing

    private void publish() throws Exception {
        if (!publishing.compareAndSet(false, true)) return;
        try {
            StatsImage.Page p = buildPage().withFaces(faces()).withMascot(mascotFace());
            byte[] png = StatsImage.render(p);
            String payload = payload(p);
            DiscordConfig c = config;
            boolean ok = !c.messageId().isBlank() && rest.editMessage(c.channelId(), c.messageId(), payload, png);
            if (!ok) {
                String id = rest.createMessage(c.channelId(), payload, png);
                config = c.withMessageId(id);
                config.save(configDir);
            }
            status = "live · " + page.name().toLowerCase(Locale.ROOT) + " · " + CLOCK.format(Instant.now());
        } finally {
            publishing.set(false);
        }
    }

    /**
     * The accounts whose heads go in the header: the one being looked at, or
     * every one of them when the view is the hive.
     */
    /**
     * The face the robot in the header wears: the same reading of the session
     * the mascot on the game's own screen makes, so the panel and the screen
     * never disagree about the mood.
     */
    private String mascotFace() {
        AutomationSessionController.SessionSnapshot s = session.snapshot();
        if (!saleFlash.isBlank() && System.currentTimeMillis() - saleFlashAt < 30_000) return "sale";
        return switch (s.state()) {
            case STOPPED -> "sleep";
            case PAUSED -> "paused";
            case SCANNING, BUYING -> "watch";
            case COOLDOWN, MONITORING -> "idle";
            default -> "work";
        };
    }

    private List<String> faces() {
        if (!account.isBlank()) return List.of(account);
        List<String> hive = DoughBayClient.hiveAccounts();
        if (hive.size() > 1) return hive;
        String me = DoughBayClient.account();
        return me.isBlank() ? List.of() : List.of(me);
    }

    private String payload(StatsImage.Page p) {
        ObjectNode root = MAPPER.createObjectNode();
        // The picture is the message. An image inside an embed is shown at
        // about 550 pixels wide however large it is drawn and whatever the
        // reader sets their scaling to, which made the page unreadable on a
        // big screen; the same file posted as a plain attachment is shown at
        // its own size and grows with the window.
        StringBuilder head = new StringBuilder("**GoNuts · ").append(p.title()).append("**");
        String desc = notice.isBlank() || System.currentTimeMillis() - noticeAt > 120_000 ? "" : notice;
        if (!desc.isBlank()) head.append('\n').append(desc);
        if (!p.footer().isBlank()) head.append("\n-# ").append(p.footer());
        root.put("content", head.toString());
        ArrayNode attachments = root.putArray("attachments");
        ObjectNode att = attachments.addObject();
        att.put("id", 0);
        att.put("filename", "stats.png");
        root.set("components", components());
        return root.toString();
    }

    private ArrayNode components() {
        ArrayNode rows = MAPPER.createArrayNode();
        // One dropdown rather than two rows of buttons. Discord takes five
        // buttons to a row and rejects the whole message at six, so every new
        // page used to mean rearranging the rows; a select holds twenty-five
        // and takes the page count off the critical path for good.
        ArrayNode r1 = row(rows);
        ObjectNode picker = select(r1, "page:pick", "Page - " + label(page));
        ArrayNode opts = picker.putArray("options");
        for (Page pg : Page.values()) {
            if (pg == Page.SEARCH) continue;   // reached by the Search button, not a page of its own
            if (pg == Page.HIVE && DoughBayClient.hiveAccounts().size() < 2) continue;
            option(opts, label(pg), pg.name(), pg == page);
        }
        // Which account the figures are for. Offered only when there is more
        // than one, so a single client's panel is exactly what it always was.
        List<String> accounts = DoughBayClient.hiveAccounts();
        if (accounts.size() > 1) {
            ArrayNode ra = row(rows);
            ObjectNode apick = select(ra, "acct:pick",
                    "Account - " + (account.isBlank() ? "all" : account));
            ArrayNode aopts = apick.putArray("options");
            option(aopts, "All accounts", "*", account.isBlank());
            for (String name : accounts) {
                option(aopts, name.equalsIgnoreCase(DoughBayClient.account()) ? name + " (this client)" : name,
                        name, name.equalsIgnoreCase(account));
            }
        }
        ArrayNode r2 = row(rows);
        button(r2, "ctl:search", "Search", 2, false);
        // Discord allows five buttons to a row and silently rejects the whole
        // message with "Invalid Form Body" at six, so Start and Stop get their
        // own row rather than being crammed onto the end of the pages.
        ArrayNode controls = row(rows);
        AutomationSessionController.SessionSnapshot s = session.snapshot();
        boolean running = s.state() != AutomationSessionController.State.STOPPED && s.state() != AutomationSessionController.State.PAUSED;
        // Start and Stop reach this client's own session and nobody else's,
        // so they are held back while another account's figures are on screen
        // rather than quietly stopping the wrong trader.
        boolean mine = account.isBlank() || account.equalsIgnoreCase(DoughBayClient.account());
        boolean everyone = account.isBlank() && DoughBayClient.hiveAccounts().size() > 1;
        if (everyone) {
            // "All accounts" on screen means all of them: this client
            // directly, the others through the ledger. Both stay live, since
            // one may be running while another is parked.
            button(controls, "ctl:start", "Start all", 3, false);
            button(controls, "ctl:stop", "Stop all", 4, false);
        } else if (mine) {
            button(controls, "ctl:start", s.state() == AutomationSessionController.State.PAUSED ? "Resume" : "Start", 3, running);
            button(controls, "ctl:stop", "Stop", 4, !running && s.state() != AutomationSessionController.State.PAUSED);
        } else {
            // Another account is on screen: the buttons drive that client,
            // through the shared ledger, and say so on their face. Its last
            // checkpointed state picks the label; the client itself has the
            // final word and answers back.
            dev.doughbay.fabric.HiveControl ctl = DoughBayClient.hiveControl();
            String theirs = ctl == null ? "" : ctl.stateOf(account);
            boolean theirsPaused = "PAUSED".equals(theirs);
            boolean theirsStopped = theirs.isBlank() || "STOPPED".equals(theirs);
            button(controls, "ctl:start", (theirsPaused ? "Resume " : "Start ") + account, 3, ctl == null || (!theirsPaused && !theirsStopped));
            button(controls, "ctl:stop", "Stop " + account, 4, ctl == null || theirsStopped);
        }
        // Replies to orders sent to the other clients, as they come in.
        dev.doughbay.fabric.HiveControl replies = DoughBayClient.hiveControl();
        if (replies != null && DoughBayClient.hiveAccounts().size() > 1) {
            for (dev.doughbay.fabric.HiveControl.Result r : replies.repliesSince(controlRepliesSeenAt)) {
                controlRepliesSeenAt = Math.max(controlRepliesSeenAt, r.doneAt());
                if (!r.target().equalsIgnoreCase(DoughBayClient.account())) note(r.target() + ": " + r.result());
            }
        }
        if (page == Page.TUNE) {
            List<String> groups = new ArrayList<>(Tuning.groups());
            if (tuneGroup.isBlank() && !groups.isEmpty()) tuneGroup = groups.get(0);
            ArrayNode r3 = row(rows);
            ObjectNode gsel = select(r3, "tune:group", "Settings group");
            ArrayNode gopts = gsel.putArray("options");
            for (String g : groups) option(gopts, g, g, g.equals(tuneGroup));
            option(gopts, "Presets", "#presets", "#presets".equals(tuneGroup));
            ArrayNode r4 = row(rows);
            if ("#presets".equals(tuneGroup)) {
                ObjectNode psel = select(r4, "tune:preset", "Apply a preset");
                ArrayNode popts = psel.putArray("options");
                for (String name : Tuning.PRESETS.keySet()) option(popts, name, name, false);
            } else {
                ObjectNode ksel = select(r4, "tune:key", "Setting");
                ArrayNode kopts = ksel.putArray("options");
                int n = 0;
                for (Tuning.Setting st : Tuning.SETTINGS) {
                    if (!st.group().equals(tuneGroup) || n >= 25) continue;
                    option(kopts, st.label() + " = " + st.format(Tuning.get(st.key())), st.key(), st.key().equals(tuneKey));
                    n++;
                }
                Tuning.Setting current = setting(tuneKey);
                ArrayNode r5 = row(rows);
                boolean none = current == null;
                boolean toggle = current != null && current.kind() == Tuning.Kind.TOGGLE;
                button(r5, "tune:toggle", "Toggle", 1, none || !toggle);
                button(r5, "tune:set", "Set value", 1, none || toggle);
                button(r5, "tune:dec", "−", 2, none || toggle);
                button(r5, "tune:inc", "+", 2, none || toggle);
                button(r5, "tune:reset", "Reset", 2, none);
            }
        }
        return rows;
    }

    private static ArrayNode row(ArrayNode rows) {
        ObjectNode r = rows.addObject();
        r.put("type", 1);
        return r.putArray("components");
    }

    private static void button(ArrayNode row, String id, String label, int style, boolean disabled) {
        ObjectNode b = row.addObject();
        b.put("type", 2);
        b.put("style", style);
        b.put("label", label);
        b.put("custom_id", id);
        if (disabled) b.put("disabled", true);
    }

    private static ObjectNode select(ArrayNode row, String id, String placeholder) {
        ObjectNode s = row.addObject();
        s.put("type", 3);
        s.put("custom_id", id);
        s.put("placeholder", placeholder);
        return s;
    }

    private static void option(ArrayNode opts, String label, String value, boolean selected) {
        ObjectNode o = opts.addObject();
        o.put("label", label.length() > 100 ? label.substring(0, 100) : label);
        o.put("value", value);
        if (selected) o.put("default", true);
    }

    private static String label(Page p) {
        return switch (p) {
            case MONEY -> "Money";
            case STATUS -> "Status";
            case HIVE -> "Hive";
            case LISTINGS -> "Listings";
            case OPPS -> "Opps";
            case ORDERS -> "Orders";
            case RIVALS -> "Rivals";
            case EVASION -> "Evasion";
            case PAYROLL -> "Payroll";
            case TUNE -> "Tune";
            case SEARCH -> "Search";
        };
    }

    // ---------------------------------------------------------------- interactions

    private void onInteraction(JsonNode d) {
        String id = d.path("id").asText("");
        String token = d.path("token").asText("");
        int type = d.path("type").asInt(0);
        String user = d.path("member").path("user").path("id").asText(d.path("user").path("id").asText(""));
        try {
            if (!user.equals(config.operatorId())) {
                rest.respond(id, token, ephemeral("This panel answers only to its operator."));
                return;
            }
            String customId = d.path("data").path("custom_id").asText("");
            if (type == 5) {
                handleModal(d, customId);
                rest.respond(id, token, "{\"type\":6}");
                refreshNow = true;
                return;
            }
            if (type != 3) return;
            if (customId.equals("ctl:search")) {
                rest.respond(id, token, modal("modal:search", "Search the markets", "item", "Item, for example golden apple", ""));
                return;
            }
            if (customId.equals("tune:set")) {
                Tuning.Setting st = setting(tuneKey);
                if (st == null) {
                    rest.respond(id, token, ephemeral("Pick a setting first."));
                    return;
                }
                rest.respond(id, token, modal("modal:set:" + st.key(), st.label(),
                        "value", st.description().length() > 100 ? st.description().substring(0, 100) : st.description(),
                        st.format(Tuning.get(st.key()))));
                return;
            }
            String reply = handleComponent(d, customId);
            rest.respond(id, token, "{\"type\":6}");
            if (reply != null) note(reply);
            refreshNow = true;
        } catch (Exception e) {
            log.warn("DoughBay Discord interaction: {}", e.toString());
        }
    }

    private String handleComponent(JsonNode d, String customId) {
        JsonNode values = d.path("data").path("values");
        String value = values.isArray() && values.size() > 0 ? values.get(0).asText("") : "";
        if (customId.equals("acct:pick")) {
            account = "*".equals(value) ? "" : value;
            return account.isBlank() ? "Showing every account" : "Showing " + account;
        }
        if (customId.equals("page:pick")) {
            if (!value.isBlank()) page = Page.valueOf(value);
            return null;
        }
        if (customId.startsWith("page:")) {
            page = Page.valueOf(customId.substring(5));
            return null;
        }
        switch (customId) {
            case "ctl:start" -> control(dev.doughbay.fabric.HiveControl.START);
            case "ctl:stop" -> control(dev.doughbay.fabric.HiveControl.STOP);
            case "tune:group" -> {
                tuneGroup = value;
                tuneKey = "";
            }
            case "tune:key" -> tuneKey = value;
            case "tune:preset" -> onClient(() -> {
                int changed = Tuning.applyPreset(value);
                note("Preset " + value + " applied: " + changed + " setting(s) changed");
            });
            case "tune:toggle" -> adjust(st -> Tuning.get(st.key()) >= 0.5 ? 0 : 1);
            case "tune:dec" -> adjust(st -> Tuning.get(st.key()) - step(st));
            case "tune:inc" -> adjust(st -> Tuning.get(st.key()) + step(st));
            case "tune:reset" -> {
                Tuning.Setting st = setting(tuneKey);
                if (st != null) onClient(() -> {
                    Tuning.reset(st.key());
                    note(st.label() + " reset to " + st.format(Tuning.get(st.key())));
                });
            }
            default -> { }
        }
        return null;
    }

    private void handleModal(JsonNode d, String customId) {
        String input = "";
        for (JsonNode row : d.path("data").path("components")) {
            for (JsonNode comp : row.path("components")) {
                if (comp.has("value")) input = comp.get("value").asText("");
            }
        }
        final String text = input.strip();
        if (customId.equals("modal:search")) {
            searchTerm = text.toLowerCase(Locale.ROOT).replace(' ', '_');
            page = Page.SEARCH;
            return;
        }
        if (customId.startsWith("modal:set:")) {
            Tuning.Setting st = setting(customId.substring("modal:set:".length()));
            if (st == null) return;
            try {
                double v = Double.parseDouble(text.replace("%", "").replace(",", "").strip());
                onClient(() -> {
                    double applied = Tuning.set(st.key(), v);
                    note(st.label() + " set to " + st.format(applied));
                });
            } catch (NumberFormatException e) {
                note("Not a number: " + text);
            }
        }
    }

    private interface Adjuster {
        double apply(Tuning.Setting st);
    }

    private void adjust(Adjuster f) {
        Tuning.Setting st = setting(tuneKey);
        if (st == null) return;
        onClient(() -> {
            double applied = Tuning.set(st.key(), f.apply(st));
            note(st.label() + " → " + st.format(applied));
        });
    }

    private static double step(Tuning.Setting st) {
        double span = st.max() - st.min();
        if (st.kind() == Tuning.Kind.PERCENT) return span >= 50 ? 5 : 1;
        if (span <= 20) return 1;
        if (span <= 200) return 5;
        return Math.max(1, Math.floor(span / 40));
    }

    /**
     * Start, Resume or Stop for whichever account is on screen: this client's
     * own session directly, another client's through the shared ledger.
     */
    private void control(String command) {
        String verb = command.equals(dev.doughbay.fabric.HiveControl.STOP) ? "Stop" : "Start";
        dev.doughbay.fabric.HiveControl ctl = DoughBayClient.hiveControl();
        List<String> accounts = DoughBayClient.hiveAccounts();
        if (account.isBlank() && accounts.size() > 1) {
            // Everyone: this client now, the rest by order.
            onClient(() -> note(DoughBayClient.account() + ": " + dev.doughbay.fabric.HiveControl.apply(session, command)));
            int sent = 0;
            for (String name : accounts) {
                if (name.equalsIgnoreCase(DoughBayClient.account())) continue;
                if (ctl != null && ctl.post(name, command, "operator")) sent++;
            }
            note("Sent " + verb + " to " + sent + " other client(s); their replies show here");
            refreshNow = true;
            return;
        }
        boolean mine = account.isBlank() || account.equalsIgnoreCase(DoughBayClient.account());
        if (mine) {
            onClient(() -> note(dev.doughbay.fabric.HiveControl.apply(session, command)));
            return;
        }
        if (ctl == null) {
            note("No hive to send that to");
            return;
        }
        note(ctl.post(account, command, "operator")
                ? "Sent " + verb + " to " + account + "; its reply shows here"
                : "Could not reach " + account);
        refreshNow = true;
    }

    private void onClient(Runnable r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) mc.execute(() -> {
            try {
                r.run();
            } catch (Exception e) {
                note("Failed: " + e.getMessage());
            }
            refreshNow = true;
        });
    }

    private void note(String text) {
        notice = text;
        noticeAt = System.currentTimeMillis();
        log.info("DoughBay Discord: {}", text);
    }

    private static Tuning.Setting setting(String key) {
        if (key == null || key.isBlank()) return null;
        for (Tuning.Setting st : Tuning.SETTINGS) if (st.key().equals(key)) return st;
        return null;
    }

    private static String ephemeral(String text) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("type", 4);
        ObjectNode data = r.putObject("data");
        data.put("content", text);
        data.put("flags", 64);
        return r.toString();
    }

    private static String modal(String customId, String title, String field, String label, String value) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("type", 9);
        ObjectNode data = r.putObject("data");
        data.put("custom_id", customId);
        data.put("title", title.length() > 45 ? title.substring(0, 45) : title);
        ArrayNode comps = data.putArray("components");
        ObjectNode row = comps.addObject();
        row.put("type", 1);
        ObjectNode input = row.putArray("components").addObject();
        input.put("type", 4);
        input.put("custom_id", field);
        input.put("style", 1);
        input.put("label", label.length() > 45 ? label.substring(0, 45) : label);
        input.put("required", true);
        if (value != null && !value.isBlank()) input.put("value", value);
        return r.toString();
    }

    // ---------------------------------------------------------------- pages

    /**
     * The account the page is about, and the few figures that were being taken
     * from whichever client happens to be hosting the panel.
     *
     * <p>Picking an account changed the ledger queries but not the session:
     * the state in the subtitle, the bank beside it, the trade count and - most
     * visibly - the slot grid all came from the host's own controller, so both
     * accounts showed the host's slots however many each really had. Anything
     * the host cannot know first-hand comes out of the shared ledger instead.
     *
     * @param name    the account being shown, or empty for the whole hive
     * @param state   what that account's session last said it was doing
     * @param bank    its money, or the hive's together
     * @param trades  trades started in its session
     * @param listed  listings it has up
     * @param live    whether this is the client running the code, so the
     *                in-memory queue and history can be shown
     */
    private record Viewed(String name, String state, long bank, int trades, int listed, boolean live) {
        String label() {
            return name.isBlank() ? "all accounts" : name;
        }
    }

    /**
     * Reads whoever is being looked at. The host answers for itself from its
     * own controller, because that is fresher than the checkpoint it writes;
     * anybody else is read from the ledger, and the hive is added up.
     */
    private Viewed viewed(AutomationSessionController.SessionSnapshot s, long balance) {
        String me = DoughBayClient.account();
        boolean live = account.isBlank() ? DoughBayClient.hiveAccounts().size() < 2 : account.equalsIgnoreCase(me);
        if (live) {
            return new Viewed(account, s.state().name(), balance, s.tradesStarted(), s.openListings(), true);
        }
        try (Database db = new Database(ledgerPath)) {
            long bank = bankInView(db, balance);
            int listed = openInView(db, s.openListings());
            if (account.isBlank()) {
                // The hive: every session's trades, and every account's slots.
                int trades = 0;
                try (PreparedStatement ps = db.connection().prepareStatement(
                        "SELECT COALESCE(SUM(trades_started),0) FROM automation_session_state WHERE client <> ''");
                     ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) trades = rs.getInt(1);
                }
                return new Viewed("", "HIVE", bank, trades, listed, false);
            }
            String state = "-";
            int trades = 0;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT controller_state, trades_started FROM automation_session_state WHERE client=?")) {
                ps.setString(1, account);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        state = rs.getString(1);
                        trades = rs.getInt(2);
                    }
                }
            }
            return new Viewed(account, state, bank, trades, listed, false);
        } catch (Exception e) {
            // Falling back to the host's own figures is wrong for another
            // account, so say whose they are rather than pass them off.
            return new Viewed(me, s.state().name(), balance, s.tradesStarted(), s.openListings(), true);
        }
    }

    private StatsImage.Page buildPage() {
        AutomationSessionController.SessionSnapshot s = session.snapshot();
        MarketWatcher watcher = DoughBayClient.watcher();
        MarketWatcher.Snapshot m = watcher == null ? null : watcher.snapshot();
        long balance = m == null ? 0 : m.accountBalance();
        String stateColor = switch (s.state()) {
            case PAUSED -> "gold";
            case STOPPED -> "red";
            default -> "green";
        };
        Viewed v = viewed(s, balance);
        // "SCANNING · 10:45 · bank $8.7m" on its own, as it always read; with a
        // hive, whose figures these are comes first, because that is the thing
        // it is now possible to be wrong about.
        String head = account.isBlank() && !v.live()
                ? "hive of " + DoughBayClient.hiveAccounts().size()
                : v.state();
        String subtitle = head + " · " + CLOCK.format(Instant.now()) + " · bank " + money(v.bank());
        String footer = s.detail().length() > 110 ? s.detail().substring(0, 110) : s.detail();
        // The slot grid belongs to whoever is being looked at, so one account's
        // allowance for one account and everybody's for the hive.
        int slots = (int) Tuning.get("slots.max")
                * (account.isBlank() ? Math.max(1, DoughBayClient.hiveAccounts().size()) : 1);
        return switch (page) {
            case MONEY -> moneyPage(s, v, stateColor, subtitle, footer, slots);
            case STATUS -> statusPage(s, v, stateColor, subtitle, footer);
            case HIVE -> hivePage(stateColor, subtitle, footer);
            case LISTINGS -> listingsPage(s, stateColor, subtitle, footer, slots);
            case OPPS -> oppsPage(m, stateColor, subtitle, footer);
            case ORDERS -> ordersPage(stateColor, subtitle, footer);
            case RIVALS -> rivalsPage(stateColor, subtitle, footer);
            case EVASION -> evasionPage(stateColor, subtitle, footer);
            case PAYROLL -> payrollPage(stateColor, subtitle, footer);
            case TUNE -> tunePage(stateColor, subtitle, footer);
            case SEARCH -> searchPage(m, stateColor, subtitle, footer);
        };
    }

    /**
     * The account filter as a piece of SQL, or nothing at all.
     *
     * <p>Positions have carried an owner since the hive existed, so scoping a
     * page to one account is a where-clause rather than a second table. An
     * empty filter is the whole hive, which is what every one of these queries
     * did before there was more than one client.
     */
    private String scope() {
        return account.isBlank() ? "" : " AND client=?";
    }

    /** Binds the account filter, if there is one, and answers the next free index. */
    private int bindScope(PreparedStatement ps, int index) throws Exception {
        if (account.isBlank()) return index;
        ps.setString(index, account);
        return index + 1;
    }

    /** A statement with the account filter already bound as its first parameter. */
    private PreparedStatement scoped(Database db, String sql) throws Exception {
        PreparedStatement ps = db.connection().prepareStatement(sql);
        bindScope(ps, 1);
        return ps;
    }

    private record Window(int sales, long revenue, long profit, int bought, long paid) {
        /** Profit as a share of what came in; the number that says whether the flips are worth doing. */
        String margin() {
            return revenue > 0 ? String.format(Locale.ROOT, "%.1f%%", profit * 100.0 / revenue) : "-";
        }

        /** What payroll took out of that profit. */
        String payrollShare() {
            return profit > 0 ? String.format(Locale.ROOT, "%.0f%%", paid * 100.0 / profit) : "-";
        }
    }

    /**
     * Realized profit in each of the last N whole hours, oldest first, as the
     * CSV the panel's sparkline draws. One hour is one bar, so the row is the
     * "Profit 1h" figure as it stood at each hour back - where it is against
     * where it has been, which the single number cannot show.
     */
    private String hourlyProfitCsv(Database db, int hours) {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COALESCE(SUM(realized_profit),0) FROM positions "
                        + "WHERE mode='REAL' AND status='SOLD' AND closed_at > ? AND closed_at <= ?" + scope())) {
            for (int i = hours - 1; i >= 0; i--) {
                long to = now - i * 3_600_000L;
                ps.setLong(1, to - 3_600_000L);
                ps.setLong(2, to);
                bindScope(ps, 3);
                long p = 0;
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) p = (long) rs.getDouble(1);
                }
                if (sb.length() > 0) sb.append(',');
                sb.append(p);
            }
        } catch (Exception e) {
            return "0";
        }
        return sb.toString();
    }

    private Window window(Database db, long from) throws Exception {
        int sales = 0;
        long revenue = 0;
        long profit = 0;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COUNT(*), COALESCE(SUM(sale_price),0), COALESCE(SUM(realized_profit),0) FROM positions "
                        + "WHERE mode='REAL' AND status='SOLD' AND closed_at > ?" + scope())) {
            ps.setLong(1, from);
            bindScope(ps, 2);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                sales = rs.getInt(1);
                revenue = rs.getLong(2);
                profit = (long) rs.getDouble(3);
            }
        }
        // Bought and paid come from different tables and different clocks, but
        // they belong on the same line as the sales: bought against sold says
        // whether the book is growing, and paid against profit says what is
        // actually left. The hourly webhook report carries both, and the Money
        // page is where people look for them the rest of the time.
        int bought = 0;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COUNT(*) FROM positions WHERE mode='REAL' AND purchased_at > ?" + scope())) {
            ps.setLong(1, from);
            bindScope(ps, 2);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) bought = rs.getInt(1);
            }
        }
        long paid = 0;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COALESCE(SUM(amount),0) FROM payroll_payments "
                        + "WHERE status NOT IN ('FAILED','UNPAID','CARRIED') AND requested_at > ?")) {
            ps.setLong(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) paid = rs.getLong(1);
            }
        } catch (Exception ignored) {
            // no payroll table yet on a fresh ledger; the rest of the page still stands
        }
        return new Window(sales, revenue, profit, bought, paid);
    }

    private StatsImage.Page moneyPage(AutomationSessionController.SessionSnapshot s, Viewed v,
            String color, String subtitle, String footer, int slots) {
        List<String[]> head = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        List<String> icons = new ArrayList<>();
        // Who made each sale, so the hive's list of them says so. Left empty
        // for a single account, where every row would carry the same face.
        List<String> sellers = new ArrayList<>();
        long now = System.currentTimeMillis();
        try (Database db = new Database(ledgerPath)) {
            Window h1 = window(db, now - 3_600_000L);
            Window h6 = window(db, now - 6 * 3_600_000L);
            Window d1 = window(db, now - 24 * 3_600_000L);
            head.add(new String[] {"Bank", money(v.bank()), "gold"});
            head.add(new String[] {"Profit 1h", money(h1.profit()), "green"});
            head.add(new String[] {"Profit 24h", money(d1.profit()), "green"});
            head.add(new String[] {"Margin 1h", h1.margin(), "green"});
            head.add(new String[] {"~spark", "Profit/h · 8h|" + hourlyProfitCsv(db, 8), "green"});
            head.add(new String[] {"Sales 1h", h1.sales() + " sold · " + h1.bought() + " bought"});
            head.add(new String[] {"Sales 6h", String.valueOf(h6.sales()) + " · " + money(h6.profit())});
            head.add(new String[] {"Sales 24h", d1.sales() + " sold · " + d1.bought() + " bought"});
            head.add(new String[] {"Revenue 24h", money(d1.revenue()) + " · margin " + d1.margin()});
            head.add(new String[] {"Payments", money(h1.paid()) + " 1h · " + money(d1.paid()) + " 24h", "gold"});
            head.add(new String[] {"Payroll share", h1.payrollShare() + " 1h · " + d1.payrollShare() + " 24h"});
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(purchase_price),0), COALESCE(SUM(target_price),0) FROM positions "
                            + "WHERE mode='REAL' AND status='LISTED'" + scope())) {
                bindScope(ps, 1);
                ResultSet rs = ps.executeQuery();
                rs.next();
                head.add(new String[] {"Listed", rs.getInt(1) + " · cost " + money(rs.getLong(2))});
                head.add(new String[] {"Asking", money(rs.getLong(3))});
            }
            head.add(new String[] {"Trades", v.trades() + " this session"});
            MarketShare.Share share = marketShare();
            if (share.rank() > 0) {
                head.add(new String[] {"Share 24h", share.describe()});
                head.add(new String[] {"Rank", share.describeRank() + " by sales"});
                if (!share.topPercent().isBlank()) {
                    head.add(new String[] {"Percentile", share.topPercent() + " of all sellers", "green"});
                }
            }
            if (session.quietMarket()) head.add(new String[] {"Mode", "quiet: smaller, steadier trades", "gold"});
            AutomationSessionController.WarmUp warm = session.warmUp();
            if (!warm.ready()) {
                head.add(new String[] {"Warm-up", warm.marketsReady() + "/" + warm.marketsNeeded() + " markets priced", "gold"});
            }
            // "x0.96" was the model's own multiplier, which means nothing to
            // anybody reading the page. Two player counts do: the network is
            // what the one auction house serves, and the shard is who can walk
            // up to us. The multiplier still colours the row.
            dev.doughbay.fabric.PopulationModel pop = DoughBayClient.population();
            if (pop != null && pop.players() > 0) {
                String counts = pop.networkPlayers() > 0
                        ? String.format(Locale.ROOT, "%,d network · %,d on this shard",
                                pop.networkPlayers(), pop.players())
                        : String.format(Locale.ROOT, "%,d on this shard", pop.players());
                head.add(new String[] {"Players", counts,
                        pop.factorNow() >= 1.1 ? "green" : pop.factorNow() <= 0.9 ? "red" : "dim"});
            }
            String rivals = rivalSummary();
            if (!rivals.isEmpty()) head.add(new String[] {"Rivals", rivals});
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT item_key, quantity, purchase_price, sale_price, realized_profit, closed_at, client "
                            + "FROM positions WHERE mode='REAL' AND status='SOLD'" + scope()
                            + " ORDER BY closed_at DESC LIMIT 12")) {
                bindScope(ps, 1);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    rows.add(new String[] {CLOCK.format(Instant.ofEpochMilli(rs.getLong(6))), item(rs.getString(1)) + " x" + rs.getInt(2),
                            money(rs.getLong(3)), money(rs.getLong(4)), signed((long) rs.getDouble(5))});
                    icons.add(rs.getString(1));
                    sellers.add(rs.getString(7));
                }
            }
        } catch (Exception e) {
            rows.add(new String[] {"", "ledger unavailable: " + e.getMessage(), "", "", ""});
        }
        StatsImage.Page money = new StatsImage.Page("Money", subtitle, color, head, v.listed(), slots,
                new String[] {"Time", "Last sales", "Cost", "Sold", "Profit"}, new boolean[] {false, false, true, true, true},
                rows, icons, 1, footer);
        boolean hive = account.isBlank() && DoughBayClient.hiveAccounts().size() > 1;
        return hive ? money.withRowFaces(sellers) : money;
    }

    /**
     * Every account trading against this ledger, side by side.
     *
     * <p>Two clients working the same auction house are one business with two
     * purses, and the question is both what they made together and which of
     * them is carrying it. Everything here comes out of the shared ledger, so
     * whichever client happens to be hosting the panel can answer for all of
     * them; only its own balance is first-hand, and the others come from the
     * heartbeat each client writes every twenty seconds.
     */
    private StatsImage.Page hivePage(String color, String subtitle, String footer) {
        List<String[]> head = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        long totalBank = 0;
        long totalProfit = 0;
        int totalSales = 0;
        long totalProfit24 = 0;
        int totalSales24 = 0;
        int totalListed = 0;
        long totalAsk = 0;
        try (Database db = new Database(ledgerPath)) {
            List<String> accounts = new ArrayList<>(DoughBayClient.hiveAccounts());
            if (accounts.isEmpty()) {
                String me = DoughBayClient.account();
                if (!me.isBlank()) accounts.add(me);
            }
            for (String name : accounts) {
                long bank = clientBalance(db, name);
                if (name.equalsIgnoreCase(DoughBayClient.account())) {
                    MarketWatcher w = DoughBayClient.watcher();
                    MarketWatcher.Snapshot snap = w == null ? null : w.snapshot();
                    if (snap != null && snap.accountBalance() > 0) bank = snap.accountBalance();
                }
                int listed = 0;
                long ask = 0;
                try (PreparedStatement ps = db.connection().prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(target_price),0) FROM positions "
                                + "WHERE mode='REAL' AND status='LISTED' AND client=?")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            listed = rs.getInt(1);
                            ask = rs.getLong(2);
                        }
                    }
                }
                int sales1 = 0;
                long profit1 = 0;
                int sales24 = 0;
                long profit24 = 0;
                try (PreparedStatement ps = db.connection().prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(realized_profit),0) FROM positions "
                                + "WHERE mode='REAL' AND status='SOLD' AND closed_at > ? AND client=?")) {
                    ps.setLong(1, now - 3_600_000L);
                    ps.setString(2, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sales1 = rs.getInt(1);
                            profit1 = (long) rs.getDouble(2);
                        }
                    }
                }
                try (PreparedStatement ps = db.connection().prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(realized_profit),0) FROM positions "
                                + "WHERE mode='REAL' AND status='SOLD' AND closed_at > ? AND client=?")) {
                    ps.setLong(1, now - 24 * 3_600_000L);
                    ps.setString(2, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            sales24 = rs.getInt(1);
                            profit24 = (long) rs.getDouble(2);
                        }
                    }
                }
                rows.add(new String[] {name, clientSession(db, name), bank >= 0 ? money(bank) : "?",
                        listed + " · " + money(ask), money(profit1) + " (" + sales1 + ")",
                        money(profit24) + " (" + sales24 + ")"});
                if (bank > 0) totalBank += bank;
                totalListed += listed;
                totalAsk += ask;
                totalProfit += profit1;
                totalSales += sales1;
                totalProfit24 += profit24;
                totalSales24 += sales24;
            }
            head.add(new String[] {"Accounts", accounts.size() + " trading", accounts.size() > 1 ? "green" : "dim"});
            head.add(new String[] {"Hive bank", money(totalBank), "gold"});
            head.add(new String[] {"Listed", totalListed + " · asking " + money(totalAsk)});
            head.add(new String[] {"Profit 1h", money(totalProfit) + " · " + totalSales + " sold", "green"});
            head.add(new String[] {"Profit 24h", money(totalProfit24) + " · " + totalSales24 + " sold", "green"});
            MarketShare.Share share = marketShare();
            if (share.rank() > 0) {
                head.add(new String[] {"Share of market", share.describe(), "green"});
                head.add(new String[] {"Busiest of ours", share.ranked() + " " + share.describeRank()});
                // Both halves of that ratio are counted inside the feed, so the
                // reader can see how much of the market the feed reaches.
                if (!share.describeCoverage().isBlank()) {
                    head.add(new String[] {"Sampling", share.describeCoverage(), "dim"});
                }
            }
            head.add(new String[] {"Viewing", account.isBlank() ? "all accounts" : account});
            head.add(new String[] {"Panel host", DoughBayClient.account() + " (this client)"});
        } catch (Exception e) {
            rows.add(new String[] {"", "", "ledger unavailable: " + e.getMessage(), "", "", ""});
        }
        if (rows.isEmpty()) rows.add(new String[] {"", "", "no clients are reporting yet", "", "", ""});
        int accountsSeen = Math.max(1, rows.size());
        return new StatsImage.Page("Hive", subtitle, color, head,
                totalListed, accountsSeen * (int) Tuning.get("slots.max"),
                new String[] {"Account", "Doing", "Bank", "Listed · asking", "1h profit", "24h profit"},
                new boolean[] {false, false, true, true, true, true}, rows, footer);
    }

    /** What one client's heartbeat last said it was worth, or -1. */
    private long clientBalance(Database db, String name) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT balance FROM api_clients WHERE name=? ORDER BY seen_at DESC LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * What one client's session says it is doing, and how long ago it said so.
     *
     * <p>The checkpoint has been keyed by account since the two of them stopped
     * overwriting each other, which is what makes it possible to answer this
     * for a client that is not the one running this code.
     */
    private String clientSession(Database db, String name) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT controller_state, updated_at FROM automation_session_state WHERE client=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "-";
                String state = rs.getString(1).toLowerCase(Locale.ROOT).replace('_', ' ');
                // A checkpoint that stopped moving is the one thing this page
                // can say that the client itself cannot: it has gone quiet.
                long age = System.currentTimeMillis() - rs.getLong(2);
                return age > 300_000 ? state + " (" + ago(age) + " ago)" : state;
            }
        } catch (Exception e) {
            return "-";
        }
    }

    /**
     * The money on show: this client's own balance when it is the one being
     * looked at, and the hive's together when the view is the hive.
     *
     * <p>Only this client knows its own balance first-hand; the others' come
     * from their heartbeats, a few seconds old at worst and the only figure
     * there is.
     */
    private long bankInView(Database db, long ownBalance) {
        String me = DoughBayClient.account();
        if (!account.isBlank()) {
            if (account.equalsIgnoreCase(me)) return ownBalance;
            long theirs = clientBalance(db, account);
            return theirs >= 0 ? theirs : ownBalance;
        }
        if (DoughBayClient.hiveAccounts().size() < 2) return ownBalance;
        // One balance per account, the freshest. A restart leaves the old
        // session's heartbeat row behind under a new client id until it ages
        // out, so summing every row counted each account twice - the hive of
        // two read like a hive of four, and the bank line doubled. Keyed by
        // name, the stale row is the same account and simply loses to the new.
        java.util.Map<String, Long> latest = new java.util.HashMap<>();
        java.util.Map<String, Long> latestAt = new java.util.HashMap<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT name, balance, seen_at FROM api_clients WHERE balance >= 0 AND name <> ''");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String nm = rs.getString(1).toLowerCase(Locale.ROOT);
                long at = rs.getLong(3);
                if (!latestAt.containsKey(nm) || at > latestAt.get(nm)) {
                    latestAt.put(nm, at);
                    latest.put(nm, rs.getLong(2));
                }
            }
        } catch (Exception ignored) {
            // one unreadable heartbeat should not blank the bank line
        }
        if (latest.isEmpty()) return ownBalance;
        // This client's own reading is fresher than the one it last wrote.
        latest.put(me.toLowerCase(Locale.ROOT), ownBalance);
        long total = 0;
        for (long b : latest.values()) total += b;
        return total;
    }

    /**
     * Listings on show. This client counts its own from the session it is
     * running; anybody else's, and the hive's together, come from the ledger.
     */
    private int openInView(Database db, int own) {
        if (!account.isBlank() && account.equalsIgnoreCase(DoughBayClient.account())) return own;
        if (account.isBlank() && DoughBayClient.hiveAccounts().size() < 2) return own;
        try (PreparedStatement ps = scoped(db,
                "SELECT COUNT(*) FROM positions WHERE mode='REAL' AND status='LISTED'" + scope());
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : own;
        } catch (Exception e) {
            return own;
        }
    }

    /**
     * Our slice of the auction house, counted inside the server's own feed on
     * both sides so the sampling cancels. See {@link MarketShare} for why the
     * obvious alternative overstates it twentyfold.
     */
    private MarketShare.Share marketShare() {
        List<String> accounts = new ArrayList<>(DoughBayClient.hiveAccounts());
        String me = DoughBayClient.account();
        if (accounts.isEmpty() && !me.isBlank()) accounts.add(me);
        if (!account.isBlank()) accounts = List.of(account);
        return MarketShare.of(ledgerPath, 24 * 3_600_000L, accounts);
    }

    /** The one-line rival summary the hourly report carries. */
    private static String rivalSummary() {
        try {
            dev.doughbay.fabric.RivalIntel intel = DoughBayClient.rivalIntel();
            if (intel == null) return "";
            String status = intel.shortStatus();
            if (status == null || status.isBlank()) return "quiet";
            // The status names itself ("rivals 12/12") and the row is already
            // labelled Rivals; printed together it read "Rivals rivals 12/12".
            return status.regionMatches(true, 0, "rivals ", 0, 7) ? status.substring(7) : status;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * What it is doing, what it is about to do, and what it has just done.
     *
     * <p>Every other page answers "how much money". This one answers "is it
     * working", which is the question actually asked when something looks
     * wrong, and the one that took a log file to answer until now.
     */
    private StatsImage.Page statusPage(AutomationSessionController.SessionSnapshot s, Viewed v,
            String color, String subtitle, String footer) {
        List<String[]> head = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        long now = System.currentTimeMillis();

        // The queue and the history live in this client's memory and nowhere
        // else, so this page can only ever be about the client hosting the
        // panel. Say so rather than let it read as the account that is picked.
        String me = DoughBayClient.account();
        if (!me.isBlank() && DoughBayClient.hiveAccounts().size() > 1) {
            head.add(new String[] {"Client", me + " (hosts the panel)",
                    v.live() || account.isBlank() ? "dim" : "gold"});
            if (!v.live() && !account.isBlank()) {
                head.add(new String[] {"Note", account + "'s queue is not visible from here", "gold"});
            }
        }
        head.add(new String[] {"Doing", s.state().name().toLowerCase(Locale.ROOT).replace('_', ' '),
                s.active() ? "green" : "gold"});
        java.util.List<String> planned = java.util.List.of();
        try {
            planned = session.plannedWork();
        } catch (Throwable ignored) {
            // a status page must never be the thing that breaks
        }
        head.add(new String[] {"Queued", planned.isEmpty() ? "nothing" : planned.size() + " step(s)"});
        if (!s.detail().isBlank()) {
            head.add(new String[] {"Detail", s.detail().length() > 64 ? s.detail().substring(0, 64) : s.detail()});
        }
        head.add(new String[] {"Since", ago(now - s.updatedAtMillis())});
        // The stall watch, said out loud whether or not it is worried: a guard
        // that only speaks when unhappy cannot be told from one that is dead.
        String watch = DoughBayClient.livenessVerdict();
        head.add(new String[] {"Stall watch", watch,
                watch.startsWith("still") ? "gold" : "dim"});
        head.add(new String[] {"Listings", listedNow(s) + " up"});
        head.add(new String[] {"Trades", s.tradesStarted() + " this session"});
        if (s.unresolvedExposure()) head.add(new String[] {"Exposure", "unresolved; nothing may run", "red"});

        // The queue first, in the order it will happen, then what already
        // happened underneath it. One feed, reading forwards from now.
        for (int i = 0; i < planned.size() && rows.size() < 12; i++) {
            String w = planned.get(i);
            rows.add(new String[] {"next", (i == 0 ? "now" : "+" + i), w.length() > 74 ? w.substring(0, 74) : w});
        }
        try {
            List<AutomationSessionController.Action> actions = session.recentActions();
            for (int i = actions.size() - 1; i >= 0 && rows.size() < 26; i--) {
                AutomationSessionController.Action a = actions.get(i);
                String what = a.detail().isBlank() ? a.state().toLowerCase(Locale.ROOT).replace('_', ' ') : a.detail();
                if (what.length() > 74) what = what.substring(0, 74);
                rows.add(new String[] {CLOCK.format(Instant.ofEpochMilli(a.at())), ago(now - a.at()), what});
            }
        } catch (Throwable t) {
            rows.add(new String[] {"", "", "history unavailable: " + t});
        }
        if (rows.isEmpty()) rows.add(new String[] {"", "", "nothing queued and nothing recorded yet"});

        return new StatsImage.Page("Status", subtitle, color, head, 0, 0,
                new String[] {"When", "Ago", "Queue, then what it just did"},
                new boolean[] {false, true, false}, rows, footer);
    }

    /** "4m" or "2h 10m", for how long ago something was. */
    private static String ago(long millis) {
        long mins = Math.max(0, millis / 60_000L);
        if (mins < 60) return mins + "m";
        return (mins / 60) + "h " + (mins % 60) + "m";
    }

    private StatsImage.Page listingsPage(AutomationSessionController.SessionSnapshot s, String color, String subtitle, String footer, int slots) {
        List<String[]> rows = new ArrayList<>();
        List<String[]> head = new ArrayList<>();
        List<String> icons = new ArrayList<>();
        long now = System.currentTimeMillis();
        int listed = listedNow(s);
        try (Database db = new Database(ledgerPath);
             PreparedStatement ps = scoped(db,
                     "SELECT item_key, quantity, purchase_price, target_price, listed_at FROM positions "
                             + "WHERE mode='REAL' AND status='LISTED'" + scope() + " ORDER BY listed_at DESC LIMIT 18");
             ResultSet rs = ps.executeQuery()) {
            listed = openInView(db, listed);
            long cost = 0;
            long ask = 0;
            int n = 0;
            while (rs.next()) {
                long age = Math.max(0, now - rs.getLong(5)) / 60_000;
                rows.add(new String[] {item(rs.getString(1)) + " x" + rs.getInt(2), money(rs.getLong(3)), money(rs.getLong(4)),
                        signed(rs.getLong(4) - rs.getLong(3)), age + " min"});
                icons.add(rs.getString(1));
                cost += rs.getLong(3);
                ask += rs.getLong(4);
                n++;
            }
            head.add(new String[] {"Open", listed + " of " + slots});
            head.add(new String[] {"Newest " + n, "cost " + money(cost)});
            head.add(new String[] {"Asking", money(ask), "gold"});
        } catch (Exception e) {
            rows.add(new String[] {"ledger unavailable: " + e.getMessage(), "", "", "", ""});
        }
        return new StatsImage.Page("Listings", subtitle, color, head, listed, slots,
                new String[] {"Item", "Cost", "Ask", "Margin", "Up for"}, new boolean[] {false, true, true, true, true},
                rows, icons, footer);
    }

    private StatsImage.Page oppsPage(MarketWatcher.Snapshot m, String color, String subtitle, String footer) {
        List<String[]> rows = new ArrayList<>();
        List<String[]> head = new ArrayList<>();
        List<String> icons = new ArrayList<>();
        if (m != null) {
            head.add(new String[] {"Signals", String.valueOf(m.opportunities().size())});
            head.add(new String[] {"Markets", String.valueOf(m.markets().size())});
            head.add(new String[] {"Feed", m.status().length() > 30 ? m.status().substring(0, 30) : m.status()});
            int n = 0;
            for (Opportunity o : m.opportunities()) {
                if (n++ >= 14) break;
                Listing l = o.listing();
                rows.add(new String[] {item(l.itemId()) + " x" + l.itemCount(), money(o.buyPrice()), money(o.recommendedSellPrice()),
                        signed((long) o.expectedNetProfit()), String.format(Locale.ROOT, "%.0f%%", o.expectedRoiPercent()),
                        String.format(Locale.ROOT, "%.0f%%", o.confidence() * 100)});
                icons.add(l.itemId());
            }
        }
        return new StatsImage.Page("Opportunities", subtitle, color, head, 0, 0,
                new String[] {"Item", "Buy", "Sell", "Profit", "ROI", "Conf"}, new boolean[] {false, true, true, true, true, true},
                rows, icons, footer);
    }

    private StatsImage.Page ordersPage(String color, String subtitle, String footer) {
        List<String[]> rows = new ArrayList<>();
        List<String[]> head = new ArrayList<>();
        List<String> icons = new ArrayList<>();
        OrderBook book = DoughBayClient.orderBook();
        head.add(new String[] {"Book", book == null ? "off" : book.orders().size() + " open orders"});
        head.add(new String[] {"Read", book == null || book.readAt() == 0 ? "never" : CLOCK.format(Instant.ofEpochMilli(book.readAt()))});
        head.add(new String[] {"Bids", Tuning.get("orders.bid") >= 0.5 ? "on · " + (int) Tuning.get("orders.bid_slots") + " slots" : "off", Tuning.get("orders.bid") >= 0.5 ? "green" : "dim"});
        long holding = 0;
        for (AutomatedExecutionDriver.OwnOrderRow r : session.ownOrderViews()) {
            holding += r.unitPrice() * r.remaining();
            rows.add(new String[] {item(r.itemId()), String.valueOf(r.requested()), String.valueOf(r.delivered()), money(r.unitPrice()),
                    r.remaining() == 0 ? "complete" : r.remaining() + " to go"});
            icons.add(r.itemId());
        }
        head.add(new String[] {"Your bids", rows.size() + " · holding " + money(holding)});
        return new StatsImage.Page("Orders", subtitle, color, head, 0, 0,
                new String[] {"Your order", "Asked", "Delivered", "Each", "State"}, new boolean[] {false, true, true, true, false},
                rows, icons, footer);
    }

    /** Who came near, when, and what the guard did about it - newest first. */
    private StatsImage.Page evasionPage(String color, String subtitle, String footer) {
        List<String[]> head = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        dev.doughbay.fabric.EvasionGuard ev = DoughBayClient.evasionGuard();
        int mode = (int) Tuning.get("evasion.escape");
        boolean armed = ev != null && ev.enabled();
        head.add(new String[] {"Guard", ev == null ? "off" : ev.status(), !armed ? "red" : ev.triggered() ? "gold" : "green"});
        head.add(new String[] {"Pause ring", (int) Tuning.get("evasion.radius_blocks") + " blocks"});
        head.add(new String[] {"Escape ring", (int) Tuning.get("evasion.disconnect_blocks") + " blocks"});
        head.add(new String[] {"On contact", mode == 0 ? "pause only" : mode == 1 ? "teleport away" : "leave the server"});
        // Every client's events come from the shared ledger, so the panel
        // shows the hive and not just the client that hosts it; the account
        // picker narrows it to one. Sightings are the outer ring speaking and
        // are listed, but only a pause or an escape counts as a trigger.
        long now = System.currentTimeMillis();
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm:ss");
        String scope = account.isBlank() ? "" : account;
        try (Database db = new Database(ledgerPath)) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT COUNT(*) FROM evasion_events WHERE at > ? AND action <> 'sighted' AND action NOT LIKE 'move-on%' "
                            + "AND (? = '' OR lower(client) = lower(?))")) {
                ps.setLong(1, now - 24 * 3_600_000L);
                ps.setString(2, scope);
                ps.setString(3, scope);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) head.add(new String[] {"Triggered 24h", String.valueOf(rs.getInt(1))});
                }
            }
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT who, COUNT(*) FROM evasion_events WHERE at > ? AND who <> '' "
                            + "AND (? = '' OR lower(client) = lower(?)) GROUP BY who ORDER BY 2 DESC LIMIT 1")) {
                ps.setLong(1, now - 7 * 24 * 3_600_000L);
                ps.setString(2, scope);
                ps.setString(3, scope);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) head.add(new String[] {"Most seen 7d", rs.getString(1) + " x" + rs.getInt(2), "gold"});
                }
            }
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT at, client, who, distance, action FROM evasion_events "
                            + "WHERE (? = '' OR lower(client) = lower(?)) ORDER BY at DESC LIMIT 14")) {
                ps.setString(1, scope);
                ps.setString(2, scope);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String when = Instant.ofEpochMilli(rs.getLong(1)).atZone(ZoneId.systemDefault()).format(tf);
                        double blocks = rs.getDouble(4);
                        rows.add(new String[] {when, rs.getString(2), rs.getString(3),
                                blocks > 0 ? String.format(Locale.ROOT, "%.0f", blocks) : "", rs.getString(5)});
                    }
                }
            }
        } catch (Exception e) {
            rows.add(new String[] {"", "", "could not read the ledger", "", e.getMessage()});
        }
        if (rows.isEmpty()) rows.add(new String[] {"", "", "nobody has come near", "", ""});
        return new StatsImage.Page("Evasion", subtitle, color, head, 0, 0,
                new String[] {"Time", "Client", "Who", "Blocks", "What happened"},
                new boolean[] {false, false, false, true, false}, rows, footer);
    }

    private StatsImage.Page rivalsPage(String color, String subtitle, String footer) {
        List<String[]> rows = new ArrayList<>();
        List<String[]> head = new ArrayList<>();
        RivalIntel intel = DoughBayClient.rivalIntel();
        RivalIntel.Snapshot r = intel == null ? RivalIntel.Snapshot.empty("off") : intel.snapshot();
        long now = System.currentTimeMillis();
        head.add(new String[] {"Rivals", r.rivals().size() + " tracked · " + r.activeNow() + " active"});
        head.add(new String[] {"Bots", String.valueOf(r.likelyBots())});
        head.add(new String[] {"Underdog", Tuning.get("underdog.enabled") >= 0.5 ? r.shadows().size() + " shadowed" : "off"});
        int n = 0;
        for (RivalIntel.Rival rv : r.rivals()) {
            if (n++ >= 10) break;
            rows.add(new String[] {rv.name(), String.valueOf(rv.sales()), money(rv.revenue()), String.format(Locale.ROOT, "%.0f%%", rv.averageMarginPercent()),
                    rv.status(now)});
        }
        for (RivalIntel.ShadowMarket sm : r.shadows()) {
            if (n++ >= 16) break;
            rows.add(new String[] {"↳ " + item(sm.itemId()) + " x" + sm.count(), sm.flips() + " flips", money(sm.medianBuy()) + "→" + money(sm.medianSale()),
                    String.format(Locale.ROOT, "%.0f%%", sm.marginPercent()), "shadow of " + sm.rival()});
        }
        return new StatsImage.Page("Rivals", subtitle, color, head, 0, 0,
                new String[] {"Name", "Sales", "Revenue", "Margin", "Status"}, new boolean[] {false, true, true, true, false}, rows, footer);
    }

    private StatsImage.Page payrollPage(String color, String subtitle, String footer) {
        List<String[]> rows = new ArrayList<>();
        List<String[]> head = new ArrayList<>();
        Payroll payroll = DoughBayClient.payroll();
        long now = System.currentTimeMillis();
        if (payroll != null) {
            StringBuilder rules = new StringBuilder();
            for (PayrollRepository.Rule rule : payroll.rules()) {
                if (rules.length() > 0) rules.append(", ");
                rules.append(rule.name()).append(' ').append(String.format(Locale.ROOT, "%.0f%%", rule.percent()));
            }
            long day = 0;
            int count = 0;
            int n = 0;
            for (PayrollRepository.Payment p : payroll.payments()) {
                if (p.requestedAt() > now - 24 * 3_600_000L && !"FAILED".equals(p.status())) {
                    day += p.amount();
                    count++;
                }
                if (n++ < 12) rows.add(new String[] {CLOCK.format(Instant.ofEpochMilli(p.requestedAt())), p.name(), money(p.amount()), p.status()});
            }
            head.add(new String[] {"Rules", rules.length() == 0 ? "none" : rules.toString()});
            head.add(new String[] {"Paid 24h", money(day) + " in " + count, "gold"});
            head.add(new String[] {"Status", payroll.status().length() > 30 ? payroll.status().substring(0, 30) : payroll.status()});
        }
        return new StatsImage.Page("Payroll", subtitle, color, head, 0, 0,
                new String[] {"Time", "To", "Amount", "Status"}, new boolean[] {false, false, true, false}, rows, footer);
    }

    private StatsImage.Page tunePage(String color, String subtitle, String footer) {
        List<String[]> rows = new ArrayList<>();
        List<String[]> head = new ArrayList<>();
        String group = tuneGroup.isBlank() ? Tuning.groups().get(0) : tuneGroup;
        head.add(new String[] {"Group", "#presets".equals(group) ? "Presets" : group, "gold"});
        Tuning.Setting current = setting(tuneKey);
        head.add(new String[] {"Selected", current == null ? "pick one below" : current.label() + " = " + current.format(Tuning.get(current.key()))});
        head.add(new String[] {"Presets", String.join(", ", Tuning.PRESETS.keySet())});
        if ("#presets".equals(group)) {
            for (var e : Tuning.PRESETS.entrySet()) rows.add(new String[] {e.getKey(), e.getValue().size() + " settings", ""});
        } else {
            for (Tuning.Setting st : Tuning.SETTINGS) {
                if (!st.group().equals(group)) continue;
                rows.add(new String[] {(st.key().equals(tuneKey) ? "▶ " : "") + st.label(), st.format(Tuning.get(st.key())),
                        Tuning.isDefault(st.key()) ? "default" : "changed"});
            }
        }
        return new StatsImage.Page("Tune", subtitle, color, head, 0, 0,
                new String[] {"Setting", "Value", ""}, new boolean[] {false, true, false}, rows, footer);
    }

    private StatsImage.Page searchPage(MarketWatcher.Snapshot m, String color, String subtitle, String footer) {
        List<String[]> rows = new ArrayList<>();
        List<String[]> head = new ArrayList<>();
        List<String> icons = new ArrayList<>();
        head.add(new String[] {"Search", searchTerm.isBlank() ? "press Search" : searchTerm, "gold"});
        if (m != null && !searchTerm.isBlank()) {
            int n = 0;
            for (MarketStats st : m.markets()) {
                if (!st.itemKey().contains(searchTerm) || !st.hasPrices()) continue;
                if (n++ >= 12) break;
                rows.add(new String[] {item(st.itemKey()) + " x" + st.bucket().exactCount(), money((long) st.quickSalePrice()), money((long) st.weightedMedian()),
                        String.format(Locale.ROOT, "%.1f/h", st.salesPerHour()), String.format(Locale.ROOT, "%.0f%%", st.confidence() * 100)});
                icons.add(st.itemKey());
            }
            long lowest = 0;
            int asks = 0;
            for (Listing l : m.activeListings()) {
                if (!l.itemId().contains(searchTerm)) continue;
                asks++;
                if (lowest == 0 || l.totalPrice() < lowest) lowest = l.totalPrice();
            }
            head.add(new String[] {"Asks up", String.valueOf(asks)});
            head.add(new String[] {"Lowest ask", asks == 0 ? "none" : money(lowest)});
        }
        return new StatsImage.Page("Markets", subtitle, color, head, 0, 0,
                new String[] {"Market", "Quick sale", "Median", "Sales", "Conf"}, new boolean[] {false, true, true, true, true},
                rows, icons, footer);
    }

    // ---------------------------------------------------------------- formatting

    static String money(long v) {
        long a = Math.abs(v);
        String s;
        if (a >= 1_000_000) s = String.format(Locale.ROOT, "$%.2fM", a / 1_000_000.0);
        else if (a >= 10_000) s = String.format(Locale.ROOT, "$%.1fK", a / 1_000.0);
        else s = String.format(Locale.ROOT, "$%,d", a);
        return v < 0 ? "-" + s : s;
    }

    static String signed(long v) {
        return v >= 0 ? "+" + money(v) : money(v);
    }

    static String item(String key) {
        String s = key.indexOf('#') >= 0 ? key.substring(0, key.indexOf('#')) : key;
        s = s.startsWith("minecraft:") ? s.substring(10) : s;
        return s.replace('_', ' ');
    }
}
