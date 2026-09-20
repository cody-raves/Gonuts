package dev.doughbay.fabric;

import dev.doughbay.core.execution.ExecutionStatus;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.text.DonutSaleMessageParser;
import dev.doughbay.core.text.DonutSaleMessageParser.SaleNotice;
import dev.doughbay.fabric.automation.AutomationSessionController;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Small, non-interactive notifications drawn over the normal in-game HUD.
 *
 * <p>This class only observes market snapshots, execution status, and server
 * messages. Alerts do not contain buttons and never send chat, packets, or
 * clicks. In particular, a demo opportunity is explicitly labelled as an
 * invented preview and cannot be used to start execution.
 */
final class DoughBayHudAlerts {

    enum Type {
        OPPORTUNITY(0xFFFFD966, 0xFFB88B21),
        SALE(0xFF55E69A, 0xFF168D5A),
        // The other half of the ledger. Sales had an alert from the start and
        // listings had nothing, so the screen showed slots emptying and never
        // showed them filling - which is the same lopsidedness that let the
        // count drift, in a form you could watch.
        LISTING(0xFF6FB8FF, 0xFF2A6FB8),
        ORDER(0xFFC08CFF, 0xFF6D3FB8),
        WARNING(0xFFFFA45B, 0xFFB85F23),
        ERROR(0xFFFF6F70, 0xFFB6343E);

        final int accent;
        final int edge;

        Type(int accent, int edge) {
            this.accent = accent;
            this.edge = edge;
        }
    }

    record Alert(Type type, String title, String lineOne, String lineTwo,
                         String lineThree, long createdAt, long durationMillis, String itemKey) {
        Alert(Type type, String title, String lineOne, String lineTwo, String lineThree, long createdAt, long durationMillis) {
            this(type, title, lineOne, lineTwo, lineThree, createdAt, durationMillis, "");
        }
    }

    private static final Identifier HUD_ID =
            Identifier.fromNamespaceAndPath("doughbay", "market_alerts");
    private static final int MAX_VISIBLE = 3;
    /** An alert box is sized to its own text between these bounds: compact for a
     *  short "sale reported", growing only as far as a long error needs. */
    private static final int MIN_ALERT_WIDTH = 190;
    /** Widest an alert may grow, however long its text is. */
    private static final int MAX_ALERT_WIDTH = 340;
    private static final int MAX_SEEN_LISTINGS = 4_096;
    private static final long ENTER_MILLIS = 220;
    private static final long EXIT_MILLIS = 260;
    private static final Consumer<SaleNotice> NO_SALE_OBSERVER = ignored -> { };

    private final Deque<Alert> alerts = new ArrayDeque<>();
    private final Deque<Alert> history = new ArrayDeque<>();

    List<Alert> history() { return List.copyOf(history); }
    private final List<HudLayout.Rect> occupied = new ArrayList<>();
    private final Set<String> seenListingKeys = new HashSet<>();
    private final Deque<String> seenListingOrder = new ArrayDeque<>();
    private final java.util.Map<String, Long> recentSaleMessages = new java.util.HashMap<>();
    private Set<String> trustedServers = Set.of();
    private volatile Consumer<SaleNotice> saleObserver = NO_SALE_OBSERVER;

    private long previewUntil;

    void preview() {
        previewUntil = System.currentTimeMillis() + 30_000L;
        showDemoSale(System.currentTimeMillis());
    }

    private long lastSnapshotAt = Long.MIN_VALUE;
    private boolean demoPreviewShown;
    private boolean driverStatusInitialized;
    private String lastDriverStatus = "";
    private long hiddenSince = Long.MIN_VALUE;
    private long worldEnteredAt = Long.MIN_VALUE;

    void register() {
        HudElementRegistry.addLast(HUD_ID, this::render);
    }

    void setTrustedServers(List<String> servers) {
        if (servers == null || servers.isEmpty()) {
            trustedServers = Set.of();
            return;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String server : servers) {
            if (server != null && !server.isBlank()) {
                normalized.add(server.strip().toLowerCase(Locale.ROOT));
            }
        }
        trustedServers = Set.copyOf(normalized);
    }

    /**
     * Receives only strict, trusted-server sale notices after packet dedupe.
     * Passing {@code null} removes the observer. Demo/manual HUD previews do
     * not pass through this bridge.
     */
    void setSaleObserver(Consumer<SaleNotice> observer) {
        saleObserver = observer == null ? NO_SALE_OBSERVER : observer;
    }

    /** Called once per client tick from the single client thread. */
    void tick(Minecraft client, MarketWatcher.Snapshot snapshot,
              ExecutionStatus executionStatus) {
        long now = System.currentTimeMillis();

        // Do not spend the one-shot demo preview on the title screen, where
        // there is no in-world HUD for the player to see.
        if (client == null || client.player == null || client.level == null) {
            worldEnteredAt = Long.MIN_VALUE;
            prune(now);
            rememberDriverStatus(executionStatus);
            return;
        }
        if (worldEnteredAt == Long.MIN_VALUE) worldEnteredAt = now;

        // Minecraft draws normal HUD elements behind container/chat screens.
        // Preserve alerts while a screen is open, then give each one its full
        // visible lifetime after the player returns to the world. This avoids
        // silently losing a sale report while the auction GUI is open.
        // The HUD is hidden behind a screen only when it is not drawn under
        // screens; the mod's own screen always hides it. While it is visible
        // nothing is held back: a sale shows the moment it happens.
        boolean hudHidden = client.gui.screen() instanceof DoughBayScreen
                || (client.gui.screen() != null && Tuning.get("hud.over_screens") < 0.5);
        if (hudHidden) {
            if (hiddenSince == Long.MIN_VALUE) hiddenSince = now;
            pruneSaleDedupe(now);
        } else {
            if (hiddenSince != Long.MIN_VALUE) {
                deferHiddenAlerts(hiddenSince, now);
                hiddenSince = Long.MIN_VALUE;
            }
            prune(now);
        }

        // Give Minecraft a moment to finish opening any quick-play/restored
        // screen before spending the one-shot demo previews. Real scanner
        // alerts are still recorded immediately and preserved while hidden.
        boolean holdDemoPreview = snapshot != null && snapshot.demo()
                && (client.gui.screen() != null || now - worldEnteredAt < 750L);
        if (!holdDemoPreview) observeSnapshot(snapshot, now);
        observeDriverStatus(executionStatus, now);
    }

