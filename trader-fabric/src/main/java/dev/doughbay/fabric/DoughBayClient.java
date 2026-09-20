package dev.doughbay.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.doughbay.core.automation.ContinuousAutomationPolicy;
import dev.doughbay.fabric.automation.AutomationSessionController;
import dev.doughbay.storage.AutomationPersistenceWorker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DoughBay client mod entry point.
 *
 * <p>The market watcher and analysis engine remain independent of Minecraft
 * execution. This class owns the single live {@link AutomatedExecutionDriver}
 * instance, advances it once per client tick, and exposes it to the UI.
 */
public class DoughBayClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("DoughBay");

    private static MarketWatcher watcher;
    /** The startup configuration, replaced only when the API key changes. */
    private static volatile DoughBayConfig activeConfig;
    private static final AutomatedExecutionDriver EXECUTION_DRIVER =
            new AutomatedExecutionDriver();
    private static final AutomationSessionController AUTOMATION_SESSION =
            new AutomationSessionController(EXECUTION_DRIVER);
    private static final DoughBayHudAlerts HUD_ALERTS = new DoughBayHudAlerts();

    /**
     * The alert panel, reachable from the session and the driver.
     *
     * <p>The panel itself is package-private and stays that way; these are the
     * three things worth announcing, and they are announced from wherever the
     * server actually confirms them rather than from wherever we decided to
     * try.
     */
    static void previewHud() { HUD_ALERTS.preview(); }

    static java.util.List<DoughBayHudAlerts.Alert> recentActivity() {
        return HUD_ALERTS.history();
    }

    public static void alertListed(String item, int count, long price) {
        HUD_ALERTS.showListed(item, count, price);
    }

    public static void alertOrderPlaced(String item, int count, long unitPrice) {
        HUD_ALERTS.showOrderPlaced(item, count, unitPrice);
    }

    public static void alertOrderFilled(String item, int count, long unitPrice) {
        HUD_ALERTS.showOrderFilled(item, count, unitPrice);
    }
    private static EvasionGuard EVASION;
    private static AutoJoin AUTO_JOIN;
    private static final ConsignmentDesk CONSIGNMENT = new ConsignmentDesk();
    private static final Liveness LIVENESS = new Liveness();
    /**
     * Sales that actually completed, which is a different thing from trades
     * begun and the only one worth watching for a stall. See the note in
     * {@link Liveness}.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SALES_COMPLETED =
            new java.util.concurrent.atomic.AtomicInteger();
    private static LedgerStatsProvider LEDGER_STATS;
    private static RivalIntel RIVALS;
    private static Payroll PAYROLL;
    private static DemandClock DEMAND;
    static StackProfile STACKS;
    private static OrderBook ORDERS;
    private static KeyMapping openScreen;
    private static KeyMapping emergencyStop;
    private static KeyMapping listInventoryKey;
    private static KeyMapping resumeKey;
    /** Raw state of each DoughBay key last tick, for edge detection past any open screen. */
    private static boolean emergencyKeyRawDown;
    private static boolean listInventoryKeyRawDown;
    private static boolean resumeKeyRawDown;

    /** Whether the mapping's key is down right now, polled raw so an open screen cannot swallow it. */
    private static boolean rawKeyDown(net.minecraft.client.Minecraft client, KeyMapping mapping) {
        try {
            // The live binding, not getDefaultKey(): a rebound key must poll at
            // its new code, or it never fires.
            com.mojang.blaze3d.platform.InputConstants.Key key =
                    ((dev.doughbay.fabric.mixin.KeyMappingAccessor) (Object) mapping).doughbay$boundKey();
            if (key == null || key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) return false;
            int code = key.getValue();
            return code > 0
                    && com.mojang.blaze3d.platform.InputConstants.isKeyDown(client.getWindow(), code);
        } catch (Throwable ignored) {
            return false;
        }
    }
    /** Held down, everything the quiet settings hide comes back. */
    private static KeyMapping PEEK_KEY;
    private static volatile String RANK = "";
    private static AutomationPersistenceWorker automationPersistence;
    private static volatile ContinuousAutomationPolicy continuousPolicy;
    private static volatile String continuousPolicyBlocker =
            "Continuous automation configuration has not loaded";
    private static boolean exactCoverageForwarded;

    @Override
    public void onInitializeClient() {
        DoughBayConfig config = DoughBayConfig.load();
        activeConfig = config;
        applyPermissions(config);
        AUTOMATION_SESSION.setRestStore(config.directory().resolve("market-rests.txt"));
        AUTOMATION_SESSION.setAuditStore(config.directory().resolve("audit-balance.txt"));
        AUTOMATION_SESSION.setCollectedStore(config.directory().resolve("orders-collected.txt"));
        AUTOMATION_SESSION.setLedgerPath(config.databasePath());
        Tuning.load(config.directory());
        Probe.load(config.directory());
        EVASION = new EvasionGuard(config.directory().resolve("evasion.txt"));
        EvasionGuard.setLedger(config.databasePath());
        LEDGER_STATS = new LedgerStatsProvider(config.databasePath());
        // The audit baseline: the balance at the last slot check, carried
        // forward by receipted sales, purchases and payouts since. Positions
        // lately booked as returned come along so the check can take back any
        // that are still on the page.
        try (dev.doughbay.storage.Database db = new dev.doughbay.storage.Database(config.databasePath())) {
            java.nio.file.Path store = config.directory().resolve("audit-balance.txt");
            if (java.nio.file.Files.exists(store)) {
                String[] parts = java.nio.file.Files.readString(store).strip().split("=");
                long auditAt = Long.parseLong(parts[0].trim());
                long auditBalance = Long.parseLong(parts[1].trim());
                long spent = 0;
                long sales = 0;
                try (var ps = db.connection().prepareStatement(
                        "SELECT COALESCE(SUM(purchase_price), 0) FROM positions WHERE mode = 'REAL' AND purchased_at > ?")) {
                    ps.setLong(1, auditAt);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) spent += rs.getLong(1);
                    }
                }
                try (var ps = db.connection().prepareStatement(
                        "SELECT COALESCE(SUM(amount), 0) FROM payroll_payments WHERE requested_at > ? AND status <> 'FAILED'")) {
                    ps.setLong(1, auditAt);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) spent += rs.getLong(1);
                    }
                }
                try (var ps = db.connection().prepareStatement(
                        "SELECT COALESCE(SUM(sale_price), 0) FROM positions WHERE mode = 'REAL' AND status = 'SOLD' AND closed_at > ? "
                                + "AND position_id IN (SELECT position_id FROM sale_buyers)")) {
                    ps.setLong(1, auditAt);
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) sales += rs.getLong(1);
                    }
                }
                AUTOMATION_SESSION.setAuditBaseline(auditBalance, auditAt, spent, sales);
                LOGGER.info("DoughBay audit baseline: {} at the last slot check {} min ago, {} spent and {} receipted sales since",
                        auditBalance, (System.currentTimeMillis() - auditAt) / 60_000, spent, sales);
            }
            java.util.List<dev.doughbay.core.model.Position> returned = new java.util.ArrayList<>();
            try (var ps = db.connection().prepareStatement(
                    "SELECT position_id, mode, item_key, stack_bucket, quantity, purchase_price, target_price, purchased_at, listed_at, closed_at "
                            + "FROM positions WHERE mode = 'REAL' AND status = 'CANCELLED' AND closed_at > ?")) {
                ps.setLong(1, System.currentTimeMillis() - 48 * 3_600_000L);
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        returned.add(new dev.doughbay.core.model.Position(rs.getLong(1), rs.getString(2), rs.getString(3),
                                dev.doughbay.core.model.StackBucket.of(rs.getInt(5)), rs.getInt(5), rs.getLong(6), rs.getLong(7),
                                rs.getLong(8), rs.getLong(9), rs.getLong(10), 0, Double.NaN, dev.doughbay.core.model.PositionStatus.CANCELLED));
                    }
                }
            }
            AUTOMATION_SESSION.setRecentlyReturned(returned);
            if (!returned.isEmpty()) LOGGER.info("DoughBay: {} position(s) booked as returned in the last two days; the slot check re-adopts any still listed", returned.size());
            var history = new dev.doughbay.storage.PositionRepository(db).balanceHistory("REAL");
            if (!history.isEmpty()) {
                var last = history.get(history.size() - 1);
                // Money that left after that record and before the close is
                // not a missing sale: purchases the bot made and payroll it
                // sent. Take them off the recorded balance so the yardstick
                // is where the bank really stood at shutdown.
                long spent = 0;
                try (var ps = db.connection().prepareStatement(
                        "SELECT COALESCE(SUM(purchase_price), 0) FROM positions WHERE mode = 'REAL' AND purchased_at > ?")) {
                    ps.setLong(1, last.recordedAt());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) spent += rs.getLong(1);
                    }
                }
                try (var ps = db.connection().prepareStatement(
                        "SELECT COALESCE(SUM(amount), 0) FROM payroll_payments WHERE requested_at > ? AND status <> 'FAILED'")) {
                    ps.setLong(1, last.recordedAt());
                    try (var rs = ps.executeQuery()) {
                        if (rs.next()) spent += rs.getLong(1);
                    }
                }
                AUTOMATION_SESSION.setBalanceBeforeStart(last.balance() - spent);
                LOGGER.info("DoughBay balance before this launch: {} recorded {} min ago, less {} spent after it = {}",
                        last.balance(), (System.currentTimeMillis() - last.recordedAt()) / 60_000, spent, last.balance() - spent);
            }
        } catch (Exception e) {
            LOGGER.warn("DoughBay could not read the balance history: {}", e.toString());
        }
        RIVALS = new RivalIntel(config.databasePath());
        DEMAND = new DemandClock(config.databasePath());
        STACKS = new StackProfile(config.databasePath());
        ORDERS = new OrderBook(config.databasePath());
        POPULATION = new PopulationModel(config.databasePath());
        SafeHomes.load(config.directory());
        AUTOMATION_SESSION.noteLedgerHistory(config.databasePath());
        ComponentObserver.init(config.databasePath());
        PAYROLL = new Payroll(config.databasePath(), EXECUTION_DRIVER, AUTOMATION_SESSION);
        AutomationSessionController.setSaleObserver((sold, buyer) -> {
            SALES_COMPLETED.incrementAndGet();
            RIVALS.recordBuyer(sold, buyer);
            Mascot.noteSale(sold);
            HUD_ALERTS.noteSold(sold);
            dev.doughbay.fabric.discord.DiscordBridge bridge = DISCORD;
            if (bridge != null) {
                bridge.noteSale(sold.itemKey(), sold.quantity(), sold.salePrice(), sold.realizedProfit());
            }
        });
        Shelf.bind(config.directory());
        HUD_ALERTS.setTrustedServers(config.notificationServers());
        HUD_ALERTS.setSaleObserver(AUTOMATION_SESSION::observeSale);

        // sqlite-jdbc extracts a native library at first use, by default into
        // the system temp directory — which is mounted noexec on some systems,
        // turning extraction success into an UnsatisfiedLinkError. Keep it
        // inside the game directory, which is writable and executable.
        System.setProperty("org.sqlite.tmpdir", config.directory().toAbsolutePath().toString());

        HIVE_BANK = new HiveBank(config.databasePath());
        HIVE_TIPS = new HiveTips(config.databasePath());
        HIVE_CONTROL = new HiveControl(config.databasePath());
        FEED_UPLOADER = new FeedUploader(config.apiBaseUrl(), config.apiKey());
        // Uses the DoughBay site's search routes only when it is pointed at
        // them; a build talking straight to DonutSMP searches its own local
        // database instead, and never calls the site.
        MARKET_QUERY = new MarketQueryClient(config.apiBaseUrl(), config.usesOwnApi());
        WEBHOOK = new StatusWebhook(config.databasePath());
        // In this client's own game folder, never the shared config: two
        // clients junctioned to one config directory both read the same file,
        // and on 2026-09-04 the second one swallowed a payment meant for the
        // first and tried to pay itself.
        try {
            commandInbox = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .resolve("send-command.txt");
        } catch (Throwable e) {
            commandInbox = config.directory().resolve("send-command.txt");
        }
        // Which markets this account already works, so the underdog only bids
        // into ground we are not on.
        try (dev.doughbay.storage.Database db = new dev.doughbay.storage.Database(config.databasePath());
             java.sql.PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT DISTINCT item_key FROM positions WHERE mode='REAL' AND closed_at > ?")) {
            ps.setLong(1, System.currentTimeMillis() - 24 * 3_600_000L);
            java.util.List<String> keys = new java.util.ArrayList<>();
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) keys.add(rs.getString(1));
            }
            AUTOMATION_SESSION.seedRecentMarkets(keys);
        } catch (Exception e) {
            LOGGER.warn("DoughBay could not read the markets we already trade: {}", e.toString());
        }
        automationPersistence = new AutomationPersistenceWorker(config.databasePath());
        // The owning account has to be known before the ledger is read, not
        // after: recovery runs long before the player exists in a world, and a
        // second client that loaded with no owner would adopt the first's
        // whole book. The signed-in account is known from launch.
        if (multiClient()) {
            try {
                String account = net.minecraft.client.Minecraft.getInstance().getUser().getName();
                if (account != null && !account.isBlank()) {
                    automationPersistence.setClient(account);
                    LOGGER.info("DoughBay: this client trades as {}; it manages only its own positions", account);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("DoughBay could not read the signed-in account: {}", e.toString());
            }
        }
        AUTOMATION_SESSION.setPersistence(automationPersistence);
        automationPersistence.start();

        watcher = new MarketWatcher(config);
        if (config.collectionEnabled()) {
            watcher.start();
        }
        // The Discord command centre: one message in the operator's channel,
        // refreshed with the trader's state and carrying its controls.
        DISCORD = dev.doughbay.fabric.discord.DiscordBridge.start(LOGGER, config.directory(), config.databasePath(), AUTOMATION_SESSION);
        HUD_ALERTS.register();
        QuietScreen.register();
        // Observe action-bar/system messages after Minecraft receives them.
        // This never cancels or modifies chat; it only recognizes the server's
        // "<buyer> bought your <item> for <price>" sale confirmation.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            EXECUTION_DRIVER.observeGameMessage(message.getString());
            if (PAYROLL != null) PAYROLL.observeChat(message.getString());
            AUTOMATION_SESSION.observeOrderChat(message.getString());
            AUTOMATION_SESSION.observeServerRestart(message.getString());
            AUTOMATION_SESSION.observeListingRefused(message.getString());
            // Donut delivers the sale confirmation as a system chat line
            // (observed: ".JAMESTHEB8312 bought your Ender Chest for $12.2K"),
            // not only on the action bar. Player chat carries a rank/name
            // prefix, so the parser's anchored shape still rejects spoofs,
            // and the HUD bridge debounces a duplicate action-bar copy.
            HUD_ALERTS.observeServerMessage(
                    net.minecraft.client.Minecraft.getInstance(), message.getString());
        });
        LOGGER.info("DoughBay initialized ({} execution). Config: {}",
                EXECUTION_DRIVER.modeName(), config.directory());
        // Bundled libraries share a nested-mod id with any other mod shipping
        // them, and only one version wins. Log which one actually loaded so a
        // version clash is diagnosable instead of a mystery NoSuchMethodError.
        try {
            LOGGER.info("DoughBay using Jackson {}",
                    com.fasterxml.jackson.databind.ObjectMapper.class.getPackage()
                            .getImplementationVersion());
        } catch (Throwable t) {
            LOGGER.warn("DoughBay could not determine the loaded Jackson version: {}", t.toString());
        }

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("doughbay", "main"));
        openScreen = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.doughbay.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                category));
        // Its own key. While the stop shared B with "open the screen", every
        // attempt to watch a running session paused it instead.
        emergencyStop = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.doughbay.emergency_stop",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_END,
                category));
        PEEK_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.doughbay.peek",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                category));
        // Home: sweep the pack and list everything sellable, for stock the
        // normal path left behind. Page Up: resume a paused session.
        listInventoryKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.doughbay.list_inventory",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_HOME,
                category));
        resumeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.doughbay.resume",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_PAGE_UP,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Keep Minecraft's input clock fresh so it never AFK-throttles the
            // render thread the whole automation loop runs on. A bot never
            // touches the real keyboard or mouse, so vanilla counts it as idle
            // after a minute and caps the framerate to 30 fps, then 10 fps after
            // ten - which stretches every auction step past its wall-clock
            // timeout and aborts it (mislabelled "render thread throttled").
            // FramerateLimitTracker.getThrottleReason() gates purely on
            // now - latestInputTime, and onInputReceived() stamps that field, so
            // calling it each tick is the same signal a keypress sends, delivered
            // from inside the loop it protects. It does not touch the iconified
            // branch; a minimized window still relies on FramerateLimitTrackerMixin.
            if (Tuning.get("perf.no_afk_throttle") >= 0.5) {
                try {
                    client.getFramerateLimitTracker().onInputReceived();
                } catch (Throwable ignored) {
                    // Never let a keep-awake failure break the tick.
                }
            }
            // Drain the emergency key before advancing the state machine. This
            // ordering guarantees a queued stop cannot be preceded by one last
            // auction or confirmation click in the same client tick.
            // Held, not toggled: a bot you cannot see is a bot you cannot
            // check, so looking at it should be as easy as leaning on a key.
            QuietScreen.setPeeking(PEEK_KEY != null && PEEK_KEY.isDown());
            StashDesk.tick(client);
            OrbitCamera.tick(client);
            boolean stopPressed = false;
            while (emergencyStop.consumeClick()) {
                stopPressed = true;
            }
            // The keybind only fires while no screen is open, but the bot almost
            // always has a (quiet-hidden) auction or order screen up, so a press
            // only landed in the gap between operations. Poll the raw key state
            // too - no screen intercepts that - so the first press lands whatever
            // is on screen. Same reasoning for the list and resume keys.
            boolean endDown = rawKeyDown(client, emergencyStop);
            if (endDown && !emergencyKeyRawDown) stopPressed = true;
            emergencyKeyRawDown = endDown;

            boolean listPressed = false;
            if (listInventoryKey != null) {
                while (listInventoryKey.consumeClick()) listPressed = true;
                boolean listDown = rawKeyDown(client, listInventoryKey);
                if (listDown && !listInventoryKeyRawDown) listPressed = true;
                listInventoryKeyRawDown = listDown;
            }
            if (listPressed) AUTOMATION_SESSION.requestInventoryList();

            boolean resumePressed = false;
            if (resumeKey != null) {
                while (resumeKey.consumeClick()) resumePressed = true;
                boolean resumeDown = rawKeyDown(client, resumeKey);
                if (resumeDown && !resumeKeyRawDown) resumePressed = true;
                resumeKeyRawDown = resumeDown;
            }
            if (resumePressed && AUTOMATION_SESSION.snapshot().state()
                    == AutomationSessionController.State.PAUSED) {
                AUTOMATION_SESSION.resumeFromPlayer();
            }

            boolean openPressed = false;
            while (openScreen.consumeClick()) {
                openPressed = true;
            }
            boolean stoppedThisTick = false;
            if (stopPressed) {
                if (AUTOMATION_SESSION.snapshot().active()) {
                    AUTOMATION_SESSION.emergencyStop();
                    stoppedThisTick = true;
                }
                if (EXECUTION_DRIVER.inspect().armed()) {
                    EXECUTION_DRIVER.emergencyStop();
                    stoppedThisTick = true;
                }
            }
            if (openPressed && !stoppedThisTick && client.gui.screen() == null) {
                client.gui.setScreen(new DoughBayScreen(watcher,
                        LEDGER_STATS != null ? LEDGER_STATS : PerformanceStatsProvider.previewOnly()));
            }

            // The controller only publishes immutable intent here. This call
            // queues/wakes the watcher's daemon; it never performs HTTP or
            // SQLite work on Minecraft's client thread.
            syncExactCoverageRequest();

            // The execution state machine must keep advancing while Minecraft's
            // auction and confirmation screens replace DoughBay's own screen.
            if (!stoppedThisTick) {
                // A bug in the trader must pause the trader, never take the
                // game down with it: the caps crash of 2026-09-02 did exactly that.
                try {
                    EXECUTION_DRIVER.tick(client);
                    AUTOMATION_SESSION.tick(client,
                            watcher == null ? null : watcher.snapshot());
                } catch (RuntimeException e) {
                    LOGGER.error("DoughBay automation tick failed; pausing", e);
                    try {
                        AUTOMATION_SESSION.pause("Internal error: " + e.getMessage() + " (see the log)");
                    } catch (RuntimeException ignored) {
                        // pausing failed too; the next tick logs again rather than crashing
                    }
                }
                // A controller tick can select a candidate or advance to the
                // second proof scan. Forward that new boundary immediately.
                syncExactCoverageRequest();
            }
            if (AUTO_JOIN == null) AUTO_JOIN = new AutoJoin(client);
            AUTO_JOIN.tick();
            autoRespawn(client);
            if (EVASION != null) EVASION.tick(client, AUTOMATION_SESSION);
            pollCommandInbox(client);
            SafeHomes.poll();
            CONSIGNMENT.tick(client);
            if (WEBHOOK != null) WEBHOOK.tick();
            // One purse pays the wages. The rules and the profit window they
            // read are shared, so a second client paying from them races the
            // first and the same profit is paid twice: 31% went out over six
            // hours on 2026-09-04 where 20% was owed.
            if (PAYROLL != null && !collector() && Tuning.get("multi.payroll_here") >= 0.5) {
                PAYROLL.tick(client);
            }
            if (HIVE_BANK != null && !collector()) HIVE_BANK.tick(client, AUTOMATION_SESSION.knownBalance());
            if (HIVE_TIPS != null && !collector()) HIVE_TIPS.sweep();
            if (HIVE_CONTROL != null && !collector()) HIVE_CONTROL.poll(AUTOMATION_SESSION);
            if (client.level != null && client.level.getGameTime() % 40 == 0) Tuning.pollExternalChanges();
            if (RIVALS != null && client.player != null) RIVALS.setOwnName(client.player.getName().getString());
            // Set at startup from the signed-in account; kept in step here in
            // case the account changed under us.
            if (automationPersistence != null && client.player != null && multiClient()) {
                automationPersistence.setClient(client.player.getName().getString());
            }
            // A container on screen is proof the world is serving again: the
            // server announces its restart and never announces the end of one,
            // so the hold would otherwise run its full ten minutes even when
            // the world came back in three.
            if (client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory
                    .AbstractContainerScreen<?>) {
                AUTOMATION_SESSION.observeServerAnswered();
            }
            // The last word in the tick: everything above guards against the
            // wrong thing happening; this notices nothing happening at all.
            try {
                var snap = AUTOMATION_SESSION.snapshot();
                LIVENESS.observe(client, SALES_COMPLETED.get(), snap.openListings(),
                        snap.openListings() > 0 || snap.committedSpend() > 0,
                        snap.active(), snap.state().name());
            } catch (RuntimeException ignored) {
                // a liveness check must never be the thing that breaks the tick
            }
            HUD_ALERTS.tick(client, watcher == null ? null : watcher.snapshot(),
                    EXECUTION_DRIVER.inspect());
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> MarketWatcher.requestRankLookup());

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            AUTOMATION_SESSION.emergencyStop();
            if (watcher != null && exactCoverageForwarded) {
                watcher.clearExactListingCoverage();
                exactCoverageForwarded = false;
            }
            if (watcher != null) {
                watcher.stop();
            }
            if (automationPersistence != null) {
                automationPersistence.close();
            }
            // Its heartbeat would otherwise beat for another ninety seconds
            // after it is gone, holding a share of the API budget it cannot
            // spend and, worse, the Discord panel it cannot publish.
            ApiBudget budget = API_BUDGET;
            if (budget != null) budget.release();
        });
    }

    public static MarketWatcher watcher() {
        return watcher;
    }

    /**
     * Markets the automation session is holding an unlisted position in. The
     * watcher pins these into its scanned set so a bought market can never be
     * rotated out from under an open position, which would deadlock the relist
     * on refused exact-book coverage.
     */
    public static java.util.Set<String> heldExposureBaseIds() {
        return AUTOMATION_SESSION.heldExposureBaseIds();
    }

    /** The configuration in force, including any key entered this session. */
    public static DoughBayConfig activeConfig() {
        return activeConfig;
    }

    /**
     * Persists an API key entered in game and restarts collection with it.
     *
     * <p>Only the key changes: every execution permission, allowlist, fee
     * value, and continuous limit is carried over from startup, so saving a
     * key can never widen what DoughBay is allowed to do. Called from the
     * client thread; the watcher restart joins a daemon that is parked
     * between polls, and the file write is a few hundred bytes.
     *
     * @return an empty string on success, otherwise a message safe to show
     *         on screen — it never contains the key
     */
    public static synchronized String applyApiKey(String key) {
        return applyApiKey(key, null);
    }

    /**
     * Saves a key and, when given, the auction source it should be read
     * through - the Donut API directly, or the DoughBay proxy. A null base URL
     * leaves the source unchanged.
     */
    public static synchronized String applyApiKey(String key, String baseUrl) {
        DoughBayConfig existing = activeConfig;
        if (existing == null) {
            return "DoughBay configuration has not finished loading";
        }
        try {
            if (key != null) DoughBayConfig.saveApiKey(existing.directory(), key);
            if (baseUrl != null) DoughBayConfig.saveApiBaseUrl(existing.directory(), baseUrl);
        } catch (Exception e) {
            LOGGER.warn("DoughBay could not save the API settings: {}", e.getMessage());
            return "Could not save: " + e.getMessage();
        }
        DoughBayConfig updated = existing;
        if (key != null) updated = updated.withApiKey(key);
        if (baseUrl != null) updated = updated.withApiBaseUrl(baseUrl);
        activeConfig = updated;
        if (watcher != null) {
            // applyConfig stops the daemon, which already cancels any queued
            // or in-flight coverage scan; only this mirror flag is left.
            exactCoverageForwarded = false;
            watcher.applyConfig(updated);
        }
        LOGGER.info("DoughBay API settings updated from the in-game card");
        return "";
    }

    /**
     * Hands every execution permission in {@code config} to the driver and the
     * session controller. Startup calls it once; the Autopilot setup card calls
     * it again after saving, so switching live trading on or off takes effect
     * at once instead of on the next launch.
     */
    private static void applyPermissions(DoughBayConfig config) {
        // A collector shares the ledger with the trading client but never acts
        // on it: several clients can watch one market, only one may spend from
        // one purse.
        EXECUTION_DRIVER.setAuthorizedExecutionEnabled(config.authorizedExecutionEnabled() && !collector());
        EXECUTION_DRIVER.setAuthorizedServers(config.authorizedServers());
        EXECUTION_DRIVER.setPreflightEnabled(config.preflightEnabled());
        EXECUTION_DRIVER.setPreflightServers(config.preflightServers());
        configureContinuousAutomation(config);
        AUTOMATION_SESSION.setSaleNotificationTrustedServers(config.notificationServers());
        AUTOMATION_SESSION.setAuctionFeePolicy(
                config.auctionFeesConfirmed(), config.auctionFees());
        AUTOMATION_SESSION.setRiskConfig(config.riskConfig());
    }

    /** Whether live trading on DonutSMP is switched on in the config in force. */
    public static boolean donutAutomationEnabled() {
        DoughBayConfig config = activeConfig;
        return config != null && config.authorizedExecutionEnabled()
                && config.continuousAutomationEnabled();
    }

    /**
     * The Autopilot setup card's one switch: saves live trading on or off for
     * DonutSMP with the given spend limits, reloads the config, and re-applies
     * the permissions now. Turning off also stops a running session, the same
     * way the Stop button does.
     *
     * @param joinedAddress the address the player is connected through, or null
     * @return an empty string on success, otherwise a message safe to show
     */
    public static synchronized String setDonutAutomation(boolean enabled, long maxPerTrade,
                                                         long maxPerSession, String joinedAddress) {
        DoughBayConfig existing = activeConfig;
        if (existing == null) {
            return "GoNuts configuration has not finished loading";
        }
        if (!enabled) {
            AUTOMATION_SESSION.stop();
        }
        try {
            DoughBayConfig.saveDonutAutomation(existing.directory(), enabled,
                    maxPerTrade, maxPerSession, joinedAddress);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            LOGGER.warn("GoNuts could not save the automation setup: {}", e.getMessage());
            return "Could not save: " + e.getMessage();
        }
        DoughBayConfig reloaded = DoughBayConfig.load();
        applyPermissions(reloaded);
        activeConfig = reloaded;
        if (enabled && !(reloaded.authorizedExecutionEnabled() && reloaded.continuousAutomationEnabled())) {
            // The save went through but another field in config.json failed
            // validation, which locks the gate fail-closed. Say so rather than
            // leave the player pressing Start on a grey button.
            return "Saved, but config.json has an invalid field; live trading stays locked (see the log)";
        }
        LOGGER.info("GoNuts live trading {} from the Autopilot card (per trade {}, per session {})",
                enabled ? "switched on" : "switched off", maxPerTrade, maxPerSession);
        return "";
    }

    public static AutomatedExecutionDriver executionDriver() {
        return EXECUTION_DRIVER;
    }

    public static EvasionGuard evasionGuard() {
        return EVASION;
    }

    /** The two keys, rebindable from the Settings tab; Minecraft saves them with its own controls. */
    public static KeyMapping keyOpenScreen() {
        return openScreen;
    }

    public static KeyMapping keyEmergencyStop() {
        return emergencyStop;
    }

    /** {bound key name, what it does} for each DoughBay key, for the HUD legend. */
    public static java.util.List<String[]> keyLegend() {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        legendRow(out, emergencyStop, "Emergency stop");
        legendRow(out, listInventoryKey, "List inventory");
        legendRow(out, resumeKey, "Resume");
        legendRow(out, openScreen, "Open panel");
        legendRow(out, PEEK_KEY, "Peek (hold)");
        return out;
    }

    private static void legendRow(java.util.List<String[]> out, KeyMapping m, String action) {
        if (m == null) return;
        // Skip an unbound key (stash is unbound by default): no point listing it.
        try {
            com.mojang.blaze3d.platform.InputConstants.Key key =
                    ((dev.doughbay.fabric.mixin.KeyMappingAccessor) (Object) m).doughbay$boundKey();
            if (key == null || key.equals(com.mojang.blaze3d.platform.InputConstants.UNKNOWN)) return;
        } catch (Throwable ignored) {
            // fall through and list it
        }
        out.add(new String[]{m.getTranslatedKeyMessage().getString(), action});
    }

    public static Payroll payroll() {
        return PAYROLL;
    }

    /** The player's rank as the API reports it; "" until read or when there is none. */
    public static String rank() {
        return RANK;
    }

    public static void setRank(String rank) {
        RANK = rank == null ? "" : rank;
    }

    private static PopulationModel POPULATION;

    public static PopulationModel population() {
        return POPULATION;
    }

    public static OrderBook orderBook() {
        return ORDERS;
    }

    public static DemandClock demandClock() {
        return DEMAND;
    }

    /** What size each market buys in; read-only for now. */
    public static StackProfile stackProfile() {
        return STACKS;
    }

    private static dev.doughbay.fabric.discord.DiscordBridge DISCORD;

    private static long deathScreenSince;
    private static long respawnedAt;
    private static java.util.List<String> heldAtDeath = java.util.List.of();

    /**
     * A death screen stops everything: the session cannot open a page while it
     * is up, so an unattended bot sits there until somebody clicks. After a
     * short pause, to leave a human time to read it, the client respawns
     * itself and carries on.
     */
    private static void autoRespawn(net.minecraft.client.Minecraft client) {
        if (client == null || client.player == null) return;
        boolean dead = client.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen;
        if (!dead) {
            deathScreenSince = 0;
            // A few seconds after coming back, check what the death cost.
            if (respawnedAt > 0 && System.currentTimeMillis() - respawnedAt > 4000) {
                long at = System.currentTimeMillis();
                respawnedAt = 0;
                java.util.List<String> lost = AUTOMATION_SESSION.auditAfterDeath(client, at);
                String message = lost.isEmpty()
                        ? "Respawned; nothing the session was holding is missing"
                        : "Respawned; lost on death: " + String.join(", ", lost);
                LOGGER.warn("DoughBay: {}", message);
                try {
                    if (WEBHOOK != null && Tuning.get("webhook.alert_death") >= 0.5) {
                        java.util.List<String[]> lines = new java.util.ArrayList<>();
                        lines.add(new String[] {"lost", lost.isEmpty() ? "nothing" : String.join("\n", lost)});
                        if (!heldAtDeath.isEmpty()) {
                            lines.add(new String[] {"was holding", String.join("\n", heldAtDeath)});
                        }
                        WEBHOOK.alert("Died and respawned", 0xE86A6A, lines, "");
                    }
                } catch (Throwable ignored) {
                    // the audit matters more than telling anyone about it
                }
                if (client.player != null) {
                    client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[GoNuts] " + message));
                }
                if (!heldAtDeath.isEmpty()) {
                    LOGGER.warn("DoughBay: the session was holding {} when it died", heldAtDeath);
                }
                heldAtDeath = java.util.List.of();
            }
            return;
        }
        if (Tuning.get("session.auto_respawn_sec") <= 0) return;
        long now = System.currentTimeMillis();
        if (deathScreenSince == 0) {
            deathScreenSince = now;
            java.util.List<String> held = new java.util.ArrayList<>();
            for (dev.doughbay.core.model.Position p : AUTOMATION_SESSION.stockInHand()) {
                held.add(p.itemKey() + " x" + p.quantity());
            }
            heldAtDeath = java.util.List.copyOf(held);
            LOGGER.warn("DoughBay: the player died{}; respawning in {} s",
                    held.isEmpty() ? "" : " holding " + held, (long) Tuning.get("session.auto_respawn_sec"));
            return;
        }
        if (now - deathScreenSince < Tuning.get("session.auto_respawn_sec") * 1000) return;
        deathScreenSince = 0;
        try {
            client.player.respawn();
            client.gui.setScreen(null);
            respawnedAt = System.currentTimeMillis();
            LOGGER.info("DoughBay: respawned; checking what the death cost");
        } catch (RuntimeException e) {
            LOGGER.warn("DoughBay could not respawn: {}", e.toString());
        }
    }

    /** Whether this key is shared with other clients, so the allowance must be divided. */
    public static boolean multiClient() {
        return Tuning.get("multi.enabled") >= 0.5;
    }

    /**
     * Whether this client only watches. A scanner reads the auction, the order
     * house and the market feed into the shared ledger, and is refused every
     * action that spends money or moves an item. It means nothing without the
     * shared key, because a lone client scanning would simply never trade.
     */
    public static boolean collector() {
        return multiClient() && Tuning.get("multi.scan_only") >= 0.5;
    }

    private static volatile ApiBudget API_BUDGET;
    private static volatile HiveBank HIVE_BANK;
    private static volatile HiveTips HIVE_TIPS;
    private static volatile HiveControl HIVE_CONTROL;
    private static volatile FeedUploader FEED_UPLOADER;
    private static volatile MarketQueryClient MARKET_QUERY;
    private static volatile StatusWebhook WEBHOOK;

    /** Read-only lookups against the site's public routes, for the in-game search box; null before config loads. */
    public static MarketQueryClient marketQuery() {
        return MARKET_QUERY;
    }

    /** The hourly status report, or null before the config has loaded. */
    public static StatusWebhook webhook() {
        return WEBHOOK;
    }

    /** Money moving between the hive's own accounts; null until the ledger path is known. */
    public static HiveTips hiveTips() {
        return HIVE_TIPS;
    }

    public static HiveBank hiveBank() {
        return HIVE_BANK;
    }

    /** Start and Stop for the other clients in the hive; null until the ledger path is known. */
    public static HiveControl hiveControl() {
        return HIVE_CONTROL;
    }

    /** Sends order-house sweeps home to the DoughBay API; null before the config has loaded. */
    public static FeedUploader feedUploader() {
        return FEED_UPLOADER;
    }

    static void setApiBudget(ApiBudget budget) {
        API_BUDGET = budget;
    }

    private static java.nio.file.Path commandInbox;
    private static long commandInboxCheckedAt;

    /**
     * A one-off command posted from outside the game.
     *
     * <p>Typing into the chat box does not work while the session is running:
     * it opens the auction every few seconds, so the keystrokes land in a
     * container screen instead. A line dropped into
     * {@code send-command.txt} goes out through the same gate every other
     * command uses, in its turn, with no screen to fight. The file is deleted
     * as it is read, so a command is sent once and never repeats.
     */
    private static void pollCommandInbox(net.minecraft.client.Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - commandInboxCheckedAt < 2000) return;
        commandInboxCheckedAt = now;
        java.nio.file.Path file = commandInbox;
        if (file == null || client.getConnection() == null) return;
        try {
            if (!java.nio.file.Files.exists(file)) return;
            java.util.List<String> lines = java.nio.file.Files.readAllLines(file);
            java.nio.file.Files.delete(file);
            for (String raw : lines) {
                String command = raw.strip();
                if (command.isEmpty() || command.startsWith("#")) continue;
                if (command.startsWith("/")) command = command.substring(1);
                LOGGER.warn("DoughBay: sending a command posted from outside the game: /{}", command);
                EXECUTION_DRIVER.sendWhenClear(client, command);
            }
        } catch (Exception e) {
            LOGGER.warn("DoughBay could not read the command inbox: {}", e.toString());
        }
    }

    /** The session's latest balance, shared with the other clients through the ledger. */
    public static void noteBalanceForHive(long balance) {
        ApiBudget b = API_BUDGET;
        if (b != null) b.setBalance(balance);
    }

    /** What the stall watch currently believes; shown on the Discord Status page. */
    public static String livenessVerdict() {
        return LIVENESS.describe();
    }

    /** This client's own account name, or empty before the user is known. */
    public static String account() {
        try {
            String name = net.minecraft.client.Minecraft.getInstance().getUser().getName();
            return name == null ? "" : name;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Every account in the hive, in a stable order; empty when running alone. */
    public static java.util.List<String> hiveAccounts() {
        ApiBudget b = API_BUDGET;
        return b == null ? java.util.List.of() : b.accounts();
    }

    /**
     * Whether this client is the one that publishes the Discord panel. Only
     * one may: they share a configuration folder, so otherwise every client
     * connects the same bot and edits the same message over the top of the
     * others.
     */
    public static boolean hostsDiscord() {
        ApiBudget b = API_BUDGET;
        if (b == null || !multiClient()) return true;
        return b.hostsDiscord(account());
    }

    /** The accounts of every client running against this ledger right now. */
    public static java.util.Set<String> hiveRoster() {
        ApiBudget b = API_BUDGET;
        return b == null ? java.util.Set.of() : b.roster();
    }

    /** Whether this seller is one of ours: another client of the same hive. */
    public static boolean hiveMember(String sellerName) {
        if (sellerName == null || sellerName.isBlank() || !multiClient()) return false;
        return hiveRoster().contains(sellerName.strip().toLowerCase(java.util.Locale.ROOT));
    }

    /** "Trading" or "Scanning", for the HUD and the status lines. */
    public static String clientMode() {
        if (!multiClient()) return "Trading";
        return collector() ? "Scanning" : "Trading";
    }

    public static dev.doughbay.fabric.discord.DiscordBridge discord() {
        return DISCORD;
    }

    public static RivalIntel rivalIntel() {
        return RIVALS;
    }

    public static AutomationSessionController automationSessionController() {
        return AUTOMATION_SESSION;
    }

    /**
     * The validated, immutable limits for a newly started session. A null
     * value is an intentional fail-closed state and must keep Start disabled.
     */
    public static ContinuousAutomationPolicy continuousPolicy() {
        return continuousPolicy;
    }

    public static String continuousPolicyBlocker() {
        return continuousPolicyBlocker;
    }

    /**
     * Read-only bridge for action-bar title packets. Fabric's game-message
     * event covers overlay system-chat packets, while Minecraft's
     * {@code /title ... actionbar} packet follows a separate listener path.
     * Both paths retain the same exact-server trust check and strict parser.
     */
    public static void observeActionBar(Component message) {
        if (message == null) return;
        HUD_ALERTS.observeServerMessage(
                net.minecraft.client.Minecraft.getInstance(), message.getString());
    }

    private static void configureContinuousAutomation(DoughBayConfig config) {
        continuousPolicy = null;
        continuousPolicyBlocker = "Continuous automation limits are incomplete";
        AUTOMATION_SESSION.setContinuousAuthorization(false, java.util.List.of());
        try {
            ContinuousAutomationPolicy parsed = ContinuousAutomationPolicy.fromConfigValues(
                    config.continuousMaxTradesPerSession(),
                    config.continuousMaxSessionSpend(),
                    config.continuousMaxPurchasePrice(),
                    config.continuousMinimumProfit(),
                    config.continuousMinimumRoiPercent(),
                    config.continuousMinimumConfidencePercent(),
                    config.continuousCooldownSeconds(),
                    config.continuousReservedHotbarSlot(),
                    config.continuousMaximumHoldMinutes(),
                    config.continuousMaxOpenListings());
            continuousPolicy = parsed;
            AUTOMATION_SESSION.setContinuousAuthorization(
                    config.continuousAutomationEnabled(),
                    config.continuousAutomationServers());
            if (!config.continuousAutomationEnabled()) {
                continuousPolicyBlocker = "Continuous automation is disabled in config";
            } else if (!config.auctionFeesConfirmed()) {
                continuousPolicyBlocker =
                        "Auction fees/taxes are not explicitly confirmed in config";
            } else if (config.notificationServers().isEmpty()) {
                continuousPolicyBlocker =
                        "No server is trusted for strict sale notifications";
            } else {
                continuousPolicyBlocker = "";
            }
        } catch (IllegalArgumentException | ArithmeticException e) {
            // Zero caps are the shipped safe default. Do not turn a partial or
            // malformed configuration into permission to send commands.
            continuousPolicyBlocker = "Continuous automation has invalid or zero safety limits";
            LOGGER.warn("DoughBay continuous automation remains locked: invalid safety limits");
        }
    }

    private static void syncExactCoverageRequest() {
        if (watcher == null) return;
        AutomationSessionController.CoverageRequest request =
                AUTOMATION_SESSION.coverageRequest();
        if (request.requested()) {
            if (watcher.requestExactListingCoverage(
                    request.itemId(), request.scanMustStartAfterMillis())) {
                exactCoverageForwarded = true;
            }
        } else if (exactCoverageForwarded) {
            // Clear only on requested -> none. Clearing on every idle tick
            // would generation-cancel a scan that just completed.
            watcher.clearExactListingCoverage();
            exactCoverageForwarded = false;
        }
    }
}