    /**
     * Observes a server/system message without suppressing or modifying it.
     * The same action-bar packet can occasionally be delivered twice; the
     * normalized line receives a short packet-level debounce.
     */
    void observeServerMessage(Minecraft client, String message) {
        String server = currentServerIdentity(client);
        if (server == null || !trustedServers.contains(server)) return;
        SaleNotice sale = DonutSaleMessageParser.parse(message).orElse(null);
        if (sale == null) return;

        long now = System.currentTimeMillis();
        String dedupeKey = sale.buyer().toLowerCase(Locale.ROOT) + "|"
                + sale.itemText().toLowerCase(Locale.ROOT) + "|"
                + (sale.count().isPresent() ? sale.count().getAsInt() : 0) + "|" + sale.price();
        Long previous = recentSaleMessages.get(dedupeKey);
        if (previous != null && now - previous < 750L) return;
        recentSaleMessages.put(dedupeKey, now);

        // Publish only after exact-server validation, strict parsing, and the
        // packet-level debounce have all succeeded. Record the debounce first
        // so a re-entrant or failing observer cannot receive this packet twice.
        try {
            saleObserver.accept(sale);
        } catch (RuntimeException ignored) {
            // Do not include raw message text, parsed fields, or the exception
            // message: observers may attach sensitive context to failures.
            DoughBayClient.LOGGER.warn(
                    "DoughBay sale observer failed after a validated sale event; HUD alert preserved");
        }

        String item = sale.itemText()
                + (sale.count().isPresent() ? " x" + sale.count().getAsInt() : "");
        add(new Alert(Type.SALE,
                "SALE REPORTED",
                compactWhitespace(item) + " sold",
                money(sale.price()) + "  •  buyer " + sale.buyer(),
                "Server sale confirmation received",
                now, 7_000L, ItemIcons.keyForDisplayName(sale.itemText())));
    }

    /**
     * A listing went up, on the server's own word rather than on ours.
     *
     * <p>Sales have been announced since the beginning and listings never
     * were, so with the chat hidden the screen only ever showed stock
     * leaving. Both ends of a trade are now visible, and a book that is
     * filling looks different from one that is only emptying.
     */
    void showListed(String item, int count, long price) {
        long now = System.currentTimeMillis();
        String named = compactWhitespace(item) + (count > 0 ? " x" + count : "");
        add(new Alert(Type.LISTING, "LISTED",
                named + " listed",
                money(price) + (count > 1 ? "  •  " + money(price / count) + " each" : ""),
                "Server listing confirmation received",
                now, 7_000L, ItemIcons.keyForDisplayName(item)));
    }

    /** A bid went on the order book. */
    void showOrderPlaced(String item, int count, long unitPrice) {
        long now = System.currentTimeMillis();
        add(new Alert(Type.ORDER, "ORDER PLACED",
                compactWhitespace(item) + " x" + count + " wanted",
                money(unitPrice) + " each  •  " + money(unitPrice * (long) count) + " committed",
                "Bid accepted by the order house",
                now, 7_000L, ItemIcons.keyForDisplayName(item)));
    }

    /** Somebody filled one of our bids and the stock has arrived. */
    void showOrderFilled(String item, int count, long unitPrice) {
        long now = System.currentTimeMillis();
        add(new Alert(Type.ORDER, "ORDER FILLED",
                compactWhitespace(item) + " x" + count + " delivered",
                money(unitPrice) + " each  •  " + money(unitPrice * (long) count) + " paid",
                "Collected from your order",
                now, 7_000L, ItemIcons.keyForDisplayName(item)));
    }

    /** Hook for future real-trade tracking; it only displays a notification. */
    void showSale(String item, String amount, String buyer) {
        long now = System.currentTimeMillis();
        add(new Alert(Type.SALE, "SALE REPORTED",
                compactWhitespace(item) + " sold",
                compactWhitespace(amount) + (buyer == null || buyer.isBlank()
                        ? "" : "  •  buyer " + compactWhitespace(buyer)),
                "Server sale confirmation received", now, 7_000L));
    }

    void showWarning(String title, String detail) {
        add(new Alert(Type.WARNING, compactWhitespace(title),
                compactWhitespace(detail), "", "", System.currentTimeMillis(), 7_000L));
    }

    void showError(String title, String detail) {
        add(new Alert(Type.ERROR, compactWhitespace(title),
                compactWhitespace(detail), "", "", System.currentTimeMillis(), 8_000L));
    }

    private void observeSnapshot(MarketWatcher.Snapshot snapshot, long now) {
        if (snapshot == null || snapshot.updatedAt() <= 0
                || snapshot.updatedAt() == lastSnapshotAt) {
            return;
        }
        lastSnapshotAt = snapshot.updatedAt();

        List<Opportunity> opportunities = snapshot.opportunities();
        if (opportunities == null || opportunities.isEmpty()) return;

        if (snapshot.demo()) {
            // One sample makes the HUD reviewable without flooding the player
            // with five invented "deals" every time a demo world is opened.
            if (!demoPreviewShown) {
                demoPreviewShown = true;
                rememberAll(opportunities);
                showDemoSale(now);
                showOpportunity(opportunities.getFirst(), true, now);
            }
            return;
        }

        // Real opportunity alerts are valid only while the scanner is healthy
        // and the exact listing snapshot is fresh. Stale historical statistics
        // remain visible in Diagnostics, but they do not create buy signals.
        if ((!"OK".equals(snapshot.status())
                && !"No opportunities passed the filters".equals(snapshot.status()))
                || now - snapshot.updatedAt() > 120_000L) {
            return;
        }

        List<Opportunity> newlyObserved = new ArrayList<>(MAX_VISIBLE);
        for (Opportunity opportunity : opportunities) {
            String key = listingKey(opportunity);
            if (key == null || !seenListingKeys.add(key)) continue;
            seenListingOrder.addLast(key);
            trimSeenListings();
            if (newlyObserved.size() < MAX_VISIBLE) newlyObserved.add(opportunity);
        }

        // Off by default. These were written when a person was going to read
        // them and decide; the bot reads the same snapshot and acts on it
        // within the second, so all they do now is push the things that
        // actually happened off the screen.
        if (dev.doughbay.fabric.Tuning.get("alerts.opportunities") < 0.5) return;
        // Add high-ranked opportunities last so the bounded queue retains them.
        for (int i = newlyObserved.size() - 1; i >= 0; i--) {
            showOpportunity(newlyObserved.get(i), false, now);
        }
    }

    private void showOpportunity(Opportunity opportunity, boolean demo, long now) {
        if (opportunity == null || opportunity.listing() == null) return;
        String item = displayItem(opportunity.listing().itemId())
                + " x" + opportunity.listing().itemCount();
        String priceLine = "Buy " + money(opportunity.buyPrice())
                + "  •  Target " + money(opportunity.recommendedSellPrice())
                + "  •  ROI " + percent(opportunity.expectedRoiPercent());
        String resultLine = demo
                ? "Invented preview  •  no live action"
                : "+" + money(Math.max(0, Math.round(opportunity.expectedNetProfit())))
                + " est  •  " + (opportunity.stats() == null ? 0 : opportunity.stats().sampleCount()) + " completed sales  •  "
                + percent(opportunity.confidence() * 100.0) + " conf";
        add(new Alert(Type.OPPORTUNITY,
                demo ? "DEMO OPPORTUNITY" : "OPPORTUNITY FOUND",
                item, priceLine, resultLine, now, demo ? 8_000L : 6_500L,
                opportunity.listing() == null ? "" : opportunity.listing().itemKey()));
    }

    private void showDemoSale(long now) {
        add(new Alert(Type.SALE,
                "DEMO SALE",
                "Ender Chest x1 sold",
                "$15.0k  •  buyer DemoBuyer",
                "Invented preview  •  no trade recorded",
                now, 8_000L));
    }

    private void observeDriverStatus(ExecutionStatus status, long now) {
        if (status == null) return;
        String signature = status.state() + "|" + status.description();
        if (!driverStatusInitialized) {
            driverStatusInitialized = true;
            lastDriverStatus = signature;
            return;
        }
        if (signature.equals(lastDriverStatus)) return;
        lastDriverStatus = signature;

        if (status.state() == ExecutionStatus.State.ABORTED) {
            add(new Alert(Type.ERROR, "EXECUTION ABORTED",
                    status.description(), "No further action was sent", "",
                    now, 8_000L));
        } else if (status.description() != null
                && status.description().toLowerCase(Locale.ROOT).startsWith("emergency stop")) {
            add(new Alert(Type.WARNING, "EXECUTION STOPPED",
                    "Emergency stop activated", "Verify inventory and balance", "",
                    now, 8_000L));
        }
    }

    private void rememberDriverStatus(ExecutionStatus status) {
        if (status == null || driverStatusInitialized) return;
        driverStatusInitialized = true;
        lastDriverStatus = status.state() + "|" + status.description();
    }

    /** Whether there is a working API feed and a real market snapshot behind the panel. */
    private static boolean feedLive() {
        if (Tuning.get("hud.only_when_live") < 0.5) return true;
        DoughBayConfig config = DoughBayClient.activeConfig();
        if (config == null || !config.hasApiKey()) return false;
        MarketWatcher watcher = DoughBayClient.watcher();
        if (watcher == null) return false;
        MarketWatcher.Snapshot snapshot = watcher.snapshot();
        return snapshot != null && !snapshot.demo() && !snapshot.markets().isEmpty()
                && System.currentTimeMillis() - snapshot.updatedAt() < 10 * 60_000L;
    }

    /** A small "key does what" legend, placed by hud.keys_x / hud.keys_y, using the current bindings. */
    private void drawKeyLegend(GuiGraphicsExtractor graphics, Font font, int margin, int screenWidth, int screenHeight) {
        java.util.List<String[]> rows = DoughBayClient.keyLegend();
        if (rows.isEmpty()) return;
        int line = font.lineHeight + 5;
        int keyW = 0;
        int textW = 0;
        for (String[] r : rows) keyW = Math.max(keyW, font.width(r[0]));
        for (String[] r : rows) textW = Math.max(textW, font.width(r[1]));
        int pad = 8;
        int boxW = Math.min(screenWidth - margin * 2, keyW + 12 + textW + pad * 2);
        int boxH = rows.size() * line + pad * 2 - 1;
        // Placed as a fraction of the screen: 0 is left/top, 100 is right/bottom.
        // Clamped so the box always stays fully on screen whatever the numbers.
        double fx = Tuning.get("hud.keys_x") / 100.0;
        double fy = Tuning.get("hud.keys_y") / 100.0;
        int x = (int) Math.round(fx * (screenWidth - boxW));
        int y = (int) Math.round(fy * (screenHeight - boxH));
        x = Math.max(margin, Math.min(x, screenWidth - boxW - margin));
        y = Math.max(margin, Math.min(y, screenHeight - boxH - margin));
        HudLayout.Rect box = HudLayout.place(x, y, boxW, boxH, screenHeight, margin, occupied);
        if (box == null) return;
        y = box.y();
        occupied.add(box);
        UiTheme.panel(graphics, x, y, boxW, boxH, UiTheme.BACKGROUND);
        int ty = y + pad;
        for (String[] r : rows) {
            graphics.fill(x + pad - 2, ty - 2, x + pad + keyW + 3, ty + font.lineHeight + 1, UiTheme.SURFACE);
            graphics.text(font, r[0], x + pad, ty, UiTheme.GOLD, false);
            graphics.text(font, fit(font, r[1], boxW - keyW - pad * 2 - 10),
                    x + pad + keyW + 10, ty, UiTheme.SECONDARY, false);
            ty += line;
        }
    }

    private void render(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker ignored) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) return;
        // Over the game and, when asked, under other screens too: the bot
        // spends most of its time inside auction pages, and the panel is
        // most useful exactly then. The mod's own screen shows it all already.
        if (client.gui.screen() instanceof DoughBayScreen) return;
        if (client.gui.screen() != null && Tuning.get("hud.over_screens") < 0.5) return;
        // Nothing to show until the market feed is live: a fresh install with
        // no key, or a key that has not answered yet, would otherwise draw an
        // empty panel over the game and look broken.
        if (!feedLive() && System.currentTimeMillis() >= previewUntil) return;

        long now = System.currentTimeMillis();
        Font font = client.font;
        int screenWidth = graphics.guiWidth();
        int screenHeight = graphics.guiHeight();
        int margin = screenWidth < 360 ? 5 : 8;
        occupied.clear();
        if (Tuning.get("hud.panel") >= 0.5 || now < previewUntil) drawSlotPanel(graphics, font, margin);
        prune(now);
        drawAlerts(graphics, font, now, screenWidth, screenHeight, margin);
        if (Tuning.get("intel.enabled") >= 0.5) drawWhoIsNearby(graphics, font, margin);
        if (Tuning.get("hud.keys") >= 0.5) drawKeyLegend(graphics, font, margin, screenWidth, screenHeight);
    }

    private void drawAlerts(GuiGraphicsExtractor graphics, Font font, long now,
                            int screenWidth, int screenHeight, int margin) {
        if (alerts.isEmpty() || Tuning.get("hud.alerts") < 0.5) return;
        boolean alertsLeft = Tuning.get("hud.alerts_left") >= 0.5;
        int top = margin;
        int index = 0;
        boolean reduced = UiTheme.reducedMotion();
        for (Alert alert : alerts) {
            if (index >= MAX_VISIBLE) break;
            long age = now - alert.createdAt();
            long duration = alert.durationMillis() + EXTRA_DISPLAY_MILLIS;
            if (age < 0 || age >= duration) continue;
            double enter = reduced ? 1 : UiMotion.easeOut(age / (double) ENTER_MILLIS);
            int alpha = reduced ? 255 : (int) Math.round(255 * UiMotion.visibility(age, duration, ENTER_MILLIS, EXIT_MILLIS));
            int width = alertWidth(font, alert, screenWidth, margin);
            int height = 22 + alertLines(font, alert, width).size() * 12;
            int targetX = alertsLeft ? margin : screenWidth - margin - width;
            HudLayout.Rect box = HudLayout.place(targetX, top, width, height, screenHeight, margin, occupied);
            if (box == null) break;
            occupied.add(box);
            // A short entrance avoids sweeping a full card across the game.
            int slide = (int) Math.round(12 * (1 - enter));
            int x = alertsLeft ? targetX - slide : targetX + slide;
            drawAlert(graphics, font, alert, x, box.y(), width, height, alpha,
                    1 - UiMotion.clamp01(age / (double) duration));
            top = box.y() + height + 6;
            index++;
        }
    }

    /**
     * A small always-on panel in the top-left: state, slots and money out,
     * the 45-slot grid, and one short line saying what is happening now.
     * Hidden while nothing is running and nothing is parked.
     */
    /** An item icon on its way into its slot (listed) or out to the right (sold). */
    private record Flight(net.minecraft.world.item.ItemStack stack, float fromX, float fromY, float toX, float toY,
                          float fromScale, float toScale, long startedAt, long durationMillis, int landCell) {
    }

    private final java.util.ArrayList<Flight> flights = new java.util.ArrayList<>();
    private final java.util.Map<Integer, Long> litCells = new java.util.HashMap<>();
    private final java.util.ArrayDeque<dev.doughbay.core.model.Position> soldQueue = new java.util.ArrayDeque<>();
    private long lastNewestListingId;
    private int lastOpenCount = -1;

    /** One of the mod's listings sold: its icon leaves the grid to the right on the next frame. */
    public void noteSold(dev.doughbay.core.model.Position sold) {
        if (sold == null) return;
        synchronized (soldQueue) {
            soldQueue.addLast(sold);
            while (soldQueue.size() > 6) soldQueue.pollFirst();
        }
    }

    private static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
    }

    private void drawFlights(GuiGraphicsExtractor graphics, long now) {
        java.util.Iterator<Flight> it = flights.iterator();
        while (it.hasNext()) {
            Flight f = it.next();
            float t = (now - f.startedAt()) / (float) f.durationMillis();
            if (t >= 1) {
                if (f.landCell() >= 0) litCells.put(f.landCell(), now + 500);
                it.remove();
                continue;
            }
            float e = ease(t);
            float px = f.fromX() + (f.toX() - f.fromX()) * e;
            float py = f.fromY() + (f.toY() - f.fromY()) * e;
            float scale = f.fromScale() + (f.toScale() - f.fromScale()) * e;
            org.joml.Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(px, py);
            pose.scale(scale);
            graphics.item(f.stack(), 0, 0);
            pose.popMatrix();
        }
    }

    /**
     * Who is standing near us, drawn down the right-hand side.
     *
     * <p>Their face, their gear as icons with the wear on the worst piece,
     * and the profile line: kills against deaths. The client is already sent
     * everything but the profile, so the panel costs nothing to draw and the
     * profile is fetched once every half hour per player.
     */
    private void drawWhoIsNearby(GuiGraphicsExtractor graphics, Font font, int margin) {
        Minecraft client = Minecraft.getInstance();
        // At least as far as the guard watches. The card looked a hundred and
        // twenty blocks out and the guard pauses the session at a hundred and
        // twenty-eight, so somebody standing in those last eight blocks
        // stopped the bot and never appeared on the panel - which is the one
        // arrival you would actually want a face for.
        double reach = Math.max(Tuning.get("intel.range_blocks"), Tuning.get("evasion.radius_blocks"));
        java.util.List<PlayerIntel.Nearby> people = PlayerIntel.nearby(client,
                reach, (int) Tuning.get("intel.max_players"));
        if (people.isEmpty()) return;

        int width = Math.min(180, graphics.guiWidth() - margin * 2);
        int rowHeight = 38;
        int height = 20 + people.size() * rowHeight;
        int x = graphics.guiWidth() - margin - width;
        HudLayout.Rect box = HudLayout.place(x, margin, width, height, graphics.guiHeight(), margin, occupied);
        if (box == null) return;
        int y = box.y();
        occupied.add(box);
        UiTheme.panel(graphics, x, y, width, height, UiTheme.BACKGROUND);
        graphics.fill(x + 7, y + 14, x + width - 7, y + 15, UiTheme.EDGE);
        PlayerIntel.Nearby first = people.get(0);
        String header = first.name().toUpperCase(java.util.Locale.ROOT) + "  " + first.distance() + "M";
        if (people.size() > 1) header = header + "  +" + (people.size() - 1);
        graphics.text(font, fit(font, header, width - 14), x + 7, y + 4, 0xFFB9A7FF, true);

        int row = y + 16;
        for (PlayerIntel.Nearby p : people) {
            // The face, when their skin has loaded.
            try {
                // getSkin() lives on the client-side player, not on Player.
                for (net.minecraft.client.player.AbstractClientPlayer entity
                        : client.level.players().stream()
                        .filter(net.minecraft.client.player.AbstractClientPlayer.class::isInstance)
                        .map(net.minecraft.client.player.AbstractClientPlayer.class::cast).toList()) {
                    if (!entity.getName().getString().equals(p.name())) continue;
                    net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(
                            graphics, entity.getSkin(), x + 7, row, 16);
                    break;
                }
            } catch (Throwable ignored) {
                // a missing skin is not worth a gap in the panel
            }
            if (p != first) {
                graphics.text(font, fit(font, p.name(), 84), x + 27, row, 0xFFE8E8EC, true);
                graphics.text(font, p.distance() + "m", x + width - 7 - font.width(p.distance() + "m"),
                        row, 0xFF898A92, true);
            } else {
                // The header already named them; this line says how they look.
                String health = p.health() >= 0
                        ? String.format(java.util.Locale.ROOT, "%.0f hearts", p.health() / 2f) : "";
                graphics.text(font, health, x + 27, row, 0xFF898A92, true);
            }

            // The profile line, or a note that it has not come back yet.
            PlayerIntel.Profile profile = p.profile();
            String line = profile == null ? "reading profile" : profile.killLine();
            if (profile != null && line.isEmpty()) line = "no kills recorded";
            int colour = 0xFF898A92;
            if (profile != null && !line.isEmpty()) {
                // Somebody who wins far more than they lose is the one to mind.
                colour = profile.ratio() >= 3 ? 0xFFE86A6A : profile.ratio() >= 1 ? 0xFFFFD966 : 0xFF74E48B;
                line = "K/D " + line;
            }
            graphics.text(font, fit(font, line, width - 34), x + 27, row + 10, colour, true);

            // Their gear as icons, worst wear written beside it. Nothing at
            // all is worth saying out loud: an unarmoured player reads as a
            // broken panel otherwise, and unarmoured is the useful fact.
            if (p.gear().isEmpty()) {
                graphics.text(font, "unarmoured, empty handed", x + 7, row + 21, 0xFF74E48B, true);
                row += rowHeight;
                continue;
            }
            int icon = x + 7;
            int worst = 101;
            for (PlayerIntel.Gear g : p.gear()) {
                if (icon > x + width - 20) break;
                try {
                    graphics.item(g.stack(), icon, row + 19);
                } catch (Throwable ignored) {
                    // an item that will not draw still counts for the wear
                }
                if (g.durabilityMax() > 0) {
                    worst = Math.min(worst, g.durabilityLeft() * 100 / g.durabilityMax());
                }
                icon += 17;
            }
            if (worst <= 100) {
                String wear = worst + "%";
                graphics.text(font, wear, x + width - 7 - font.width(wear), row + 23,
                        worst < 25 ? 0xFFE86A6A : 0xFF898A92, true);
            }
            row += rowHeight;
        }
    }

    private void drawSlotPanel(GuiGraphicsExtractor graphics, Font font, int margin) {
        AutomationSessionController controller = DoughBayClient.automationSessionController();
        if (controller == null) return;
        AutomationSessionController.SessionSnapshot session = controller.snapshot();
        List<AutomationSessionController.OpenListingView> open = controller.openListingViews();
        boolean running = session.state() != AutomationSessionController.State.STOPPED;
        boolean preview = System.currentTimeMillis() < previewUntil;
        long previewAge = 30_000L - (previewUntil - System.currentTimeMillis());
        Mascot.Face previewFace = previewAge < 6_000 ? Mascot.Face.WATCH
                : previewAge < 11_000 ? Mascot.Face.SALE : previewAge < 22_000 ? Mascot.Face.IDLE : Mascot.Face.WORK;
        if (!running && open.isEmpty() && !preview) return;

        int cap = controller.maxOpenListings();
        long out = 0;
        for (var listing : open) out += listing.purchasePrice();

        // Auction or order house: the panel follows what the bot is doing.
        boolean orderMode = preview || session.state() == AutomationSessionController.State.READING_ORDERS
                || session.state() == AutomationSessionController.State.BID_DESK
                || DoughBayClient.executionDriver().operationIntent() == AutomatedExecutionDriver.OperationIntent.DELIVER;
        List<AutomatedExecutionDriver.OwnOrderRow> bids = controller.ownOrderViews();
        long held = 0;
        for (var b : bids) held += b.unitPrice() * b.remaining();
        int other = controller.otherListedSlots();
        int fromServer = controller.serverListedSlots();
        int used = Math.min(SlotTracker.slots(), fromServer >= 0 ? fromServer : open.size() + other);
        EvasionGuard evasion = DoughBayClient.evasionGuard();
        RivalIntel rivals = DoughBayClient.rivalIntel();
        List<String> details = new ArrayList<>();
        if (rivals != null && Tuning.get("hud.rivals") >= 0.5 && !rivals.shortStatus().isEmpty())
            details.add(rivals.shortStatus());
        if (Tuning.get("underdog.enabled") >= 0.5 && rivals != null) {
            var snap = rivals.snapshot();
            int shadowed = snap == null ? 0 : snap.shadows().size();
            if (shadowed > 0) details.add("Shadowing " + shadowed + " markets");
        }
        String action = shortStatus(session, running);
        if (evasion != null && evasion.triggered()) action = "Paused: " + evasion.lastIntruder() + " nearby";
        else if (evasion != null && evasion.enabled()) details.add("Proximity guard on");
        String sale = Tuning.get("hud.sale_line") >= 0.5 ? Mascot.recentSaleLine() : "";
        if (!sale.isEmpty()) action = sale;
        if (Tuning.get("hud.status_line") < 0.5 && sale.isEmpty()) action = "";
        if (preview) {
            action = switch (previewFace) {
                case SALE -> "Sample sale: the dollar reels spin and settle independently.";
                case IDLE -> "Waiting for the next opportunity. Your copilot is getting a little bored.";
                case WORK -> "Preparing the next listing. Keeping an eye on the market.";
                default -> "Reading the order house. Waiting for the next complete scan before placing another bid.";
            };
            details = new ArrayList<>(List.of("Rivals: watching 12 traders", "Shadowing 5 markets", "Proximity guard on"));
        }
        boolean showMascot = Tuning.get("hud.mascot") >= 0.5;
        boolean showGrid = Tuning.get("hud.grid") >= 0.5;
        int width = UiMotion.boundedWidth(288, 248, 360, graphics.guiWidth(), margin);
        int textWidth = width - 24;
        var actionLines = font.split(net.minecraft.network.chat.Component.literal(action), textWidth);
        var detailLines = font.split(net.minecraft.network.chat.Component.literal(String.join("  /  ", details)), textWidth);
        int lineHeight = font.lineHeight + 3;
        int actionHeight = action.isBlank() ? 0 : 18 + actionLines.size() * lineHeight;
        int detailHeight = details.isEmpty() ? 0 : 7 + detailLines.size() * lineHeight;
        var grid = SlotGridLayout.fit(textWidth, SlotTracker.slots());
        int cell = grid.cellHeight();
        int gridWidth = showGrid ? grid.width() : 0;
        int gridHeight = showGrid ? grid.height() + 22 : 0;
        int height = 88 + actionHeight + detailHeight + gridHeight;
        int corner = (int) Math.round(Tuning.get("hud.corner"));
        int x = (corner == 1 || corner == 3) ? graphics.guiWidth() - margin - width : margin;
        int y = (corner == 2 || corner == 3) ? graphics.guiHeight() - margin - height : margin;
        y = Math.max(margin, y);
        int accent = session.state() == AutomationSessionController.State.PAUSED ? UiTheme.WARN
                : orderMode ? 0xFF80BEFF : UiTheme.TEAL;
        occupied.add(new HudLayout.Rect(x, y, width, height));
        UiTheme.panel(graphics, x, y, width, height, UiTheme.BACKGROUND);
        int tx = x + (showMascot ? 76 : 12);
        if (showMascot) Mascot.draw(graphics, preview ? previewFace : Mascot.faceFor(session, evasion), x + 10, y + 2, 52);
        graphics.text(font, preview ? "GONUTS / DEMO" : "GONUTS", tx, y + 12, UiTheme.GOLD, false);
        String state = preview ? "PREVIEW / SAMPLE DATA" : shortState(session);
        UiTheme.rounded(graphics, tx, y + 27, font.width(state) + 14, 17, 4, UiTheme.SURFACE);
        graphics.text(font, state, tx + 7, y + 31, accent, false);
        if (Tuning.get("hud.clock") >= 0.5) {
            String clock = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            graphics.text(font, clock, x + width - 12 - font.width(clock), y + 12, UiTheme.MUTED, false);
        }
        int half = (width - 32) / 2;
        UiTheme.rounded(graphics, x + 10, y + 54, half, 28, 4, UiTheme.SURFACE);
        UiTheme.rounded(graphics, x + 18 + half, y + 54, half, 28, 4, UiTheme.SURFACE);
        graphics.text(font, orderMode ? "ORDER SLOTS" : "AUCTION SLOTS", x + 17, y + 59, UiTheme.MUTED, false);
        graphics.text(font, (preview ? 35 : orderMode ? bids.size() : used) + " / " + SlotTracker.slots(), x + 17, y + 71, UiTheme.TEXT, false);
        graphics.text(font, orderMode ? "COMMITTED" : "LISTED AT COST", x + half + 25, y + 59, UiTheme.MUTED, false);
        graphics.text(font, money(preview ? 540_000 : orderMode ? held : out), x + half + 25, y + 71, UiTheme.TEXT, false);
        int cursor = y + 91;
        if (!action.isBlank()) {
            graphics.text(font, "NOW", x + 12, cursor, accent, false);
            cursor += 14;
            for (var line : actionLines) {
                graphics.text(font, line, x + 12, cursor, UiTheme.TEXT, false);
                cursor += lineHeight;
            }
            cursor += 4;
        }
        if (!details.isEmpty()) {
            for (var line : detailLines) {
                graphics.text(font, line, x + 12, cursor, UiTheme.SECONDARY, false);
                cursor += lineHeight;
            }
            cursor += 7;
        }
        int gridTop = cursor + 13;
        if (showGrid) {
            graphics.fill(x + 12, cursor - 2, x + width - 12, cursor - 1, UiTheme.EDGE);
            graphics.text(font, "CAPACITY", x + 12, cursor + 2, UiTheme.MUTED, false);
            SlotTracker.drawGrid(graphics, x + 12, gridTop, grid,
                    preview ? 35 : orderMode ? bids.size() : used, orderMode ? (int) Tuning.get("orders.bid_slots") : cap,
                    255, accent, UiTheme.EDGE);
        }
        long nowMillis = System.currentTimeMillis();
        if (!preview && showGrid && Tuning.get("hud.flights") >= 0.5 && !UiTheme.reducedMotion()) {
            int gx = x + 12;
            int gy = gridTop;
            // A new listing: its icon flies from the status line into the newest cell.
            long newest = open.isEmpty() ? 0 : open.get(open.size() - 1).positionId();
            if (lastOpenCount >= 0 && open.size() > lastOpenCount && newest != lastNewestListingId) {
                var newestView = open.get(open.size() - 1);
                net.minecraft.world.item.ItemStack stack = ItemIcons.stackFor(newestView.itemKey(), newestView.quantity());
                // The cell that just turned green, which is the last filled one
                // - not a position in the ledger's own list. The grid fills
                // from the server's count, so aiming at the ledger's index sent
                // the icon to a cell somewhere in the middle of the block while
                // the one that actually lit up was further along.
                int index = Math.max(0, Math.min(SlotTracker.slots() - 1, used - 1));
                if (stack != null) {
                    float toX = gx + grid.left(index);
                    float toY = gy + grid.top(index);
                    flights.add(new Flight(stack, x + width - 40, y + 62,
                            toX, toY, 1.75f, cell / 16f, nowMillis, 650, index));
                }
            }
            lastOpenCount = open.size();
            lastNewestListingId = newest;
            // A sale: the icon lifts out of the last cell and leaves to the right, growing as it goes.
            synchronized (soldQueue) {
                dev.doughbay.core.model.Position sold;
                while ((sold = soldQueue.pollFirst()) != null) {
                    net.minecraft.world.item.ItemStack stack = ItemIcons.stackFor(sold.itemKey(), sold.quantity());
                    if (stack == null) continue;
                    // Leaves from the cell that just emptied: the first free
                    // one after the sale, at the end of the filled block.
                    int index = Math.max(0, Math.min(SlotTracker.slots() - 1, used));
                    float fromX = gx + grid.left(index);
                    float fromY = gy + grid.top(index);
                    flights.add(new Flight(stack, fromX, fromY, x + width - 28, fromY - 8, cell / 16f, 1.2f, nowMillis, 800, -1));
                }
            }
            // Cells that just received a listing glow for a moment.
            litCells.entrySet().removeIf(e -> e.getValue() < nowMillis);
            for (int index : litCells.keySet()) {
                int cx = gx + grid.left(index);
                int cy = gy + grid.top(index);
                graphics.fill(cx - 1, cy - 1, gx + grid.right(index) + 1, cy + cell + 1, 0xFFB8FFC8);
            }
        }
        if (preview || UiTheme.reducedMotion() || !showGrid || Tuning.get("hud.flights") < 0.5) {
            flights.clear();
            litCells.clear();
            synchronized (soldQueue) { soldQueue.clear(); }
            lastOpenCount = open.size();
            lastNewestListingId = open.isEmpty() ? 0 : open.get(open.size() - 1).positionId();
        }
        if (!flights.isEmpty()) drawFlights(graphics, nowMillis);
    }

    private static String shortState(AutomationSessionController.SessionSnapshot session) {
        return switch (session.state()) {
            case SCANNING -> "SCANNING";
            case BUYING -> "BUYING";
            case PREPARING_LIST, LISTING -> "LISTING";
            case MONITORING -> "LISTED";
            case COOLDOWN -> "COOLDOWN";
            case REPRICING -> "REPRICING";
            case AUDITING_SLOTS -> "CHECKING";
            case READING_ORDERS -> "ORDERS";
            case BID_DESK -> "BIDS";
            case PAUSED -> "PAUSED";
            case STOPPED -> "IDLE";
        };
    }

    /** A few words on what is happening, never a sentence. */
    private static String shortStatus(AutomationSessionController.SessionSnapshot session,
                                      boolean running) {
        var position = session.position();
        String item = position == null ? "" : titleCase(position.itemKey());
        return switch (session.state()) {
            case SCANNING -> {
                String d = session.detail() == null ? "" : session.detail();
                if (d.startsWith("Open listings at the cap")) yield "Slots full, waiting for sales";
                if (d.startsWith("Waiting before")) yield "Waiting: close the open screen";
                yield "Scanning markets";
            }
            case BUYING -> {
                String d = DoughBayClient.executionDriver().inspect().description();
                if (d == null) yield "Buying";
                if (d.startsWith("Watching recent listings")) yield "Watching recent listings";
                if (d.startsWith("Switching")) yield "Setting filter: Recently Listed";
                if (d.startsWith("Recent listing")) yield "Found: " + firstClause(d.substring(15));
                yield firstClause(d);
            }
            case PREPARING_LIST, LISTING -> item.isBlank() ? "Listing"
                    : "Listing " + item + " at " + money(position.targetPrice());
            case MONITORING -> item.isBlank() ? "Listed" : "Listed " + item;
            case COOLDOWN -> "Next buy in " + remaining(session.cooldownUntilMillis());
            case REPRICING -> "Pulling back a stale listing";
            case AUDITING_SLOTS -> "Checking your auction slots";
            case READING_ORDERS -> "Reading the order house";
            case BID_DESK -> {
                String d = session.detail() == null ? "" : session.detail();
                yield d.startsWith("Bid desk: ") ? "Bids: " + d.substring(10) : "Working your bids";
            }
            case PAUSED -> "Paused: press Resume or End";
            case STOPPED -> running ? "" : "Idle, " + "listings still up";
        };
    }

    private static String firstClause(String text) {
        int cut = text.indexOf(';');
        String clause = cut > 0 ? text.substring(0, cut) : text;
        return clause;
    }

    private static String titleCase(String itemKey) {
        String base = itemKey == null ? "" : itemKey;
        int hash = base.indexOf('#');
        if (hash >= 0) base = base.substring(0, hash);
        int colon = base.indexOf(':');
        if (colon >= 0) base = base.substring(colon + 1);
        StringBuilder out = new StringBuilder();
        for (String word : base.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static String remaining(long untilMillis) {
        long left = Math.max(0, untilMillis - System.currentTimeMillis()) / 1000;
        return left + "s";
    }

    private static int alertWidth(Font font, Alert alert, int screenWidth, int margin) {
        int text = font.width(alert.title());
        text = Math.max(text, font.width(alert.lineOne()));
        if (!alert.lineTwo().isBlank()) text = Math.max(text, font.width(alert.lineTwo()));
        if (!alert.lineThree().isBlank()) text = Math.max(text, font.width(alert.lineThree()));
        boolean hasIcon = ItemIcons.stackFor(alert.itemKey(), 1) != null;
        int need = text + 24 + (hasIcon ? 28 : 0);
        return UiMotion.boundedWidth(need, MIN_ALERT_WIDTH, MAX_ALERT_WIDTH, screenWidth, margin);
    }

    private record AlertText(net.minecraft.util.FormattedCharSequence text, int color) { }

    private static List<AlertText> alertLines(Font font, Alert alert, int width) {
        int room = Math.max(1, width - 24 - (ItemIcons.stackFor(alert.itemKey(), 1) != null ? 28 : 0));
        List<AlertText> lines = new ArrayList<>();
        String[] fields = {alert.title(), alert.lineOne(), alert.lineTwo(), alert.lineThree()};
        int[] colors = {alert.type().accent, UiTheme.TEXT,
                alert.type() == Type.SALE ? alert.type().accent : UiTheme.SECONDARY, UiTheme.MUTED};
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].isBlank()) continue;
            for (var line : font.split(net.minecraft.network.chat.Component.literal(fields[i]), room))
                lines.add(new AlertText(line, colors[i]));
        }
        return lines;
    }

    private static void drawAlert(GuiGraphicsExtractor graphics, Font font, Alert alert,
                                  int x, int y, int width, int height, int alpha, double remaining) {
        if (alpha <= 3) return;
        int accent = withAlpha(alert.type().accent, alpha);
        graphics.fill(x + 2, y + 3, x + width + 2, y + height + 3, withAlpha(0x50000000, alpha));
        graphics.fill(x, y, x + width, y + height, withAlpha(UiTheme.BACKGROUND, alpha));
        graphics.outline(x, y, width, height, withAlpha(UiTheme.EDGE, alpha));
        graphics.fill(x + 1, y + 1, x + 3, y + height - 1, accent);
        int textX = x + 12;
        int textWidth = Math.max(1, width - 24);
        var icon = ItemIcons.stackFor(alert.itemKey(), 1);
        if (icon != null) {
            // Reserve the slot throughout the fade so the copy never jumps sideways.
            graphics.fill(x + 9, y + 10, x + 31, y + 32, withAlpha(UiTheme.SURFACE, alpha));
            if (alpha > 180) graphics.item(icon, x + 12, y + 13);
            textX += 28;
            textWidth = Math.max(1, textWidth - 28);
        }
        int lineY = y + 9;
        for (var line : alertLines(font, alert, width)) {
            graphics.text(font, line.text(), textX, lineY, withAlpha(line.color(), alpha), false);
            lineY += 12;
        }
        graphics.fill(x + 10, y + height - 5, x + width - 10, y + height - 4, withAlpha(UiTheme.EDGE, alpha));
        int progress = (int) Math.round(Math.max(0, width - 20) * remaining);
        graphics.fill(x + 10, y + height - 5, x + 10 + progress, y + height - 4, accent);
    }

    private void add(Alert alert) {
        if (alert == null || alert.title().isBlank()) return;
        history.addFirst(alert);
        while (history.size() > 80) history.removeLast();
        alerts.addLast(alert);
        while (alerts.size() > MAX_VISIBLE) alerts.removeFirst();
    }

    /** Every alert lingers this much longer, so long error text can be read. */
    private static final long EXTRA_DISPLAY_MILLIS = 2_000;

    private void prune(long now) {
        alerts.removeIf(alert -> now - alert.createdAt() >= alert.durationMillis() + EXTRA_DISPLAY_MILLIS);
        pruneSaleDedupe(now);
    }

    private void pruneSaleDedupe(long now) {
        recentSaleMessages.entrySet().removeIf(entry -> now - entry.getValue() > 30_000L);
    }

    private void deferHiddenAlerts(long hiddenAt, long now) {
        if (alerts.isEmpty() || now <= hiddenAt) return;
        Deque<Alert> shifted = new ArrayDeque<>(alerts.size());
        for (Alert alert : alerts) {
            long invisibleFrom = Math.max(hiddenAt, alert.createdAt());
            long shift = Math.max(0L, now - invisibleFrom);
            shifted.addLast(new Alert(alert.type(), alert.title(), alert.lineOne(),
                    alert.lineTwo(), alert.lineThree(), alert.createdAt() + shift,
                    alert.durationMillis(), alert.itemKey()));
        }
        alerts.clear();
        alerts.addAll(shifted);
    }

    private void rememberAll(List<Opportunity> opportunities) {
        for (Opportunity opportunity : opportunities) {
            String key = listingKey(opportunity);
            if (key == null || !seenListingKeys.add(key)) continue;
            seenListingOrder.addLast(key);
            trimSeenListings();
        }
    }

    private void trimSeenListings() {
        while (seenListingOrder.size() > MAX_SEEN_LISTINGS) {
            seenListingKeys.remove(seenListingOrder.removeFirst());
        }
    }

    private static String listingKey(Opportunity opportunity) {
        if (opportunity == null || opportunity.listing() == null
                || opportunity.listing().listingKey() == null
                || opportunity.listing().listingKey().isBlank()) {
            return null;
        }
        return opportunity.listing().listingKey();
    }

    private static String currentServerIdentity(Minecraft client) {
        if (client == null) return null;
        if (client.isLocalServer() || client.hasSingleplayerServer()) return "singleplayer";
        var server = client.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return null;
        return server.ip.strip().toLowerCase(Locale.ROOT);
    }

    private static String compactWhitespace(String value) {
        if (value == null) return "";
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String displayItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return "Unknown item";
        String value = itemId;
        int colon = value.indexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) value = value.substring(colon + 1);
        value = value.replace('_', ' ');
        StringBuilder result = new StringBuilder(value.length());
        boolean upper = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ') {
                result.append(c);
                upper = true;
            } else {
                result.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return result.toString();
    }

    private static String money(long value) {
        double amount = value;
        String suffix = "";
        if (Math.abs(value) >= 1_000_000_000L) {
            amount /= 1_000_000_000.0;
            suffix = "b";
        } else if (Math.abs(value) >= 1_000_000L) {
            amount /= 1_000_000.0;
            suffix = "m";
        } else if (Math.abs(value) >= 1_000L) {
            amount /= 1_000.0;
            suffix = "k";
        }
        if (suffix.isEmpty()) return "$" + value;
        String pattern = Math.abs(amount) >= 100 ? "%.0f" : "%.1f";
        return "$" + String.format(Locale.ROOT, pattern, amount) + suffix;
    }

    private static String percent(double value) {
        if (!Double.isFinite(value)) return "--";
        return String.format(Locale.ROOT, value >= 100 ? "%.0f%%" : "%.0f%%", value);
    }

    private static String fit(Font font, String text, int width) {
        return UiTheme.fit(font, text, width);
    }

    private static int withAlpha(int argb, int alpha) {
        int baseAlpha = argb >>> 24;
        int composed = baseAlpha * clamp(alpha, 0, 255) / 255;
        return (argb & 0x00FFFFFF) | (composed << 24);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

}
