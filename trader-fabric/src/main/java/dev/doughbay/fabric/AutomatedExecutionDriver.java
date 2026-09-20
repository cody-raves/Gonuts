package dev.doughbay.fabric;

import dev.doughbay.core.execution.ExecutionDriver;
import dev.doughbay.core.execution.ExecutionResult;
import dev.doughbay.core.execution.ExecutionStatus;
import dev.doughbay.core.model.NamespacedId;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Position;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import dev.doughbay.fabric.mixin.DialogScreenAccessor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live auction execution for Minecraft 26.2.
 *
 * <p>The market engine only knows the five-method {@link ExecutionDriver}
 * contract. All Minecraft-specific work stays here: commands are sent through
 * the current client connection, open container slots are inspected, and the
 * exact menu slot is activated through {@code handleContainerInput}.
 *
 * <p>Execution is deliberately a tick-driven state machine. A command, the
 * auction results screen, the confirmation screen, and the resulting inventory
 * update arrive on different client ticks; blocking inside {@link #buy} would
 * freeze the game and race the server.
 *
 * <p>The driver never chooses between indistinguishable listings. It first
 * requires an exact item id, stack size, and parsed lore price, then uses the
 * seller name to disambiguate when available. If a price cannot be parsed or
 * more than one candidate remains, it aborts instead of guessing. Player
 * inventory slots are excluded from every GUI search, so an item in the hotbar
 * cannot be mistaken for an auction listing.
 */
public final class AutomatedExecutionDriver implements ExecutionDriver {

    private static final String EXECUTION_DISABLED =
            "Authorized live execution is disabled. GoNuts is analyzing only.";
    private static final String PREFLIGHT_DISABLED =
            "Read-only automation preflight is disabled.";

    private static final int STABLE_TICKS_REQUIRED = 2;
    /** Your Items pages a pull-back will walk before giving up. */
    private static final int MAX_OWN_LISTING_PAGES = 6;
    private static final int OPEN_TIMEOUT_TICKS = 160;
    /**
     * Wall-clock backstop for a stalled phase. The tick-based timeouts here
     * assume ~20 ticks/second, but this loop runs on the render thread, which
     * the OS throttles toward ~1fps when the game window is unfocused or
     * covered - stretching an "8-second" tick timeout into minutes and freezing
     * the session (the READING_ORDERS / AUDITING_SLOTS wedge). A phase running
     * this long in real time is stuck regardless of its tick count, so abort and
     * recover in seconds instead of minutes. Set well above the longest
     * legitimate tick budget (OPEN_TIMEOUT_TICKS * 2 = ~16s at 20 TPS) so it
     * only fires when ticks have genuinely crawled.
     */
    private static final long PHASE_WALLCLOCK_TIMEOUT_MILLIS = 45_000L;
    /**
     * A read - the order house or your own orders - is quick and idempotent:
     * nothing is bought or listed, so aborting and retrying costs nothing. Give
     * a stalled read a far shorter leash than a buy or a listing (whose
     * settlement can legitimately sit waiting on the server), so a stray order
     * page recovers in seconds on its own instead of freezing the desk until
     * someone presses Escape. The per-step reset above means this only fires
     * when a single page or stage has genuinely frozen, never on a slow but
     * still-advancing multi-page sweep.
     */
    private static final long READ_WALLCLOCK_TIMEOUT_MILLIS = 15_000L;
    /**
     * How many sold-but-still-displayed rows a screen-driven buy may click
     * through. DonutSMP's auction page keeps showing a listing after it sold
     * until someone clicks it; the click then erases the row, closes the
     * screen, and the server says "This item was already bought". Nothing
     * distinguishes such a ghost from a live row before the click.
     */
    private static final int MAX_GHOST_RETRIES = 4;
    /** Marks that the confirmation being awaited was a dialog, not a container. */
    private static final int DIALOG_CONFIRMATION = -2;
    private static final int CONFIRM_TIMEOUT_TICKS = 140;
    private static final int COMPLETION_TIMEOUT_TICKS = 600;
    // The server's receipt has arrived eight seconds after the dialog closed
    // under load; giving up sooner paused the session on a buy that had
    // actually gone through.
    private static final int CLOSED_WITHOUT_RESULT_TICKS = 400;

    private static final DateTimeFormatter CAPTURE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);

    private static final String NUMBER_TOKEN =
            "[0-9]+(?:[,_ ]+[0-9]{3})*(?:\\.[0-9]+)?|[0-9]+(?:\\.[0-9]+)?";
    private static final String FIELD_DECORATION = "(?:[•*›»>\\-–—]\\s*)?";
    private static final String AUTHORITATIVE_PRICE_LABEL =
            "(?:listing\\s+price|auction\\s+price|buy\\s+price|total\\s+price|"
                    + "price(?!\\s+per\\b)|cost(?!\\s+per\\b)|total)";

    /**
     * A listing price is trusted only when the whole lore line is a recognized
     * authoritative field. Incidental amounts such as per-unit prices, fees,
     * balances, and minimum bids must not satisfy the live-price gate.
     */
    private static final Pattern AUTHORITATIVE_PRICE_PREFIX = Pattern.compile(
            "(?i)^\\s*" + FIELD_DECORATION + AUTHORITATIVE_PRICE_LABEL + "\\b");
    private static final Pattern AUTHORITATIVE_PRICE_FIELD = Pattern.compile(
            "(?i)^\\s*" + FIELD_DECORATION + AUTHORITATIVE_PRICE_LABEL
                    + "(?:\\s*(?::|=|[-–—]|»|›)\\s*|\\s+)"
                    + "\\$?\\s*(" + NUMBER_TOKEN + ")"
                    + "\\s*([kmbt])?\\s*(?:coins?)?\\s*$");

    /**
     * A listing price written as bare currency, e.g. {@code $ 22K}.
     *
     * <p>DonutSMP's auction GUI labels nothing: a listing's lore is its item
     * name and this line. Requiring a "Price"/"Cost"/"Total" label therefore
     * matched no real listing on that server.
     *
     * <p>The leading {@code $} must open the line, which excludes the
     * {@code ~$ 3.6K} sell-value hint the server paints on items in the
     * player's own inventory. Those are estimates of what a shop would pay,
     * not listing prices, and treating one as authoritative would be a
     * catastrophic misread.
     */
    private static final Pattern BARE_PRICE_FIELD = Pattern.compile(
            "(?i)^\\s*\\$\\s*(" + NUMBER_TOKEN + ")\\s*([kmbt])?\\s*(?:coins?)?\\s*$");

    /** Recognized auction ownership fields; arbitrary username mentions do not count. */
    private static final Pattern SELLER_FIELD = Pattern.compile(
            "(?i)^\\s*" + FIELD_DECORATION
                    + "(?:seller(?:\\s+name)?|owner|listed\\s+by|sold\\s+by)"
                    + "(?:\\s*(?::|=|[-–—]|»|›)\\s*|\\s+)"
                    + "([A-Za-z0-9_]{1,16})\\s*$");
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private enum Operation {
        NONE,
        /** Search and verify one listing, but never click any GUI control. */
        DRY_RUN,
        BUY,
        LIST,
        /** Pull one of the player's own listings back into the inventory. */
        CANCEL,
        /** Read the player's own listings page and report what is up. */
        AUDIT,
        /** Search one market and report the cheapest live ask that is not ours. */
        PROBE,
        /** Read the order house: every open buy order, sorted by most per item. */
        ORDERS,
        /** Hand the selected stack to an order that pays for it. */
        DELIVER,
        /** Read the player's own orders page. */
        OWN_ORDERS,
        /** Create a buy order through the order house dialogs. */
        PLACE_ORDER,
        /** Collect what was delivered to one of the player's own orders. */
        COLLECT_ORDER,
        /** Cancel one of the player's own orders. */
        CANCEL_ORDER
    }

    /** Public, mutation-free view of what the state machine intends to do. */
    public enum OperationIntent {
        NONE,
        PREFLIGHT,
        BUY,
        LIST,
        CANCEL,
        AUDIT,
        PROBE,
        ORDERS,
        DELIVER,
        OWN_ORDERS,
        PLACE_ORDER,
        COLLECT_ORDER,
        CANCEL_ORDER
    }

    /** Terminal outcomes are immutable facts, not display strings. */
    public enum TerminalOutcome {
        NONE,
        SUCCEEDED,
        ABORTED,
        EMERGENCY_STOPPED
    }

    public enum PreflightOutcome {
        NOT_RUN,
        RUNNING,
        PASSED,
        FAILED,
        REFUSED
    }

    private enum Phase {
        IDLE,
        WAITING_FOR_LISTING,
        WAITING_FOR_CONFIRMATION,
        WAITING_FOR_COMPLETION,
        ABORTED
    }

    private enum Resolution {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS,
        PRICE_MISMATCH
    }

    private Operation operation = Operation.NONE;
    private Phase phase = Phase.IDLE;

    private Opportunity opportunity;
    private String targetListingKey = "";
    private String targetItemKey = "";
    private String targetItemId = "";
    private int targetCount;
    private long targetPrice;
    private String targetSeller = "";
    /**
     * A price ceiling instead of an exact target. Positive only for a
     * screen-driven buy: any stack size qualifies, the cheapest visible row
     * at or under the ceiling is clicked, and the confirmation's exact price
     * must land within that row's displayed precision and under the ceiling.
     */
    private long targetCeiling;
    /** Width of the clicked row's displayed price step in ceiling mode. */
    private long targetPriceSpan = 1;
    /** In ceiling mode, the only stack size that qualifies (0 = any). */
    private int targetRequiredCount;
    /**
     * How many more times the search may be repeated while no row clears
     * the ceiling. Cheap rows are taken within seconds of appearing, so a
     * single look at the page rarely catches one; a hunt re-reads the page
     * every second or so and clicks the moment a qualifying row shows up.
     */
    private int huntLooksRemaining;

    /** One market the recent-listings watch may buy: any row of this item and stack at or under the ceiling. */
    public record WatchTarget(String itemId, int count, long ceiling) {
    }

    /**
     * The recent-listings watch: instead of searching one market, the whole
     * auction house's "Recently Listed" page is re-read every few seconds
     * and the first row under any watched market's ceiling is bought.
     * Underpriced rows survive seconds; this is where they are caught.
     */
    private List<WatchTarget> watchTargets = List.of();
    private boolean watching;
    private int filterClicks;
    /**
     * Between looks the page is closed and the screen is clear: the watch
     * opens, reads once, closes, and waits off-screen for the next look.
     */
    private boolean watchWaiting;
    /**
     * The gap before the next look, drawn fresh each time: mostly 2 to 6
     * seconds, occasionally 8 to 15. A metronome is the easiest thing to
     * notice in a log; a player's rhythm is not one.
     */
    private int nextWatchDelayTicks = WATCH_INTERVAL_TICKS;
    private long watchOpenedAtMillis;
    /** Pages turned in this look, and the highest page number seen, so a stale page is not re-read. */
    private int watchPagesRead;
    private int watchLastPage;

    /**
     * A search term this watch is filtered by, or "" for the whole Recently
     * Listed page. Filled boxes are the reason this exists: they are a market
     * of their own that only ever turned up by accident, on pages being read
     * for something else.
     */
    private String watchSearchTerm = "";
    /** Pages this particular watch reads, or 0 to use the ordinary setting. */
    private int watchPagesOverride;

    /** How deep into the auction one look goes; 1 keeps the old behaviour of page one only. */
    private int watchPagesWanted() {
        if (watchPagesOverride > 0) return watchPagesOverride;
        return Math.max(1, (int) Tuning.get("watch.pages"));
    }
    private static final java.util.Random PACE = new java.util.Random();

    /** 1.0 is the normal rhythm; below it the looks come sooner (a quiet field). */
    private volatile double paceScale = 1.0;

    public void setPaceScale(double scale) {
        paceScale = Math.max(0.3, Math.min(2.0, scale));
    }

    /**
     * The pause between two steps of the same action: after the page opens,
     * before the row is clicked, after the stack is in hand, before the price
     * is typed. A person takes a beat at each of those; the mod used to take a
     * fifth of a second every time, which is a signature of its own. Every
     * pause is drawn fresh, so no two runs look alike.
     */
    private int humanStep(int atLeastTicks) {
        if (Tuning.get("pace.human_steps") < 0.5) return atLeastTicks;
        int min = (int) Math.round(Tuning.get("pace.step_min_sec") * 20);
        int max = Math.max(min, (int) Math.round(Tuning.get("pace.step_max_sec") * 20));
        int drawn = min + PACE.nextInt(max - min + 1);
        // Widen the click settle under server strain too, so navigation clicks
        // (Your Orders, page arrows) register instead of being dropped - a
        // dropped click is what leaves the order read stalled mid-page.
        return Math.max(atLeastTicks,
                (int) Math.round(drawn * paceScale * ServerStrain.pacingMultiplier()));
    }

    private int drawWatchDelayTicks() {
        int gapMin = (int) Math.round(Tuning.get("pace.gap_min_sec") * 20);
        int gapMax = Math.max(gapMin, (int) Math.round(Tuning.get("pace.gap_max_sec") * 20));
        int longMin = (int) Math.round(Tuning.get("pace.long_gap_min_sec") * 20);
        int longMax = Math.max(longMin, (int) Math.round(Tuning.get("pace.long_gap_max_sec") * 20));
        int oneIn = (int) Tuning.get("pace.long_gap_one_in");
        int base = oneIn > 0 && PACE.nextInt(oneIn) == 0
                ? longMin + PACE.nextInt(longMax - longMin + 1)
                : gapMin + PACE.nextInt(gapMax - gapMin + 1);
        return Math.max(10, (int) Math.round(base * paceScale));
    }
    /** 0: on the auction page, looking for the chest; 1: on the own-listings page. */
    private int cancelStage;
    /**
     * Retries of the click that opens your own listings.
     *
     * <p>The click is sent, the auction page stays up, and eight seconds later
     * the pull-back is abandoned. Overnight that was 107 of 186 failures, all
     * of them reporting the auction page still open - so the chest was found
     * and the wrong one was not clicked; the click simply did not take. The
     * server drops a click that lands inside its own action cooldown, and the
     * desk is acting every few seconds.
     *
     * <p>The Your Orders navigation already answers this by clicking again
     * rather than giving up, and this is the same answer for the same problem.
     */
    private int cancelNavRetries;
    /** Pages of Your Items already walked looking for the listing to pull back. */
    private int cancelPagesTurned;
    /** Pages of your own listings the slot audit has walked so far. */
    private int auditPagesTurned;
    /** Rows gathered across those pages; the audit's answer is all of them, not the first page. */
    private final List<OwnListingRow> auditRows = new ArrayList<>();
    /**
     * Rows on your own page whose price the tooltip would not give up.
     *
     * <p>They were skipped in silence, and a skipped row is still a slot: the
     * audit read eighty-nine where the server counted ninety, so the desk
     * believed it had somewhere to put one more listing and was told no, over
     * and over. Counted here so the slot is counted even when the row cannot
     * be managed.
     */
    private int auditUnreadable;
    /** Expired listings collected back during this audit, so one bad page cannot click for ever. */
    private int auditCollects;
    /** The page just read, so a page that did not turn is not counted twice. */
    private List<OwnListingRow> lastAuditPage = List.of();
    /** Ticks spent waiting for a page to actually turn before giving up on it. */
    private int auditPageWaits;
    /** Rows that vanished on click, by (item, count, displayed price) and when; a row stays a ghost for 90 seconds. */
    private final java.util.Map<String, Long> watchGhosts = new java.util.HashMap<>();
    private static final long GHOST_MILLIS = 90_000;

    private boolean isGhost(String key) {
        Long at = watchGhosts.get(key);
        return at != null && System.currentTimeMillis() - at < GHOST_MILLIS;
    }

    private void pruneGhosts() {
        long now = System.currentTimeMillis();
        watchGhosts.values().removeIf(at -> now - at >= GHOST_MILLIS);
    }
    private static final int WATCH_INTERVAL_TICKS = 60;
    private static final int MAX_FILTER_CLICKS = 3;
    private static final String RECENTLY_LISTED = "Recently Listed";

    /** Which sort in the list this look is using; each look moves on to the next. */
    private int watchFilterIndex;

    /**
     * The auction pages this client watches, in turn.
     *
     * <p>"Recently Listed" is where a mispriced item first appears; "Lowest
     * Price" holds the ones that landed while nobody was looking. Giving each
     * client one of them splits the coverage but makes it fragile: with the
     * second client down, its sort goes unread. So the setting takes a list,
     * separated by commas, and every look moves to the next one. Both clients
     * then see everything, out of step with each other.
     */
    private static List<String> watchFilters() {
        String want = Tuning.text("watch.filter");
        if (want == null || want.isBlank()) return List.of(RECENTLY_LISTED);
        List<String> out = new ArrayList<>();
        for (String part : want.split(",")) {
            String name = part.strip();
            if (!name.isEmpty()) out.add(name);
        }
        return out.isEmpty() ? List.of(RECENTLY_LISTED) : List.copyOf(out);
    }

    private String watchFilter() {
        List<String> all = watchFilters();
        return all.get(Math.floorMod(watchFilterIndex, all.size()));
    }
    /** Cheapest qualifying-item row seen during the last screen-driven buy, 0 if none. */
    private long lastCheapestVisible;
    // Seven searches in seven seconds made the server stop opening the
    // page at all; a look every 2.5 seconds stays under that throttle.
    private static final int HUNT_INTERVAL_TICKS = 80;
    /** A hunt look has happened since the last listing capture. */
    private boolean huntedSinceCapture;
    /** Rows (stack size, displayed price) that vanished on click this attempt. */
    private final List<long[]> ghostRows = new ArrayList<>();
    private int ghostRetries;
    /** Set when the server says the clicked listing was already bought. */
    private boolean ghostSignalled;
    /** The server's own purchase receipt ("You bought 2 Ender Pearls for $ 999"). */
    private String purchaseReceipt = "";
    private int inventoryCountBefore;

    private int ticksInPhase;
    private long phaseStartedAtMillis = System.currentTimeMillis();
    private int screenGoneTicks;
    private boolean sawContainerInPhase;

    private int clickedContainerId = -1;
    private String clickedScreenTitle = "";
    private String clickedScreenSignature = "";
    private int confirmationContainerId = -1;

    private String stableSignature = "";
    private int stableTicks;

    private boolean listingCaptureTaken;
    private boolean confirmationCaptureTaken;
    private Path lastCapturePath;

    private String lastMessage = "Idle";

    /*
     * Monotonic completion evidence for higher-level orchestration. The
     * sequence is never reset by clearStatus(), so a controller can safely
     * ignore every event at or below the sequence observed when it began an
     * operation. This avoids guessing success from human-readable text.
     */
    private long terminalSequence;
    private TerminalEvent lastTerminalEvent = TerminalEvent.none();

    private PreflightReport lastPreflightReport = PreflightReport.notRun();

    /**
     * Explicit private/authorized-environment opt-in. False is the safe default,
     * including before config has loaded and after any config read failure.
     */
    private volatile boolean authorizedExecutionEnabled;
    private volatile Set<String> authorizedServers = Set.of();

    /**
     * Independent permission boundary for a command-only, no-click preflight.
     * It never grants permission to {@link #buy(Opportunity)} or
     * {@link #list(Position, long)}.
     */
    private volatile boolean preflightEnabled;
    private volatile Set<String> preflightServers = Set.of();

    public synchronized void setAuthorizedExecutionEnabled(boolean enabled) {
        authorizedExecutionEnabled = enabled;
        if (!enabled && isLiveActive()) {
            publishTerminal(TerminalOutcome.ABORTED, EXECUTION_DISABLED);
            clearTarget();
            phase = Phase.ABORTED;
            resetPhaseObservation();
            lastMessage = "ABORTED: " + EXECUTION_DISABLED;
        }
    }

    public synchronized boolean authorizedExecutionEnabled() {
        return authorizedExecutionEnabled;
    }

    public synchronized void setPreflightEnabled(boolean enabled) {
        preflightEnabled = enabled;
        if (!enabled && operation == Operation.DRY_RUN) {
            abort(Minecraft.getInstance(), PREFLIGHT_DISABLED);
        }
    }

    public synchronized boolean preflightEnabled() {
        return preflightEnabled;
    }

    /** Human-readable current server/environment refusal, or null when allowed. */
    public synchronized String currentEnvironmentBlocker() {
        if (!authorizedExecutionEnabled) return EXECUTION_DISABLED;
        return unavailableReason(Minecraft.getInstance());
    }

    /** Human-readable preflight-only refusal, or null when explicitly allowed. */
    public synchronized String currentPreflightBlocker() {
        if (!preflightEnabled) return PREFLIGHT_DISABLED;
        return preflightUnavailableReason(Minecraft.getInstance());
    }

    public synchronized void setAuthorizedServers(List<String> servers) {
        authorizedServers = normalizeServers(servers);
    }

    public synchronized void setPreflightServers(List<String> servers) {
        preflightServers = normalizeServers(servers);
    }

    /**
     * Starts a one-shot, read-only automation preflight.
     *
     * <p>The preflight deliberately uses the same exact listing resolver as a
     * live buy: item id, stack size, live lore price, and seller must all
     * identify one unique GUI slot after the results container has stabilized.
     * It sends only the auction search command. A successful match is captured
     * for diagnostics and then the operation stops before any container input
     * can be sent.
     *
     * <p>This is still permission-gated because it sends a server command, but
     * it has a boundary separate from live execution. Both
     * {@link #setPreflightEnabled(boolean)} and an exact match in
     * {@link #setPreflightServers(List)} are required. These settings never
     * grant permission to buy or list.
     */
    public synchronized ExecutionResult startDryRun(Opportunity requested) {
        // Re-entry must not replace a RUNNING report with REFUSED while the
        // state machine is still active. inspect().armed() drives the global B
        // emergency stop, so the active operation remains the source of truth.
        if (isActive()) {
            return ExecutionResult.refused(
                    "DRY RUN REFUSED: another DoughBay operation is already in progress");
        }
        if (!preflightEnabled) {
            return refusePreflight(requested, PREFLIGHT_DISABLED);
        }
        String invalid = validateExactOpportunity(requested, true);
        if (invalid != null) {
            return refusePreflight(requested, invalid);
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = preflightUnavailableReason(client);
        if (unavailable != null) {
            return refusePreflight(requested, unavailable);
        }

        prepareOpportunity(client, requested, Operation.DRY_RUN);

        // A dry run must always produce a capture for this exact attempt rather
        // than inheriting the live-buy session's one-time capture latch.
        listingCaptureTaken = false;
        lastCapturePath = null;
        lastPreflightReport = reportFor(requested, PreflightOutcome.RUNNING,
                List.of(), null,
                "Preflight search is running; no GUI input will be sent");

        String command = "ah search " + searchTermFor(requested);
        try {
            sendCommand(client, command);
        } catch (RuntimeException e) {
            String failure = "DRY RUN FAILED: could not send /" + command
                    + "; no GUI input was sent";
            lastPreflightReport = reportFor(requested, PreflightOutcome.FAILED,
                    List.of(), null, failure);
            publishTerminal(TerminalOutcome.ABORTED, failure);
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = failure;
            DoughBayClient.LOGGER.error("DoughBay could not start read-only dry run", e);
            return ExecutionResult.aborted(lastMessage);
        }

        enterPhase(Phase.WAITING_FOR_LISTING,
                String.format(Locale.ROOT,
                        "DRY RUN: searching for %s x%d at %,d by %s; no clicks will be sent",
                        shortName(targetItemId), targetCount, targetPrice, targetSeller));
        lastPreflightReport = reportFor(requested, PreflightOutcome.RUNNING,
                List.of(), null, lastMessage);
        notifyPlayer(client, lastMessage);
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    /** Current lifecycle/result for the Automation tab's one-shot dry run. */
    public synchronized ExecutionStatus dryRunStatus() {
        return preflightStatus();
    }

    public synchronized ExecutionStatus preflightStatus() {
        return switch (lastPreflightReport.outcome()) {
            case NOT_RUN -> preflightEnabled
                    ? new ExecutionStatus(ExecutionStatus.State.IDLE,
                    "Read-only preflight has not run", false)
                    : new ExecutionStatus(ExecutionStatus.State.DISABLED,
                    PREFLIGHT_DISABLED, false);
            case RUNNING -> new ExecutionStatus(ExecutionStatus.State.VERIFYING,
                    lastPreflightReport.detail(), true);
            case PASSED -> new ExecutionStatus(ExecutionStatus.State.IDLE,
                    lastPreflightReport.detail(), false);
            case FAILED, REFUSED -> new ExecutionStatus(ExecutionStatus.State.ABORTED,
                    lastPreflightReport.detail(), false);
        };
    }

    public synchronized PreflightReport lastPreflightReport() {
        return lastPreflightReport;
    }

    /**
     * Clears only completed/aborted UI status. Permission settings are left
     * untouched, and an active preflight or trade can never be cleared this
     * way (use the emergency stop for that).
     */
    public synchronized boolean clearStatus() {
        if (isActive()) return false;
        phase = Phase.IDLE;
        lastMessage = "Idle";
        lastPreflightReport = PreflightReport.notRun();
        lastCapturePath = null;
        resetPhaseObservation();
        return true;
    }

    public synchronized OperationIntent operationIntent() {
        return switch (operation) {
            case NONE -> OperationIntent.NONE;
            case DRY_RUN -> OperationIntent.PREFLIGHT;
            case BUY -> OperationIntent.BUY;
            case LIST -> OperationIntent.LIST;
            case CANCEL -> OperationIntent.CANCEL;
            case AUDIT -> OperationIntent.AUDIT;
            case PROBE -> OperationIntent.PROBE;
            case ORDERS -> OperationIntent.ORDERS;
            case DELIVER -> OperationIntent.DELIVER;
            case OWN_ORDERS -> OperationIntent.OWN_ORDERS;
            case PLACE_ORDER -> OperationIntent.PLACE_ORDER;
            case COLLECT_ORDER -> OperationIntent.COLLECT_ORDER;
            case CANCEL_ORDER -> OperationIntent.CANCEL_ORDER;
        };
    }

    // ------------------------------------------------------------ the player's own orders

    /** One of the player's own buy orders as shown on Your Orders. */
    /**
     * @param alreadyCancelled the server has closed this order already and is
     *                         only holding the delivered goods for collection
     */
    public record OwnOrderRow(String itemId, String itemKey, int requested, long unitPrice, int delivered,
                              long expiresInMillis, boolean alreadyCancelled) {
        public OwnOrderRow(String itemId, String itemKey, int requested, long unitPrice, int delivered,
                long expiresInMillis) {
            this(itemId, itemKey, requested, unitPrice, delivered, expiresInMillis, false);
        }

        public int remaining() {
            return Math.max(0, requested - delivered);
        }
    }

    private List<OwnOrderRow> lastOwnOrders = List.of();
    private int ownStage;
    private int ownSettleUntilTick;
    private int placeCount;
    private long placeUnitPrice;
    private String placeDisplayName = "";
    /** Search terms for the item chooser, broadest match first; the chooser searches ids, not display names. */
    private List<String> placeSearchTerms = List.of();
    private int placeSearchIndex;
    private long ownTargetUnitPrice;

    public synchronized List<OwnOrderRow> lastOwnOrders() {
        return lastOwnOrders;
    }

    private static final Pattern OWN_REQUESTED = Pattern.compile("(?i)([0-9][0-9,]*)\\s+requested");
    /** "Order Canceled" on the row: the server closed it already and only the goods remain. */
    private static final Pattern OWN_CANCELLED = Pattern.compile("(?i)order\s+cancell?ed");
    private static final Pattern OWN_EXPIRES = Pattern.compile("(?i)(?:([0-9]+)d)?\\s*(?:([0-9]+)h)?\\s*(?:([0-9]+)m)?\\s*until order expires");

    private ExecutionResult beginOwnOrdersOperation(Operation op, String itemId, int count, long unitPrice, String message) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }
        opportunity = null;
        operation = op;
        targetListingKey = op.name().toLowerCase(Locale.ROOT) + ":" + (itemId == null ? "" : itemId) + ":" + unitPrice;
        targetItemKey = itemId == null ? "" : itemId;
        targetItemId = itemId == null ? "" : itemId;
        targetCount = count;
        targetRequiredCount = count;
        targetPrice = unitPrice;
        targetCeiling = 0;
        targetPriceSpan = 1;
        cancelStage = 0;
        cancelPagesTurned = 0;
        cancelNavRetries = 0;
        auditPagesTurned = 0;
        auditRows.clear();
        auditUnreadable = 0;
        auditCollects = 0;
        lastAuditPage = List.of();
        auditPageWaits = 0;
        wrongPageReopens = 0;
        purchaseReceipt = "";
        targetSeller = "";
        clickedContainerId = -1;
        confirmationContainerId = -1;
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        lastCapturePath = null;
        inventoryCountBefore = itemId == null ? 0 : countInventory(client, itemId);
        ownStage = 0;
        ownSettleUntilTick = 0;
        ownTargetUnitPrice = unitPrice;
        placeConfirmed = false;
        placeReceipt = "";
        ownDone = false;
        try {
            sendCommand(client, "orders");
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED, "Could not open the order house");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /orders: " + e.getMessage();
            return ExecutionResult.aborted(lastMessage);
        }
        enterPhase(Phase.WAITING_FOR_LISTING, message);
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    public synchronized ExecutionResult readOwnOrders() {
        return beginOwnOrdersOperation(Operation.OWN_ORDERS, null, 0, 0, "Reading your orders");
    }

    public synchronized ExecutionResult placeOrder(String itemId, int count, long unitPrice) {
        if (itemId == null || count <= 0 || unitPrice <= 0) return ExecutionResult.refused("An order needs an item, a count and a price");
        placeCount = count;
        placeUnitPrice = unitPrice;
        placeDisplayName = displayNameFor(itemId);
        String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        List<String> terms = new ArrayList<>();
        terms.add(path);
        if (path.contains("_")) terms.add(path.substring(path.lastIndexOf('_') + 1));
        terms.add(placeDisplayName);
        placeSearchTerms = List.copyOf(terms);
        placeSearchIndex = 0;
        return beginOwnOrdersOperation(Operation.PLACE_ORDER, itemId, count, unitPrice,
                String.format(Locale.ROOT, "Placing a bid: %s x%d at %s each", shortName(itemId), count, money(unitPrice)));
    }

    public synchronized ExecutionResult collectOrder(String itemId, long unitPrice) {
        return collectOrder(itemId, unitPrice, 0);
    }

    /** Collects from the {@code ordinal}-th row (0-based) of your orders matching the item and price. */
    public synchronized ExecutionResult collectOrder(String itemId, long unitPrice, int ordinal) {
        ownMatchOrdinal = Math.max(0, ordinal);
        return beginOwnOrdersOperation(Operation.COLLECT_ORDER, itemId, 0, unitPrice,
                "Collecting " + shortName(itemId) + " delivered to your order");
    }

    private int ownMatchOrdinal;
    private int wrongPageReopens;
    private int ownNavClickedAt;
    private int ownNavRetries;
    private int collectClicks;

    public synchronized ExecutionResult cancelOrder(String itemId, long unitPrice) {
        ownMatchOrdinal = 0;
        return beginOwnOrdersOperation(Operation.CANCEL_ORDER, itemId, 0, unitPrice,
                "Cancelling your bid on " + shortName(itemId));
    }

    /** The Edit Order page offered Collect where the cancel would be: the order has a fill waiting. */
    public static final String FILL_WAITING =
            "the order has a delivery waiting; it wants collecting, not cancelling";

    /**
     * The button whose name, or failing that whose lore, says what it does.
     *
     * <p>This matched the hover name alone, and half the buttons on an Edit
     * Order page have no name at all: the page reported itself as
     * {@code [, , , , Pale Oak Log, , Collect, , , ]}. The control was sitting
     * right there and could not be seen, so a cancel that was perfectly
     * possible was reported as "shows no cancel control" and retried until
     * something stopped it.
     *
     * <p>A named button still wins, and only when none matches does the lore
     * get a say - the line under an unnamed button is what tells a player what
     * it does, so it is what tells us too.
     */
    private int findControl(AbstractContainerScreen<?> container, Inventory inventory, String nameStart, int from, int to) {
        int limit = Math.min(to, container.getMenu().slots.size());
        for (int menuSlot = from; menuSlot < limit; menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (stack.getHoverName().getString().strip().toLowerCase(Locale.ROOT).startsWith(nameStart)) return menuSlot;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null) return -1;
        for (int menuSlot = from; menuSlot < limit; menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            for (String line : tooltipLines(client, stack)) {
                String l = line.strip().toLowerCase(Locale.ROOT);
                // A lore line that merely mentions the word is not the button:
                // "you cannot cancel a filled order" contains "cancel" and is
                // the opposite of what we want. Only a line that opens with it,
                // or reads like an instruction to do it, counts.
                boolean says = l.startsWith(nameStart)
                        || l.startsWith("click to " + nameStart)
                        || l.startsWith("left click to " + nameStart)
                        || l.startsWith("> " + nameStart);
                if (!says) continue;
                DoughBayClient.LOGGER.info("DoughBay: no button named \"{}\"; slot {} says \"{}\" so taking that one",
                        nameStart, menuSlot, line.strip());
                return menuSlot;
            }
        }
        return -1;
    }

    /** Every named button on a container page, for when the one we want is not there. */
    private List<String> controlNames(AbstractContainerScreen<?> container, Inventory inventory) {
        List<String> names = new ArrayList<>();
        for (int menuSlot = 0; menuSlot < Math.min(45, container.getMenu().slots.size()); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString().strip();
            List<String> lore = tooltipLines(Minecraft.getInstance(), stack);
            names.add(name.isEmpty() && !lore.isEmpty() ? "(" + String.join(" / ", lore) + ")" : name);
        }
        return names;
    }

    private OwnOrderRow parseOwnOrder(Minecraft client, ItemStack stack) {
        int requested = -1;
        long unit = -1;
        long delivered = -1;
        long expires = -1;
        boolean cancelled = false;
        for (String raw : tooltipLines(client, stack)) {
            String line = raw.strip();
            Matcher req = OWN_REQUESTED.matcher(line);
            if (req.find()) requested = (int) parseAbbreviated(req.group(1));
            Matcher price = ORDER_PRICE.matcher(line);
            if (price.find()) unit = parseAbbreviated(price.group(1) + price.group(2));
            Matcher done = ORDER_DELIVERED.matcher(line);
            if (done.find()) delivered = parseAbbreviated(done.group(1));
            if (OWN_CANCELLED.matcher(line).find()) cancelled = true;
            Matcher exp = OWN_EXPIRES.matcher(line);
            if (exp.find()) {
                long d = exp.group(1) == null ? 0 : Long.parseLong(exp.group(1));
                long h = exp.group(2) == null ? 0 : Long.parseLong(exp.group(2));
                long m = exp.group(3) == null ? 0 : Long.parseLong(exp.group(3));
                expires = ((d * 24 + h) * 60 + m) * 60_000L;
            }
        }
        if (requested <= 0 || unit <= 0) return null;
        ItemDescriptor descriptor = ItemDescriptor.of(stack);
        String id = itemId(stack);
        return new OwnOrderRow(id, descriptor.hasParts() ? id + "#" + descriptor.hash() : id, requested, unit,
                (int) Math.max(0, delivered), expires, cancelled);
    }

    /** Your Orders and the pages behind it, for all four own-order operations. */
    private void tickOwnOrdersPage(Minecraft client, AbstractContainerScreen<?> container) {
        Inventory inventory = client.player.getInventory();
        String title = container.getTitle().getString().toLowerCase(Locale.ROOT);
        if (ticksInPhase < ownSettleUntilTick) return;
        if (ticksInPhase >= OPEN_TIMEOUT_TICKS * 2) {
            abort(client, "No progress at stage " + ownStage + " of " + operation + " on \"" + title + "\"");
            return;
        }
        if (ownStage == 0) {
            if (!title.contains("orders")) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The order house did not open (page: \"" + title + "\")");
                return;
            }
            if (title.contains("your orders")) {
                ownStage = 1;
            } else {
                // The navigation row is not always in the same place, so the
                // whole page is searched when the usual row does not have it.
                int yours = findControl(container, inventory, "your orders", 45, 54);
                if (yours < 0) yours = findControl(container, inventory, "your orders", 0, container.getMenu().slots.size());
                if (yours < 0) {
                    if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The order house shows no Your Orders control");
                    return;
                }
                ownStage = 1;
                ownNavClickedAt = ticksInPhase;
                if (!clickSlot(client, container, yours)) {
                    if (clickDeferred) return;   // paced, not impossible: try again next tick
                    abort(client, "Your Orders could not be clicked");
                    return;
                }
                resetPhaseObservation();
                ownSettleUntilTick = humanStep(6);
                return;
            }
        }
        if (ownStage == 1) {
            if (!title.contains("your orders")) {
                // Still on the order house: the click did not take. Try it
                // once more before giving up on the whole step.
                if (title.contains("orders") && ticksInPhase >= 40 && ownNavRetries < 2) {
                    ownNavRetries++;
                    int again = findControl(container, inventory, "your orders", 0, container.getMenu().slots.size());
                    if (again >= 0 && clickSlot(client, container, again)) {
                        DoughBayClient.LOGGER.info("DoughBay: the Your Orders click did not take; retry {} of 2", ownNavRetries);
                        resetPhaseObservation();
                        ownSettleUntilTick = humanStep(8);
                        return;
                    }
                }
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "Your Orders did not open (page: \"" + title + "\")");
                return;
            }
            ownNavRetries = 0;
            List<OwnOrderRow> rows = new ArrayList<>();
            List<String> unparsed = new ArrayList<>();
            int matchSlot = -1;
            int matchesSeen = 0;
            int emptySlot = -1;
            for (int menuSlot = 0; menuSlot < Math.min(45, container.getMenu().slots.size()); menuSlot++) {
                Slot slot = container.getMenu().slots.get(menuSlot);
                if (slot.container == inventory) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) {
                    if (emptySlot < 0) emptySlot = menuSlot;
                    continue;
                }
                OwnOrderRow row = parseOwnOrder(client, stack);
                if (row == null) {
                    if (menuSlot < 45) unparsed.add(stack.getHoverName().getString() + " [" + String.join(" | ", tooltipLines(client, stack)) + "]");
                    continue;
                }
                rows.add(row);
                if (row.itemId().equals(targetItemId) && row.unitPrice() == ownTargetUnitPrice) {
                    if (matchSlot < 0 && matchesSeen == ownMatchOrdinal) {
                        matchSlot = menuSlot;
                        ownTargetTooltip = List.copyOf(tooltipLines(client, stack));
                    }
                    matchesSeen++;
                }
            }
            lastOwnOrders = List.copyOf(rows);
            if (operation == Operation.OWN_ORDERS && !unparsed.isEmpty()) {
                DoughBayClient.LOGGER.info("DoughBay: {} item(s) on Your Orders did not parse as orders: {}", unparsed.size(), unparsed);
            }
            switch (operation) {
                case OWN_ORDERS -> complete(client, "Your orders read: " + rows.size() + " open");
                case PLACE_ORDER -> {
                    if (ownStage == 1 && placeCount > 0 && matchSlot >= 0 && rows.stream().anyMatch(r ->
                            r.itemId().equals(targetItemId) && r.unitPrice() == placeUnitPrice && r.requested() == placeCount && placeConfirmed)) {
                        complete(client, String.format(Locale.ROOT, "Bid placed: %s x%d at %s each",
                                shortName(targetItemId), placeCount, money(placeUnitPrice)));
                        return;
                    }
                    if (placeConfirmed) {
                        if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The new order did not appear on Your Orders");
                        return;
                    }
                    // The page starts a new order from a named control, not
                    // an empty pane; an empty slot is the fallback only.
                    //
                    // Searched past the grid as well. A page's controls sit in
                    // the row underneath its contents - the auction's own
                    // listings chest lives there and is looked for there - and
                    // this only ever searched the forty-five slots above it.
                    // So on a page whose grid was full of orders there was no
                    // empty pane to fall back on and the control was never
                    // reached, and the desk reported no way to start an order
                    // while looking straight past the button. A hundred and
                    // thirty-eight times, and every one of those pages listed
                    // nothing but the orders it already held.
                    // Back to the grid only. Searching the control row as
                    // well found something down there answering to "new" or
                    // "create" that is not this button, clicked it, and the
                    // order flow stalled: twenty-seven "no progress at stage"
                    // and twenty-one "Edit Order did not open" in under an
                    // hour, against a hundred and thirty-eight over four days
                    // before. The widening was a guess about where the button
                    // is; the full-book case below is what those aborts
                    // actually were.
                    int newOrder = -1;
                    int slots = container.getMenu().slots.size();
                    for (String name : new String[] {"new order", "create order", "place order", "create", "new"}) {
                        newOrder = findControl(container, inventory, name, 0, 45);
                        if (newOrder >= 0) break;
                    }
                    if (newOrder < 0) newOrder = emptySlot;
                    if (newOrder < 0) {
                        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
                        for (int menuSlot = 0; menuSlot < Math.min(45, slots); menuSlot++) {
                            Slot slot = container.getMenu().slots.get(menuSlot);
                            if (slot.container == inventory || slot.getItem().isEmpty()) continue;
                            names.add(slot.getItem().getHoverName().getString().strip());
                        }
                        // A grid with an order in every square is a full order
                        // book, which is a thing that happens and not a fault.
                        // Aborting called it one, and an abort counts towards
                        // the strain that holds the whole session off.
                        if (names.size() >= 45) {
                            complete(client, "The order book is full (" + names.size() + " orders); no room for another");
                            return;
                        }
                        // Wait for the page before calling the button missing.
                        //
                        // The order house repaints constantly under delivery
                        // traffic - items arriving, orders filling - so a page
                        // read a tick after it opened is often caught mid-load,
                        // the New Order control not yet drawn. Aborting on that
                        // first frame was the bulk of the order-house aborts,
                        // and each one also feeds the strain that holds the
                        // whole session off for thirty seconds, so a false
                        // abort here costs far more than the tick it saves.
                        //
                        // Give the page the same settle window every other step
                        // in this method already waits out. On a real Your
                        // Orders page the button appears within it and the
                        // order proceeds; a genuine wrong page still aborts,
                        // either here after the wait or at the outer no-progress
                        // backstop.
                        if (ticksInPhase < OPEN_TIMEOUT_TICKS) return;
                        abort(client, "No New Order control on Your Orders (page shows: " + String.join(", ", names) + ")");
                        return;
                    }
                    ownStage = 2;
                    lastMessage = "Opening a new order";
                    if (!clickSlot(client, container, newOrder)) {
                        if (clickDeferred) return;   // paced, not impossible: try again next tick
                        abort(client, "The New Order slot could not be clicked");
                        return;
                    }
                    resetPhaseObservation();
                }
                case COLLECT_ORDER, CANCEL_ORDER -> {
                    if (ownStage == 1 && ownDone) {
                        if (operation == Operation.CANCEL_ORDER) {
                            if (matchSlot < 0 || !cancelOrderReceipt.isEmpty()) {
                                complete(client, "Bid on " + shortName(targetItemId) + " cancelled"
                                        + (cancelOrderReceipt.isEmpty() ? "" : " (" + cancelOrderReceipt + ")"));
                            } else if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                                abort(client, "The bid is still on Your Orders after the cancel");
                            }
                        } else if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                            abort(client, "Nothing arrived from the order");
                        }
                        return;
                    }
                    if (matchSlot < 0) {
                        abort(client, "Your order for " + shortName(targetItemId) + " at " + money(ownTargetUnitPrice) + " is not on Your Orders");
                        return;
                    }
                    ownStage = 3;
                    if (!clickSlot(client, container, matchSlot)) {
                        if (clickDeferred) return;   // paced, not impossible: try again next tick
                        abort(client, "The order could not be opened");
                        return;
                    }
                    resetPhaseObservation();
                    ownSettleUntilTick = humanStep(6);
                }
                default -> abort(client, "Unexpected operation on Your Orders");
            }
            return;
        }
        if (ownStage == 3 && title.contains("collect items") && operation == Operation.COLLECT_ORDER) {
            ownStage = 5;
            collectClicks = 0;
            resetPhaseObservation();
            return;
        }
        if (ownStage == 3) {
            if (!title.contains("edit order")) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "Edit Order did not open (page: \"" + title + "\")");
                return;
            }
            String control = operation == Operation.COLLECT_ORDER ? "collect" : "cancel";
            int slot = findControl(container, inventory, control, 0, 45);
            // An order with a delivery waiting offers Collect where a cancel
            // would be, and the desk only learns that here. Our own count of
            // what has been delivered said nothing was pending, so it queued a
            // cancel; the page knows better. Say which it is rather than
            // reporting a missing button, so the desk can go and collect it.
            if (slot < 0 && operation != Operation.COLLECT_ORDER
                    && findControl(container, inventory, "collect", 0, 45) >= 0) {
                abort(client, FILL_WAITING);
                return;
            }
            if (slot < 0) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                    // "shows no cancel control" says nothing about what it did
                    // show, which is the one thing needed to tell a renamed
                    // button from a page that genuinely has no such control.
                    abort(client, "Edit Order shows no " + control + " control; the page offers "
                            + controlNames(container, inventory));
                }
                return;
            }
            ownStage = operation == Operation.COLLECT_ORDER ? 5 : 4;
            collectClicks = 0;
            if (!clickSlot(client, container, slot)) {
                if (clickDeferred) return;   // paced, not impossible: try again next tick
                abort(client, "The " + control + " control could not be clicked");
                return;
            }
            resetPhaseObservation();
            ownSettleUntilTick = humanStep(6);
            return;
        }
        if (ownStage == 4) {
            if (!title.contains("cancel order")) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The cancel confirmation did not open (page: \"" + title + "\")");
                return;
            }
            int confirm = findControl(container, inventory, "confirm", 0, 45);
            if (confirm < 0) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The cancel confirmation shows no Confirm control");
                return;
            }
            ownDone = true;
            ownStage = 1;
            if (!clickSlot(client, container, confirm)) {
                if (clickDeferred) return;   // paced, not impossible: try again next tick
                abort(client, "The cancel Confirm control could not be clicked");
                return;
            }
            resetPhaseObservation();
            ownSettleUntilTick = humanStep(8);
            return;
        }
        if (ownStage == 5) {
            // Collect opens "Orders -> Collect Items": the delivered stacks sit
            // on the page and each one is clicked into the inventory.
            int now = countInventory(client, targetItemId);
            if (title.contains("collect items")) {
                int pending = -1;
                for (int menuSlot = 0; menuSlot < Math.min(54, container.getMenu().slots.size()); menuSlot++) {
                    Slot slot = container.getMenu().slots.get(menuSlot);
                    if (slot.container == inventory) continue;
                    ItemStack stack = slot.getItem();
                    if (stack.isEmpty() || !itemId(stack).equals(targetItemId)) continue;
                    pending = menuSlot;
                    break;
                }
                if (pending >= 0) {
                    if (collectClicks >= 40) {
                        abort(client, "The collect page keeps its stacks after " + collectClicks + " clicks");
                        return;
                    }
                    collectClicks++;
                    lastMessage = "Collecting stack " + collectClicks + " from the order";
                    DoughBayClient.LOGGER.info("DoughBay collect: clicking slot {} ({} x{}) on the collect page (inventory holds {})",
                            pending, itemId(container.getMenu().slots.get(pending).getItem()),
                            container.getMenu().slots.get(pending).getItem().getCount(), now);
                    if (!quickMoveSlot(client, container, pending)) {
                        abort(client, "The stack on the collect page could not be moved");
                        return;
                    }
                    resetPhaseObservation();
                    ownSettleUntilTick = humanStep(5);
                    return;
                }
            }
            if (now > inventoryCountBefore && (collectClicks > 0 || !title.contains("collect items"))) {
                targetCount = now - inventoryCountBefore;
                complete(client, String.format(Locale.ROOT, "Collected %s x%d from your order",
                        shortName(targetItemId), targetCount));
                return;
            }
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                List<String> onPage = new ArrayList<>();
                for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
                    Slot slot = container.getMenu().slots.get(menuSlot);
                    if (slot.container == inventory || slot.getItem().isEmpty()) continue;
                    onPage.add(menuSlot + ":" + itemId(slot.getItem()) + "x" + slot.getItem().getCount());
                }
                abort(client, "Nothing arrived from the order after " + collectClicks + " click(s) (page: \"" + title + "\" showing "
                        + onPage + "; " + String.join(" | ", ownTargetTooltip) + ")");
            }
        }
    }

    private List<String> ownTargetTooltip = List.of();

    /** How many of an item the inventory holds right now. */
    public static int inventoryCount(Minecraft client, String itemId) {
        return countInventory(client, itemId);
    }

    private boolean placeConfirmed;
    /** The server's "You ordered 64 Empty Maps" line after Create Order; the page it returns to varies. */
    private String placeReceipt = "";
    private static final Pattern PLACE_RECEIPT = Pattern.compile("(?i)^you ordered +([0-9,]+) +(.+)$");
    private boolean ownDone;

    private static String dialogTitle(DialogScreen<?> dialogScreen) {
        Dialog dialog = ((DialogScreenAccessor) dialogScreen).doughbay$dialog();
        return dialog == null || dialog.common() == null ? "" : dialog.common().title().getString().strip();
    }

    private static List<String> dialogLines(DialogScreen<?> dialogScreen) {
        List<String> lines = new ArrayList<>();
        Dialog dialog = ((DialogScreenAccessor) dialogScreen).doughbay$dialog();
        if (dialog == null || dialog.common() == null) return lines;
        for (DialogBody body : dialog.common().body()) {
            if (body instanceof PlainMessage message) lines.add(message.contents().getString());
            else if (body instanceof ItemBody item) item.description().ifPresent(d -> lines.add(d.contents().getString()));
        }
        return lines;
    }

    private static Button dialogButton(DialogScreen<?> dialogScreen, String label) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialogScreen.children(), buttons);
        for (Button b : buttons) {
            if (b.getMessage().getString().strip().equalsIgnoreCase(label)) return b;
        }
        return null;
    }

    private static void press(Button button) {
        button.onPress(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
    }

    /** The chooser's search button, whatever the server calls it. */
    private static Button searchButton(DialogScreen<?> dialogScreen) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialogScreen.children(), buttons);
        for (Button b : buttons) {
            String name = b.getMessage().getString().toLowerCase(Locale.ROOT);
            if (name.contains("search") || name.contains("suchen") || name.contains("such")
                    || name.contains("find") || name.contains("buscar") || name.contains("rechercher")) {
                return b;
            }
        }
        return buttons.size() == 1 ? buttons.get(0) : null;
    }

    /** Every button on a dialog, for a message that says what was actually there. */
    private static String dialogButtonNames(DialogScreen<?> dialogScreen) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialogScreen.children(), buttons);
        StringBuilder out = new StringBuilder();
        for (Button b : buttons) {
            if (out.length() > 0) out.append(", ");
            out.append('"').append(b.getMessage().getString()).append('"');
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    /** The item chooser, whatever language the server names it in. */
    private static boolean isChooseItemDialog(String lowerTitle) {
        return lowerTitle.contains("choose item")
                || lowerTitle.contains("select item")
                || lowerTitle.contains("select an item")
                || lowerTitle.contains("choose an item")
                || lowerTitle.contains("gegenstand");        // "Gegenstand auswählen"
    }

    /** The order-creation dialogs: choose the item, the amount, the price, review, create. */
    private void tickPlaceDialog(Minecraft client, DialogScreen<?> dialogScreen) {
        sawContainerInPhase = true;
        if (ticksInPhase < ownSettleUntilTick) return;
        String title = dialogTitle(dialogScreen).toLowerCase(Locale.ROOT);
        List<EditBox> boxes = new ArrayList<>();
        collectEditBoxes(dialogScreen.children(), boxes);
        // The server does not always title this dialog in English: it came
        // back as "Gegenstand auswählen" on 2026-09-04. The dialog is the same
        // one, so it is recognised by any of its names rather than by luck.
        if (isChooseItemDialog(title)) {
            if (ownStage == 2) {
                // The button is not always called "Search": the German dialog
                // calls it something else and every bid the main account tried
                // on 2026-09-04 died here. Any of its names will do, and
                // failing that the dialog's only button is the one.
                Button search = searchButton(dialogScreen);
                if (boxes.isEmpty() || search == null) {
                    if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                        abort(client, "The item chooser has no search box (buttons: "
                                + dialogButtonNames(dialogScreen) + ")");
                    }
                    return;
                }
                boxes.get(0).setValue(placeSearchTerms.get(placeSearchIndex));
                lastMessage = "Searching the item chooser for " + placeSearchTerms.get(placeSearchIndex);
                ownStage = 6;
                press(search);
                resetPhaseObservation();
                ownSettleUntilTick = humanStep(8);
                return;
            }
            // Nothing found, or nothing named like the item: the next term.
            if (title.contains("(0 results)")) {
                if (placeSearchIndex + 1 < placeSearchTerms.size()) {
                    placeSearchIndex++;
                    ownStage = 2;
                    resetPhaseObservation();
                    ownSettleUntilTick = humanStep(4);
                    return;
                }
                abort(client, "The item chooser finds nothing for " + String.join(" / ", placeSearchTerms));
                return;
            }
            // After the search: the button named exactly like the item.
            List<Button> buttons = new ArrayList<>();
            collectButtons(dialogScreen.children(), buttons);
            Button pick = null;
            String wanted = placeDisplayName.toLowerCase(Locale.ROOT);
            for (Button b : buttons) {
                // The button carries the item sprite as a bracketed marker
                // before the name: "[item/oak_log@items] Oak Log".
                String raw = b.getMessage().getString();
                String label = raw.substring(raw.lastIndexOf(']') + 1)
                        .replaceAll("[^A-Za-z0-9 ()']", " ").replaceAll(" +", " ").strip().toLowerCase(Locale.ROOT);
                if (label.equals(wanted)) {
                    pick = b;
                    break;
                }
            }
            if (pick == null) {
                if (ticksInPhase < OPEN_TIMEOUT_TICKS / 2) return;
                if (placeSearchIndex + 1 < placeSearchTerms.size()) {
                    placeSearchIndex++;
                    ownStage = 2;
                    resetPhaseObservation();
                    ownSettleUntilTick = humanStep(4);
                    return;
                }
                List<String> labels = new ArrayList<>();
                for (Button b : buttons) labels.add(b.getMessage().getString().strip());
                abort(client, "No item button matches " + placeDisplayName + " (buttons: " + String.join(", ", labels) + ")");
                return;
            }
            ownStage = 7;
            press(pick);
            resetPhaseObservation();
            ownSettleUntilTick = humanStep(6);
            return;
        }
        if (title.contains("how many")) {
            Button next = dialogButton(dialogScreen, "Next");
            if (boxes.isEmpty() || next == null) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The amount dialog has no box or Next");
                return;
            }
            boxes.get(0).setValue(String.valueOf(placeCount));
            ownStage = 8;
            press(next);
            resetPhaseObservation();
            ownSettleUntilTick = humanStep(6);
            return;
        }
        // Ordering anything enchantable opens a chooser first: a trident offers
        // Riptide, Channeling, Impaling and the rest, each with its levels. The
        // desk wants the plain item at the plain price, so the chooser is
        // skipped. Without this the order was abandoned and the item struck off
        // as one the server would not take.
        if (title.contains("choose enchantments") || title.contains("enchantment")) {
            Button skip = dialogButton(dialogScreen, "Skip Enchantments");
            if (skip == null) skip = dialogButton(dialogScreen, "Skip");
            if (skip == null) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                    abort(client, "The enchantment chooser has no skip (buttons: " + dialogButtonNames(dialogScreen) + ")");
                }
                return;
            }
            DoughBayClient.LOGGER.info("DoughBay bid desk: skipping the enchantment chooser for {}", placeDisplayName);
            press(skip);
            resetPhaseObservation();
            ownSettleUntilTick = humanStep(6);
            return;
        }
        if (title.contains("price per item")) {
            Button review = dialogButton(dialogScreen, "Review Order");
            if (boxes.isEmpty() || review == null) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The price dialog has no box or Review Order");
                return;
            }
            boxes.get(0).setValue(String.valueOf(placeUnitPrice));
            ownStage = 9;
            press(review);
            resetPhaseObservation();
            ownSettleUntilTick = humanStep(6);
            return;
        }
        if (title.contains("review order")) {
            List<String> lines = dialogLines(dialogScreen);
            boolean itemOk = false;
            boolean amountOk = false;
            boolean priceOk = false;
            for (String line : lines) {
                String l = line.strip().toLowerCase(Locale.ROOT);
                if (l.startsWith("item:") && l.contains(placeDisplayName.toLowerCase(Locale.ROOT))) itemOk = true;
                if (l.startsWith("amount:") && l.replaceAll("[^0-9]", "").equals(String.valueOf(placeCount))) amountOk = true;
                if (l.startsWith("price:")) {
                    Matcher m = ORDER_PRICE.matcher(line);
                    if (m.find()) {
                        long shown = parseAbbreviated(m.group(1) + m.group(2));
                        String num = m.group(1);
                        String suffix = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
                        long grain = suffix.startsWith("k") ? 1_000L : suffix.startsWith("m") ? 1_000_000L : suffix.startsWith("b") ? 1_000_000_000L : 1L;
                        int decimals = num.contains(".") ? num.length() - num.indexOf('.') - 1 : 0;
                        for (int i = 0; i < decimals && grain > 1; i++) grain /= 10;
                        priceOk = Math.abs(shown - placeUnitPrice) < Math.max(2, grain);
                    }
                }
            }
            Button create = dialogButton(dialogScreen, "Create Order");
            if (!itemOk || !amountOk || !priceOk || create == null) {
                abort(client, "Review Order does not match the bid (" + String.join(" | ", lines) + ")");
                return;
            }
            placeConfirmed = true;
            ownStage = 1;
            press(create);
            resetPhaseObservation();
            ownSettleUntilTick = humanStep(8);
            return;
        }
        if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "An unexpected dialog opened while placing a bid: " + title);
    }

    private int deliverStage;
    private long deliverUnitPrice;
    private String deliverSearch = "";
    private long deliverExpectedTotal;
    private int deliverSettleUntilTick;
    private String deliverReceipt = "";
    private volatile String lastDeliveryReceipt = "";
    private volatile long lastDeliveryReceiptAt;

    /** The most recent "You delivered N X and received $Y" line, whenever it arrived. */
    public String lastDeliveryReceipt() {
        return lastDeliveryReceipt;
    }

    public long lastDeliveryReceiptAt() {
        return lastDeliveryReceiptAt;
    }

    /** The money named by that receipt, or 0. */
    public long lastDeliveryReceiptAmount() {
        Matcher m = DELIVER_RECEIPT.matcher(lastDeliveryReceipt);
        return m.find() ? parseAbbreviated(m.group(3) + m.group(4)) : 0;
    }
    private static final Pattern DELIVER_RECEIPT = Pattern.compile(
            "(?i)you delivered\\s+([0-9,]+)\\s+(.+?)\\s+and received\\s+\\$\\s*([0-9][0-9.,]*)\\s*([kmb]?)");

    /**
     * Hands the selected stack to the best open order for it: opens the order
     * house, searches the item, clicks the highest-paying order that still
     * wants the whole stack at no less than {@code unitPrice}, moves the
     * stack into the deliver page, closes it so the server offers the
     * confirmation, confirms, and completes on the server's receipt line.
     */
    public synchronized ExecutionResult deliver(Position requested, long unitPrice) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        if (requested == null || unitPrice <= 0) {
            return ExecutionResult.refused("Cannot deliver a missing position");
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }
        String itemId = baseItemId(requested.itemKey());
        ItemStack selected = client.player.getInventory().getSelectedItem();
        if (selected.isEmpty() || !itemId(selected).equals(itemId) || selected.getCount() != requested.quantity()) {
            return ExecutionResult.refused("Hold the position's exact stack in the selected hotbar slot");
        }
        if (ItemDescriptor.of(selected).hasParts()) {
            return ExecutionResult.refused("Only a plain stack is delivered automatically");
        }
        opportunity = null;
        operation = Operation.DELIVER;
        targetListingKey = "deliver:" + requested.positionId();
        targetItemKey = requested.itemKey();
        targetItemId = itemId;
        targetCount = requested.quantity();
        targetRequiredCount = requested.quantity();
        targetPrice = 0;
        targetCeiling = 0;
        targetPriceSpan = 1;
        cancelStage = 0;
        cancelPagesTurned = 0;
        cancelNavRetries = 0;
        auditPagesTurned = 0;
        auditRows.clear();
        auditUnreadable = 0;
        auditCollects = 0;
        lastAuditPage = List.of();
        auditPageWaits = 0;
        wrongPageReopens = 0;
        purchaseReceipt = "";
        targetSeller = "";
        clickedContainerId = -1;
        confirmationContainerId = -1;
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        lastCapturePath = null;
        inventoryCountBefore = countInventory(client, itemId);
        deliverStage = 0;
        deliverUnitPrice = unitPrice;
        deliverSearch = searchTermForItemId(itemId);
        deliverExpectedTotal = unitPrice * requested.quantity();
        deliverSettleUntilTick = 0;
        deliverReceipt = "";
        try {
            sendCommand(client, "orders");
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED, "Could not open the order house");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /orders: " + e.getMessage();
            return ExecutionResult.aborted(lastMessage);
        }
        enterPhase(Phase.WAITING_FOR_LISTING, String.format(Locale.ROOT,
                "Delivering %s x%d to an order paying %s each", shortName(itemId), targetCount, money(unitPrice)));
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    private static void collectEditBoxes(List<? extends GuiEventListener> children, List<EditBox> out) {
        for (GuiEventListener child : children) {
            if (child instanceof EditBox box) {
                out.add(box);
            } else if (child instanceof ContainerEventHandler container) {
                collectEditBoxes(container.children(), out);
            }
        }
    }

    /** The order house's search dialog: type the item, press Search. */
    private void tickDeliverDialog(Minecraft client, DialogScreen<?> dialogScreen) {
        sawContainerInPhase = true;
        if (deliverStage != 1) {
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "An unexpected dialog opened during delivery");
            return;
        }
        List<EditBox> boxes = new ArrayList<>();
        collectEditBoxes(dialogScreen.children(), boxes);
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialogScreen.children(), buttons);
        Button search = null;
        for (Button b : buttons) {
            if (b.getMessage().getString().strip().equalsIgnoreCase("search")) search = b;
        }
        if (boxes.isEmpty() || search == null) {
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The order search dialog has no text box or Search button");
            return;
        }
        boxes.get(0).setValue(deliverSearch);
        deliverStage = 2;
        lastMessage = "Searching the order house for " + deliverSearch;
        search.onPress(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
        resetPhaseObservation();
        deliverSettleUntilTick = humanStep(10);
    }

    private void tickDeliverPage(Minecraft client, AbstractContainerScreen<?> container) {
        Inventory inventory = client.player.getInventory();
        String title = container.getTitle().getString().toLowerCase(Locale.ROOT);
        if (ticksInPhase < deliverSettleUntilTick) return;
        if (deliverStage == 0 || deliverStage == 2) {
            if (!title.contains("orders") || title.contains("deliver")) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The order house did not open (page: \"" + title + "\")");
                return;
            }
            if (deliverStage == 0) {
                int searchSlot = -1;
                for (int menuSlot = 45; menuSlot < Math.min(54, container.getMenu().slots.size()); menuSlot++) {
                    Slot slot = container.getMenu().slots.get(menuSlot);
                    if (slot.container == inventory) continue;
                    ItemStack stack = slot.getItem();
                    if (!stack.isEmpty() && stack.getHoverName().getString().strip().equalsIgnoreCase("search")) searchSlot = menuSlot;
                }
                if (searchSlot < 0) {
                    if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The order house shows no Search control");
                    return;
                }
                deliverStage = 1;
                if (!clickSlot(client, container, searchSlot)) {
                    if (clickDeferred) return;   // paced, not impossible: try again next tick
                    abort(client, "The order house Search control could not be clicked");
                    return;
                }
                resetPhaseObservation();
                return;
            }
            // Stage 2: the search results. The best order that still wants the whole stack.
            int bestSlot = -1;
            long bestUnit = 0;
            for (int menuSlot = 0; menuSlot < Math.min(45, container.getMenu().slots.size()); menuSlot++) {
                Slot slot = container.getMenu().slots.get(menuSlot);
                if (slot.container == inventory) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty() || !itemId(stack).equals(targetItemId)) continue;
                if (ItemDescriptor.of(stack).hasParts()) continue;
                if (worn(stack)) continue;   // damaged gear is not what the market stats priced
                long unit = -1;
                long remaining = -1;
                for (String line : tooltipLines(client, stack)) {
                    Matcher price = ORDER_PRICE.matcher(line);
                    if (price.find()) unit = parseAbbreviated(price.group(1) + price.group(2));
                    Matcher done = ORDER_DELIVERED.matcher(line);
                    if (done.find()) remaining = parseAbbreviated(done.group(2)) - parseAbbreviated(done.group(1));
                }
                if (unit < deliverUnitPrice * 0.999 || remaining < targetCount) continue;
                if (unit > bestUnit) {
                    bestUnit = unit;
                    bestSlot = menuSlot;
                }
            }
            if (bestSlot < 0) {
                abort(client, String.format(Locale.ROOT, "No order pays %s each for %s x%d any more",
                        money(deliverUnitPrice), shortName(targetItemId), targetCount));
                return;
            }
            deliverUnitPrice = bestUnit;
            deliverExpectedTotal = bestUnit * targetCount;
            deliverStage = 3;
            lastMessage = "Opening the order paying " + money(bestUnit) + " each";
            if (!clickSlot(client, container, bestSlot)) {
                if (clickDeferred) return;   // paced, not impossible: try again next tick
                abort(client, "The order could not be clicked");
                return;
            }
            resetPhaseObservation();
            deliverSettleUntilTick = humanStep(6);
            return;
        }
        if (deliverStage == 3) {
            if (!title.contains("deliver")) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The deliver page did not open (page: \"" + title + "\")");
                return;
            }
            // Move the selected stack into the order's grid, then close: the
            // server answers with the confirmation page.
            int selectedSlot = inventory.getSelectedSlot();
            int menuSlot = -1;
            for (int i = 0; i < container.getMenu().slots.size(); i++) {
                Slot slot = container.getMenu().slots.get(i);
                if (slot.container == inventory && slot.getContainerSlot() == selectedSlot) menuSlot = i;
            }
            ItemStack held = inventory.getItem(selectedSlot);
            if (menuSlot < 0 || held.isEmpty() || !itemId(held).equals(targetItemId) || held.getCount() != targetCount) {
                abort(client, "The stack to deliver is not in the selected slot any more");
                return;
            }
            try {
                client.gameMode.handleContainerInput(container.getMenu().containerId, menuSlot, 0,
                        ContainerInput.QUICK_MOVE, client.player);
            } catch (RuntimeException e) {
                abort(client, "The stack could not be moved into the deliver page");
                return;
            }
            deliverStage = 4;
            resetPhaseObservation();
            deliverSettleUntilTick = humanStep(4);
            return;
        }
        if (deliverStage == 4) {
            boolean inGrid = false;
            for (int i = 0; i < Math.min(45, container.getMenu().slots.size()); i++) {
                Slot slot = container.getMenu().slots.get(i);
                if (slot.container == inventory) continue;
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && itemId(stack).equals(targetItemId) && stack.getCount() == targetCount) inGrid = true;
            }
            if (!inGrid) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The stack did not land in the deliver page");
                return;
            }
            deliverStage = 5;
            lastMessage = "Stack placed; closing the page for the confirmation";
            client.player.closeContainer();
            resetPhaseObservation();
            deliverSettleUntilTick = humanStep(4);
            return;
        }
        if (deliverStage == 5) {
            if (!title.contains("confirm")) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The delivery confirmation did not open (page: \"" + title + "\")");
                return;
            }
            int confirmSlot = -1;
            for (int i = 0; i < Math.min(45, container.getMenu().slots.size()); i++) {
                Slot slot = container.getMenu().slots.get(i);
                if (slot.container == inventory) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                String name = stack.getHoverName().getString().strip().toLowerCase(Locale.ROOT);
                if (name.startsWith("confirm")) confirmSlot = i;
            }
            if (confirmSlot < 0) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "The delivery confirmation shows no Confirm control");
                return;
            }
            deliverStage = 6;
            lastMessage = "Confirming the delivery";
            if (!clickSlot(client, container, confirmSlot)) {
                if (clickDeferred) return;   // paced, not impossible: try again next tick
                abort(client, "The delivery Confirm control could not be clicked");
                return;
            }
            resetPhaseObservation();
            return;
        }
        if (deliverStage == 6) {
            if (!deliverReceipt.isEmpty()) {
                completeDelivery(client);
                return;
            }
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) abort(client, "No delivery receipt arrived after the confirmation");
        }
    }

    private void completeDelivery(Minecraft client) {
        Matcher m = DELIVER_RECEIPT.matcher(deliverReceipt);
        long received = m.find() ? parseAbbreviated(m.group(3) + m.group(4)) : -1;
        // The receipt abbreviates ("$3K"); the order's own price times the count
        // is exact and agrees with it, so that is what is booked.
        long booked = received > 0 && Math.abs(received - deliverExpectedTotal) <= Math.max(1_000, deliverExpectedTotal / 10)
                ? deliverExpectedTotal : Math.max(received, 0);
        targetPrice = booked;
        complete(client, String.format(Locale.ROOT, "Delivered %s x%d to an order for %s (%s)",
                shortName(targetItemId), targetCount, money(booked), deliverReceipt));
        // The server reopens the empty deliver page; leave the world clean.
        if (client.gui.screen() instanceof AbstractContainerScreen<?>) client.player.closeContainer();
    }

    /**
     * One buy order as read from the order house. {@code parts} are the
     * tooltip lines above the price (the requirement, part of what makes an
     * order the order it is); {@code tail} is every line below it, kept
     * verbatim and never part of identity - it is where a buyer's name would
     * be if the server prints one, which nobody has looked at yet.
     */
    public record OrderRow(String itemId, String itemKey, String descriptorJson, List<String> parts,
                           long unitPrice, long delivered, long total, int page, List<String> tail) {
    }

    private List<OrderRow> lastOrders = List.of();
    private final List<OrderRow> ordersRead = new ArrayList<>();
    private int ordersPagesWanted;
    private int ordersPagesRead;
    private int ordersLastPage;
    private int ordersFilterClicks;
    private int ordersSettleUntilTick;
    private boolean ordersFullSweep;
    private static final int ORDERS_MAX_PAGES = 200;

    /** The rows read by the last successful order-house read. */
    public synchronized List<OrderRow> lastOrders() {
        return lastOrders;
    }

    /**
     * Opens the order house and reads up to {@code pages} pages of open buy
     * orders sorted by most per item. Read-only: the only clicks are the
     * sort button and the next-page arrow.
     */
    public synchronized ExecutionResult scanOrders(int pages) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }
        opportunity = null;
        operation = Operation.ORDERS;
        targetListingKey = "orders:scan";
        targetItemKey = "";
        targetItemId = "";
        targetCount = 0;
        targetPrice = 0;
        targetCeiling = 0;
        targetPriceSpan = 1;
        targetRequiredCount = 0;
        cancelStage = 0;
        cancelPagesTurned = 0;
        cancelNavRetries = 0;
        auditPagesTurned = 0;
        auditRows.clear();
        auditUnreadable = 0;
        auditCollects = 0;
        lastAuditPage = List.of();
        auditPageWaits = 0;
        wrongPageReopens = 0;
        purchaseReceipt = "";
        targetSeller = "";
        clickedContainerId = -1;
        confirmationContainerId = -1;
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        lastCapturePath = null;
        ordersRead.clear();
        // Zero pages means every page: turn until Next Page is gone or a page is empty.
        ordersPagesWanted = pages <= 0 ? ORDERS_MAX_PAGES : pages;
        ordersFullSweep = pages <= 0;
        ordersPagesRead = 0;
        ordersLastPage = 0;
        ordersFilterClicks = 0;
        ordersTurnRetries = 0;
        lastOrdersSweepComplete = false;
        ordersSettleUntilTick = 0;
        try {
            sendCommand(client, "orders");
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED, "Could not open the order house");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /orders: " + e.getMessage();
            return ExecutionResult.aborted(lastMessage);
        }
        enterPhase(Phase.WAITING_FOR_LISTING, "Reading the order house");
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    private static final Pattern ORDER_PRICE = Pattern.compile("(?i)\\$\\s*([0-9][0-9.,]*)\\s*([kmb]?)\\s+each");
    private static final Pattern ORDER_DELIVERED = Pattern.compile("(?i)([0-9][0-9.,]*[kmb]?)\\s*/\\s*([0-9][0-9.,]*[kmb]?)\\s+delivered");
    private static final Pattern ORDER_PAGE = Pattern.compile("(?i)page\\s*([0-9]+)");
    private static final String MOST_PER_ITEM = "Most Per Item";

    /** "5.3M", "260.2", "29,700" as a whole number; -1 when unreadable. */
    static long parseAbbreviated(String text) {
        if (text == null) return -1;
        String t = text.trim().toLowerCase(Locale.ROOT).replace(",", "");
        double scale = 1;
        if (t.endsWith("k")) { scale = 1e3; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("m")) { scale = 1e6; t = t.substring(0, t.length() - 1); }
        else if (t.endsWith("b")) { scale = 1e9; t = t.substring(0, t.length() - 1); }
        try {
            return Math.round(Double.parseDouble(t) * scale);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void tickOrdersPage(Minecraft client, AbstractContainerScreen<?> container) {
        Inventory inventory = client.player.getInventory();
        String title = container.getTitle().getString();
        if (!title.toLowerCase(Locale.ROOT).contains("orders")) {
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                abort(client, "The order house did not open (page: \"" + title + "\")");
            }
            return;
        }
        if (ticksInPhase < ordersSettleUntilTick) return;
        Matcher pageMatch = ORDER_PAGE.matcher(title);
        int page = pageMatch.find() ? Integer.parseInt(pageMatch.group(1)) : ordersLastPage + 1;
        if (page <= ordersLastPage) {
            // The next page has not replaced this one yet.
            if (ticksInPhase < OPEN_TIMEOUT_TICKS) return;
            // The click did not take - a paced command, a server hiccup. Every
            // full sweep of the book had been ending this way somewhere in
            // its first ten pages and calling itself complete. Press Next
            // again before giving up.
            if (ordersTurnRetries >= 2) {
                finishOrders(client, "next page did not open");
                return;
            }
            ordersTurnRetries++;
            int again = -1;
            for (int menuSlot = 45; menuSlot < Math.min(54, container.getMenu().slots.size()); menuSlot++) {
                Slot slot = container.getMenu().slots.get(menuSlot);
                if (slot.container == inventory || slot.getItem().isEmpty()) continue;
                if (slot.getItem().getHoverName().getString().strip().toLowerCase(Locale.ROOT).startsWith("next page")) again = menuSlot;
            }
            if (again < 0) {
                finishOrders(client, "next page did not open");
                return;
            }
            lastMessage = "Order house page " + ordersLastPage + " did not turn; pressing Next again (" + ordersTurnRetries + ")";
            if (!clickSlot(client, container, again)) {
                if (clickDeferred) return;
                finishOrders(client, "next-page arrow could not be clicked");
                return;
            }
            resetPhaseObservation();
            ordersSettleUntilTick = 12;
            return;
        }
        ordersTurnRetries = 0;
        // The bottom row holds the controls: the sort filter and the page arrows.
        int filterSlot = -1;
        int nextSlot = -1;
        String selection = "";
        for (int menuSlot = 45; menuSlot < Math.min(54, container.getMenu().slots.size()); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString().strip();
            if (name.equalsIgnoreCase("Filter")) {
                filterSlot = menuSlot;
                selection = filterSelection(stack, List.of(MOST_PER_ITEM, "Most Paid", RECENTLY_LISTED));
            } else if (name.toLowerCase(Locale.ROOT).startsWith("next page")) {
                nextSlot = menuSlot;
            }
        }
        // Sorted by most per item the first pages are nothing but elytra bids;
        // recently listed gives a spread of live orders, and repeated reads
        // build the book up over the hour.
        if (ordersPagesRead == 0 && filterSlot >= 0 && !RECENTLY_LISTED.equals(selection) && ordersFilterClicks < 3) {
            ordersFilterClicks++;
            lastMessage = "Sorting the order house by recently listed (click " + ordersFilterClicks + ")";
            if (!clickSlot(client, container, filterSlot)) {
                if (clickDeferred) return;   // paced, not impossible: try again next tick
                abort(client, "The order house sort button could not be clicked");
                return;
            }
            resetPhaseObservation();
            ordersSettleUntilTick = 12;
            return;
        }
        int rowsBefore = ordersRead.size();
        for (int menuSlot = 0; menuSlot < Math.min(45, container.getMenu().slots.size()); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            List<String> tooltip = tooltipLines(client, stack);
            long unit = -1;
            long delivered = -1;
            long total = -1;
            List<String> parts = new ArrayList<>();
            List<String> tail = new ArrayList<>();
            for (int i = 1; i < tooltip.size(); i++) {
                String line = tooltip.get(i).strip();
                if (line.isEmpty()) continue;
                Matcher price = ORDER_PRICE.matcher(line);
                if (price.find()) {
                    unit = parseAbbreviated(price.group(1) + price.group(2));
                    continue;
                }
                Matcher done = ORDER_DELIVERED.matcher(line);
                if (done.find()) {
                    delivered = parseAbbreviated(done.group(1));
                    total = parseAbbreviated(done.group(2));
                    continue;
                }
                if (line.toLowerCase(Locale.ROOT).startsWith("click")) continue;
                if (unit < 0) parts.add(line);
                else tail.add(line);
            }
            if (unit <= 0 || total <= 0) continue;
            ItemDescriptor descriptor = ItemDescriptor.of(stack);
            String id = itemId(stack);
            String key = descriptor.hasParts() ? id + "#" + descriptor.hash() : id;
            ordersRead.add(new OrderRow(id, key, descriptor.toJson(), List.copyOf(parts), unit,
                    Math.max(0, delivered), total, page, List.copyOf(tail)));
        }
        int rowsOnPage = ordersRead.size() - rowsBefore;
        ordersPagesRead++;
        ordersLastPage = page;
        if (ordersFullSweep && rowsOnPage == 0) {
            finishOrders(client, "last page");
            return;
        }
        if (ordersPagesRead < ordersPagesWanted && nextSlot >= 0) {
            lastMessage = "Order house page " + page + " read; turning the page";
            if (!clickSlot(client, container, nextSlot)) {
                if (clickDeferred) return;   // paced, not impossible: try again next tick
                finishOrders(client, "next-page arrow could not be clicked");
                return;
            }
            resetPhaseObservation();
            ordersSettleUntilTick = 12;
            return;
        }
        finishOrders(client, "");
    }

    /** How many times the current page turn has been pressed again after not taking. */
    private int ordersTurnRetries;
    /** Whether the last full sweep really reached the end of the book. */
    private boolean lastOrdersSweepComplete;

    /**
     * True only when a full sweep ended because the book ran out - an empty
     * page, or no Next Page left - and not because a page turn failed or the
     * page cap was hit. Anything that expires orders on the strength of a
     * sweep must ask this, not the sweep's intent.
     */
    public synchronized boolean lastOrdersSweepComplete() {
        return lastOrdersSweepComplete;
    }

    /**
     * Pages read so far in the current order-house scan. A rising count means
     * the read is progressing - a full sweep of many pages is slow but not
     * stuck. A count that stops climbing is a genuine stall.
     */
    public synchronized int ordersPagesRead() {
        return ordersPagesRead;
    }

    /** How many pages the current order scan will read at most (its target). */
    public synchronized int ordersPagesWanted() {
        return ordersPagesWanted;
    }

    private void finishOrders(Minecraft client, String note) {
        lastOrders = List.copyOf(ordersRead);
        lastOrdersSweepComplete = ordersFullSweep
                && (note.equals("last page") || (note.isEmpty() && ordersPagesRead < ordersPagesWanted));
        complete(client, (ordersFullSweep ? "Order house swept: " : "Order house read: ") + ordersRead.size()
                + " order(s) on " + ordersPagesRead + " page(s)" + (note.isEmpty() ? "" : " (" + note + ")"));
    }

    /** One row of the player's own listings as last audited. */
    /** One of the player's own listings on the page; {@code itemKey} carries the contents hash for a box. */
    public record OwnListingRow(String itemId, String itemKey, int count, long displayedPrice, long priceUpperBound) {
        public boolean covers(long price) {
            return price >= displayedPrice && price <= priceUpperBound;
        }
    }

    private List<OwnListingRow> lastOwnListings = List.of();

    /** The rows read by the last successful own-listings audit. */
    public synchronized List<OwnListingRow> lastOwnListings() {
        return lastOwnListings;
    }

    /**
     * Whether a row on your own page is an expired listing waiting to be
     * collected, rather than one of the page's own panes and buttons.
     */
    private static boolean waitingToBeCollected(List<String> tooltip) {
        for (String line : tooltip) {
            if (line.toLowerCase(Locale.ROOT).contains("click to collect")) return true;
        }
        return false;
    }

    /** Listings the last audit saw but could not price, and so could not report. */
    public synchronized int lastOwnListingsUnreadable() {
        return lastOwnListingsUnreadable;
    }

    private int lastOwnListingsUnreadable;

    /** Latest immutable terminal event; sequence zero means no event yet. */
    /** How the market looked on the last screen-driven buy: its cheapest qualifying row. */
    /** The item the watch clicked, or "" while it is still only reading. */
    public synchronized String watchClickedItemId() {
        return operation == Operation.BUY && watching ? targetItemId : "";
    }

    /** True while a watch is only reading the page: no row has been clicked. */
    public synchronized boolean watchHasNotClicked() {
        return operation == Operation.BUY && watching && targetItemId.isEmpty();
    }

    public synchronized long lastCheapestVisible() {
        return lastCheapestVisible;
    }

    public synchronized TerminalEvent lastTerminalEvent() {
        return lastTerminalEvent;
    }

    @Override
    public synchronized ExecutionResult buy(Opportunity requested) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        String invalid = validateExactOpportunity(requested, true);
        if (invalid != null) return ExecutionResult.refused(invalid);
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }

        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }

        prepareOpportunity(client, requested, Operation.BUY);

        String command = "ah search " + searchTermFor(requested);
        try {
            sendCommand(client, command);
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED,
                    "Could not send the auction search command");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /" + command + ": " + e.getMessage();
            DoughBayClient.LOGGER.error("DoughBay could not start automated buy", e);
            return ExecutionResult.aborted(lastMessage);
        }

        enterPhase(Phase.WAITING_FOR_LISTING,
                String.format(Locale.ROOT,
                        "Searching for %s x%d at %,d; press END to emergency-stop",
                        shortName(targetItemId), targetCount, targetPrice));
        notifyPlayer(client, lastMessage);

        // ExecutionDriver predates asynchronous live execution and has no
        // STARTED outcome. AWAITING_PLAYER is the non-terminal compatible value;
        // inspect() carries the actual PREPARED/VERIFYING lifecycle state.
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    /**
     * Buys the cheapest listing of {@code itemId} visible on the search
     * screen at or under {@code maxTotalPrice}, whatever its stack size.
     *
     * <p>Chosen from the screen rather than from an API listing because the
     * API's active-listing feed lags the auction house by minutes: every
     * API-sourced target tried live had already sold ("This item was already
     * bought") while the screen showed live rows the API had never listed.
     */
    public synchronized ExecutionResult buyCheapestVisible(String itemId, long maxTotalPrice) {
        return buyCheapestVisible(itemId, maxTotalPrice, 0);
    }

    /**
     * As above, but only rows of exactly {@code requiredCount} qualify (0 for
     * any). A session values a market per stack size, so the stack it buys
     * must be the one it priced.
     */
    public synchronized ExecutionResult buyCheapestVisible(String itemId, long maxTotalPrice,
                                                           int requiredCount) {
        return buyCheapestVisible(itemId, maxTotalPrice, requiredCount, 1);
    }

    /**
     * As above, re-reading the page up to {@code looks} times (about a
     * second apart) until a qualifying row appears.
     */
    public synchronized ExecutionResult buyCheapestVisible(String itemId, long maxTotalPrice,
                                                           int requiredCount, int looks) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        final String canonical;
        try {
            canonical = NamespacedId.normalize(itemId);
        } catch (IllegalArgumentException ignored) {
            return ExecutionResult.refused("Item id is not a safe namespaced id");
        }
        if (maxTotalPrice <= 0) {
            return ExecutionResult.refused("The price ceiling must be positive");
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }

        opportunity = null;
        operation = Operation.BUY;
        targetListingKey = "ceiling:" + canonical + ":" + maxTotalPrice;
        targetItemKey = canonical;
        targetItemId = canonical;
        targetCount = 0;
        targetPrice = 0;
        targetCeiling = maxTotalPrice;
        targetPriceSpan = 1;
        targetRequiredCount = Math.max(0, requiredCount);
        lastCheapestVisible = 0;
        huntLooksRemaining = Math.max(0, looks - 1);
        ghostRows.clear();
        ghostRetries = 0;
        ghostSignalled = false;
        purchaseReceipt = "";
        targetSeller = "";
        inventoryCountBefore = countInventory(client, canonical);
        clickedContainerId = -1;
        clickedScreenTitle = "";
        clickedScreenSignature = "";
        confirmationContainerId = -1;
        // Every attempt gets its own evidence capture.
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        lastCapturePath = null;

        String command = "ah search " + searchTermForItemId(canonical);
        try {
            sendCommand(client, command);
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED,
                    "Could not send the auction search command");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /" + command + ": " + e.getMessage();
            DoughBayClient.LOGGER.error("DoughBay could not start screen-driven buy", e);
            return ExecutionResult.aborted(lastMessage);
        }

        enterPhase(Phase.WAITING_FOR_LISTING,
                String.format(Locale.ROOT,
                        "Searching for the cheapest %s at or under %,d; press END to emergency-stop",
                        shortName(canonical), maxTotalPrice));
        notifyPlayer(client, lastMessage);
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    /**
     * Watches the auction house's recent listings for any row under any of
     * the given markets' ceilings, re-reading the page about every five
     * seconds up to {@code looks} times, and buys the first that qualifies.
     */
    public synchronized ExecutionResult watchRecentListings(List<WatchTarget> targets, int looks) {
        return watchRecentListings(targets, looks, "", 0);
    }

    /**
     * As above, but the auction is filtered by {@code searchTerm} first and read
     * {@code pages} deep. A blank term with 0 pages is the ordinary watch.
     */
    public synchronized ExecutionResult watchRecentListings(List<WatchTarget> targets, int looks,
            String searchTerm, int pages) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        if (targets == null || targets.isEmpty()) {
            return ExecutionResult.refused("No market to watch for");
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }
        long maxCeiling = 0;
        for (WatchTarget target : targets) {
            if (target.ceiling() <= 0) return ExecutionResult.refused("A watch ceiling must be positive");
            maxCeiling = Math.max(maxCeiling, target.ceiling());
        }

        opportunity = null;
        operation = Operation.BUY;
        targetListingKey = "watch:all";
        targetItemKey = "";
        targetItemId = "";
        targetCount = 0;
        targetPrice = 0;
        targetCeiling = maxCeiling;
        targetPriceSpan = 1;
        targetRequiredCount = 0;
        lastCheapestVisible = 0;
        huntLooksRemaining = Math.max(0, looks - 1);
        watchTargets = List.copyOf(targets);
        watching = true;
        filterClicks = 0;
        // Ghosts are kept across looks: the same sold row sits on the page for
        // seconds, and a fresh look must not click it again.
        pruneGhosts();
        ghostRows.clear();
        ghostRetries = 0;
        ghostSignalled = false;
        purchaseReceipt = "";
        targetSeller = "";
        inventoryCountBefore = 0;
        clickedContainerId = -1;
        clickedScreenTitle = "";
        clickedScreenSignature = "";
        confirmationContainerId = -1;
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        lastCapturePath = null;

        watchSearchTerm = searchTerm == null ? "" : searchTerm.strip();
        watchPagesOverride = Math.max(0, pages);
        try {
            sendCommand(client, watchSearchTerm.isEmpty() ? "ah" : "ah search " + watchSearchTerm);
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED, "Could not open the auction house");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /ah: " + e.getMessage();
            DoughBayClient.LOGGER.error("DoughBay could not start the recent-listings watch", e);
            return ExecutionResult.aborted(lastMessage);
        }
        enterPhase(Phase.WAITING_FOR_LISTING,
                String.format(Locale.ROOT,
                        "Watching recent listings for %d markets (ceilings up to %,d); press END to emergency-stop",
                        targets.size(), maxCeiling));
        notifyPlayer(client, lastMessage);
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    /**
     * Pulls one of the player's own listings back: open the auction house,
     * click the chest that lists the player's items, click the row that
     * matches the item, stack, and price, and verify the stack arrives.
     */
    public synchronized ExecutionResult cancelOwnListing(String itemId, int count, long price) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        final String canonical;
        try {
            canonical = NamespacedId.normalize(itemId);
        } catch (IllegalArgumentException ignored) {
            return ExecutionResult.refused("Item id is not a safe namespaced id");
        }
        if (count <= 0 || price <= 0) {
            return ExecutionResult.refused("A listing to pull back needs a stack size and price");
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }

        opportunity = null;
        operation = Operation.CANCEL;
        targetListingKey = "cancel:" + canonical + ":" + price;
        targetItemKey = canonical;
        targetItemId = canonical;
        targetCount = count;
        targetPrice = price;
        targetCeiling = 0;
        targetPriceSpan = 1;
        targetRequiredCount = count;
        cancelStage = 0;
        cancelPagesTurned = 0;
        cancelNavRetries = 0;
        auditPagesTurned = 0;
        auditRows.clear();
        auditUnreadable = 0;
        auditCollects = 0;
        lastAuditPage = List.of();
        auditPageWaits = 0;
        wrongPageReopens = 0;
        ghostRows.clear();
        ghostRetries = 0;
        ghostSignalled = false;
        purchaseReceipt = "";
        targetSeller = "";
        inventoryCountBefore = countInventory(client, canonical);
        clickedContainerId = -1;
        clickedScreenTitle = "";
        clickedScreenSignature = "";
        confirmationContainerId = -1;
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        lastCapturePath = null;

        try {
            sendCommand(client, "ah");
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED, "Could not open the auction house");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /ah: " + e.getMessage();
            return ExecutionResult.aborted(lastMessage);
        }
        enterPhase(Phase.WAITING_FOR_LISTING,
                String.format(Locale.ROOT,
                        "Pulling back %s x%d listed at %,d; press END to emergency-stop",
                        shortName(canonical), count, price));
        notifyPlayer(client, lastMessage);
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    /**
     * Reads the player's own listings: open the auction house, click the
     * chest, record every row's item, stack, and price, then close. Used at
     * session start to learn what sold while the mod was offline and how
     * many slots are in use.
     */
    public synchronized ExecutionResult auditOwnListings() {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }
        opportunity = null;
        operation = Operation.AUDIT;
        targetListingKey = "audit:own";
        targetItemKey = "";
        targetItemId = "";
        targetCount = 0;
        targetPrice = 0;
        targetCeiling = 0;
        targetPriceSpan = 1;
        targetRequiredCount = 0;
        cancelStage = 0;
        cancelPagesTurned = 0;
        cancelNavRetries = 0;
        auditPagesTurned = 0;
        auditRows.clear();
        auditUnreadable = 0;
        auditCollects = 0;
        lastAuditPage = List.of();
        auditPageWaits = 0;
        wrongPageReopens = 0;
        purchaseReceipt = "";
        targetSeller = "";
        clickedContainerId = -1;
        confirmationContainerId = -1;
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        lastCapturePath = null;
        try {
            sendCommand(client, "ah");
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED, "Could not open the auction house");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /ah: " + e.getMessage();
            return ExecutionResult.aborted(lastMessage);
        }
        enterPhase(Phase.WAITING_FOR_LISTING, "Checking your auction slots");
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    /**
     * Searches one market and reports, through the terminal event's price,
     * the cheapest live ask of the given stack size that is not the
     * player's own; zero when none is visible. Read-only: no click.
     */
    public synchronized ExecutionResult probeCheapest(String itemId, int count, String ownName) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        final String canonical;
        try {
            canonical = NamespacedId.normalize(itemId);
        } catch (IllegalArgumentException ignored) {
            return ExecutionResult.refused("Item id is not a safe namespaced id");
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }
        opportunity = null;
        operation = Operation.PROBE;
        targetListingKey = "probe:" + canonical + ":" + count;
        targetItemKey = canonical;
        targetItemId = canonical;
        targetCount = Math.max(0, count);
        targetPrice = 0;
        targetCeiling = 0;
        targetPriceSpan = 1;
        targetRequiredCount = Math.max(0, count);
        targetSeller = ownName == null ? "" : ownName;
        clickedContainerId = -1;
        confirmationContainerId = -1;
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        lastCapturePath = null;
        String command = "ah search " + searchTermForItemId(canonical);
        try {
            sendCommand(client, command);
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED, "Could not send the auction search");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /" + command + ": " + e.getMessage();
            return ExecutionResult.aborted(lastMessage);
        }
        enterPhase(Phase.WAITING_FOR_LISTING,
                "Checking the cheapest live " + shortName(canonical) + " before listing");
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    private void tickProbePage(Minecraft client, AbstractContainerScreen<?> container) {
        Inventory inventory = client.player.getInventory();
        // The cheapest rows on the page are mostly ghosts: sold, still shown
        // until clicked. The median of the visible asks is what a buyer
        // actually finds available, so that is what gets undercut.
        List<Long> asks = new ArrayList<>();
        for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || menuSlot >= 45) continue;
            if (!itemId(stack).equals(targetItemId)) continue;
            if (targetRequiredCount > 0 && stack.getCount() != targetRequiredCount) continue;
            List<String> tooltip = tooltipLines(client, stack);
            if (!targetSeller.isBlank() && tooltipContainsSeller(tooltip, targetSeller)) continue;
            PriceEvidence price = authoritativePriceEvidence(tooltip);
            if (!price.sawAuthoritativeField() || price.unclear()) continue;
            asks.add(price.displayed());
        }
        asks.sort(null);
        long cheapest = asks.isEmpty() ? 0 : asks.get(0);
        long median = asks.isEmpty() ? 0 : asks.get(asks.size() / 2);
        targetPrice = median;
        complete(client, median > 0
                ? String.format(Locale.ROOT, "Live %s x%d from others: %d row(s), cheapest %s, median %s",
                        shortName(targetItemId), targetRequiredCount, asks.size(), money(cheapest), money(median))
                : String.format(Locale.ROOT, "No other %s x%d is listed right now",
                        shortName(targetItemId), targetRequiredCount));
    }

    private void tickAuditPage(Minecraft client, AbstractContainerScreen<?> container) {
        Inventory inventory = client.player.getInventory();
        if (cancelStage == 0) {
            // The chest is only clicked on an auction page. The bid desk can
            // leave an orders page up, and clicking its chest-shaped control
            // opens Your Orders, not Your Items: every failed pull-back of
            // 2026-09-04 morning was this. A wrong page is closed and the
            // auction asked for again, twice, before giving up.
            String pageTitle = container.getTitle().getString().toLowerCase(Locale.ROOT);
            if (!pageTitle.contains("auction")) {
                if (wrongPageReopens < 2) {
                    wrongPageReopens++;
                    client.player.closeContainer();
                    sendCommand(client, "ah");
                    resetPhaseObservation();
                    lastMessage = "A \"" + pageTitle + "\" page was up instead of the auction; reopening ("
                            + wrongPageReopens + " of 2)";
                    DoughBayClient.LOGGER.info("DoughBay: {}", lastMessage);
                    return;
                }
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                    abort(client, "The auction did not open (page: \"" + pageTitle + "\")");
                }
                return;
            }
            int chestSlot = ownItemsChestSlot(container, inventory);
            if (chestSlot < 0) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                    abort(client, "The auction page shows no chest for your own listings");
                }
                return;
            }
            clickedContainerId = container.getMenu().containerId;
            clickedScreenSignature = containerSignature(client, container);
            if (!clickSlot(client, container, chestSlot)) {
                if (clickDeferred) return;   // paced, not impossible: try again next tick
                abort(client, "The own-listings chest could not be clicked");
                return;
            }
            cancelStage = 1;
            lastMessage = "Reading your listings";
            resetPhaseObservation();
            return;
        }
        // Only the page titled "Auction -> Your Items" is read. Reading the
        // auction page that was still open counted strangers' rows as yours.
        if (!isOwnItemsPage(container)) {
            // An orders page has no chest for your own listings, so clicking
            // for one can never work: it is not a click that failed, it is a
            // control that is not on the screen. Six hundred and thirty-three
            // of these gave up over four days and three hundred and ten of
            // them were this - the desk had left an orders page open, and the
            // audit clicked at it three times and abandoned the whole check.
            //
            // The pull-back path has closed the wrong page and asked for the
            // auction again since September the fourth. The audit was written
            // earlier and never learned.
            String pageTitle = container.getTitle().getString().toLowerCase(Locale.ROOT);
            if (!pageTitle.contains("auction")) {
                if (wrongPageReopens < 2) {
                    wrongPageReopens++;
                    client.player.closeContainer();
                    sendCommand(client, "ah");
                    resetPhaseObservation();
                    lastMessage = "A \"" + pageTitle + "\" page was up instead of the auction; reopening ("
                            + wrongPageReopens + " of 2)";
                    DoughBayClient.LOGGER.info("DoughBay audit: {}", lastMessage);
                    return;
                }
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                    abort(client, "The auction did not open for the slot check (page: \"" + pageTitle + "\")");
                }
                return;
            }
            // The click did not take and the auction page is still up. Try it
            // again before abandoning the whole pull-back: the listing that
            // needed repricing is the one that stays at yesterday's price.
            if (ticksInPhase >= 40 && cancelNavRetries < 2) {
                int again = ownItemsChestSlot(container, inventory);
                if (again >= 0 && clickSlot(client, container, again)) {
                    cancelNavRetries++;
                    DoughBayClient.LOGGER.info(
                            "DoughBay: the own-listings click did not take; retry {} of 2", cancelNavRetries);
                    resetPhaseObservation();
                    return;
                }
            }
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                abort(client, "Your Items page did not open after clicking the chest (page: \""
                        + container.getTitle().getString() + "\")");
            }
            return;
        }
        List<OwnListingRow> rows = new ArrayList<>();
        int unreadable = 0;
        for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || menuSlot >= 45) continue;
            List<String> tips = tooltipLines(client, stack);
            PriceEvidence price = authoritativePriceEvidence(tips);
            if (!price.sawAuthoritativeField() || price.unclear()) {
                // Most of what lands here is furniture: the filler panes and
                // the "List" button, sixteen of them on a half-empty page and
                // none on a full one. Counting those as slots made the total
                // come to ninety whatever the book actually held, which reads
                // as permanently full - a worse lie than the one it replaced.
                //
                // Exactly one kind of unpriced row is a real slot: a listing
                // that expired and is waiting to be collected. It says so
                // itself.
                if (waitingToBeCollected(tips)) {
                    unreadable++;
                    DoughBayClient.LOGGER.info(
                            "DoughBay audit: an expired listing is holding a slot until it is collected: {} x{}",
                            itemId(stack), stack.getCount());
                }
                continue;
            }
            long displayed = price.displayed();
            long band = displayedBand(price.tolerance());
            String rowId = itemId(stack);
            ItemDescriptor rowDescriptor = ItemDescriptor.of(stack);
            String rowKey = rowDescriptor.isContainer() ? rowId + "#" + rowDescriptor.hash() : rowId;
            rows.add(new OwnListingRow(rowId, rowKey, stack.getCount(), displayed, displayed + band - 1));
        }
        // An expired listing is not a listing. It came back unsold, it sits on
        // this page saying "Click to collect", and it holds a slot the entire
        // time - a slot that cannot sell anything, because the item is not for
        // sale any more. Nothing in the mod was looking for them, so they only
        // ever accumulated: every listing that times out becomes one, and a
        // book of ninety slowly turns into a book of eighty, then seventy.
        //
        // One click puts the stock back in the inventory, where the ordinary
        // flow lists it again at today's price rather than yesterday's - which
        // is usually why it did not sell - and gives the slot back.
        //
        // Collected on the way through, one at a time. This page has not been
        // recorded yet, so reading it again with the row gone costs nothing
        // and keeps the count honest.
        if (Tuning.get("slots.collect_unsold") >= 0.5 && auditCollects < 12) {
            for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
                Slot slot = container.getMenu().slots.get(menuSlot);
                if (slot.container == inventory) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty() || menuSlot >= 45) continue;
                if (!waitingToBeCollected(tooltipLines(client, stack))
                        || !clickSlot(client, container, menuSlot)) {
                    continue;
                }
                auditCollects++;
                DoughBayClient.LOGGER.info(
                        "DoughBay audit: collecting an expired listing back from the auction house: {} x{}; "
                                + "the slot is freed and the stock can be listed again",
                        itemId(stack), stack.getCount());
                resetPhaseObservation();
                return;
            }
        }
        captureOnce(client, container, CaptureStage.LISTING);
        // Your listings run to several pages and this read one of them. Page
        // one holds forty-five, so an account with eighty-three listings was
        // reported as forty-five, every time, and the ledger was reconciled
        // against a number that was really "page one is full". The pull-back
        // path has walked the pages since it was written; the audit never did.
        // The click that turns the page is sent, and the next tick reads
        // whatever container is showing - which is page one again when the
        // server has not swapped it yet. Adding those rows a second time gave
        // exactly forty-five plus forty-five: a book of sixty-two reported as
        // ninety of ninety, every slot apparently taken, and the duplicates
        // counted as listings the ledger could not match.
        //
        // A page identical to the one just read is the page not having turned.
        // There is no legitimate case for it: two pages of a listing book
        // never hold the same stacks at the same prices in the same order.
        // A page identical to the one just read is the page not having turned
        // yet. It must not be counted twice - that gave forty-five plus
        // forty-five and a phantom full house - but it must not end the walk
        // either, which is the mistake that replaced it: the audit stopped at
        // page one, reported forty-five, and the server refused listings the
        // panel said there was room for. It has not turned yet is a reason to
        // look again, not a reason to stop.
        if (!rows.isEmpty() && rows.equals(lastAuditPage)) {
            if (auditPageWaits < 20) {
                auditPageWaits++;
                return;
            }
            DoughBayClient.LOGGER.info(
                    "DoughBay: the listing page never turned after {} tries; counting {} row(s)",
                    auditPageWaits, auditRows.size());
        } else {
            auditPageWaits = 0;
            lastAuditPage = List.copyOf(rows);
            auditRows.addAll(rows);
            auditUnreadable += unreadable;
        }
        int nextPageSlot = -1;
        for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (stack.getHoverName().getString().strip().toLowerCase(Locale.ROOT)
                    .startsWith("next page")) {
                nextPageSlot = menuSlot;
            }
        }
        if (nextPageSlot >= 0 && !rows.isEmpty() && auditPagesTurned < MAX_OWN_LISTING_PAGES) {
            // The count goes up when the page turns, not when we decide to
            // turn it. Incrementing first meant a click the pacing gate held
            // back for a third of a second still counted as a page walked, so
            // the audit stopped after page one and reported forty-five of a
            // book of ninety - and the buy gate, reading half the truth, kept
            // offering stock to an auction house that was already full.
            if (!clickSlot(client, container, nextPageSlot)) {
                // Deferred rather than impossible: come back for it. Anything
                // else is the same mistake in a different place.
                if (clickDeferred) return;
            } else {
                auditPagesTurned++;
                lastMessage = "Reading page " + (auditPagesTurned + 1) + " of your listings";
                resetPhaseObservation();
                return;
            }
        }
        List<OwnListingRow> all = List.copyOf(auditRows);
        int couldNotPrice = auditUnreadable;
        auditRows.clear();
        auditUnreadable = 0;
        auditCollects = 0;
        auditPagesTurned = 0;
        lastAuditPage = List.of();
        lastOwnListings = all;
        lastOwnListingsUnreadable = couldNotPrice;
        complete(client, "Auction slots checked: " + all.size() + " of your listings are up"
                + (couldNotPrice > 0 ? " and " + couldNotPrice + " could not be priced" : ""));
    }

    private void tickCancelPage(Minecraft client, AbstractContainerScreen<?> container) {
        Inventory inventory = client.player.getInventory();
        if (cancelStage == 0) {
            // The chest is only clicked on an auction page. The bid desk can
            // leave an orders page up, and clicking its chest-shaped control
            // opens Your Orders, not Your Items: every failed pull-back of
            // 2026-09-04 morning was this. A wrong page is closed and the
            // auction asked for again, twice, before giving up.
            String pageTitle = container.getTitle().getString().toLowerCase(Locale.ROOT);
            if (!pageTitle.contains("auction")) {
                if (wrongPageReopens < 2) {
                    wrongPageReopens++;
                    client.player.closeContainer();
                    sendCommand(client, "ah");
                    resetPhaseObservation();
                    lastMessage = "A \"" + pageTitle + "\" page was up instead of the auction; reopening ("
                            + wrongPageReopens + " of 2)";
                    DoughBayClient.LOGGER.info("DoughBay: {}", lastMessage);
                    return;
                }
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                    abort(client, "The auction did not open (page: \"" + pageTitle + "\")");
                }
                return;
            }
            // The chest in the auction page's control row opens the player's
            // own listings. Listings themselves sit in the rows above it.
            int chestSlot = ownItemsChestSlot(container, inventory);
            if (chestSlot < 0) {
                if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                    abort(client, "The auction page shows no chest for your own listings");
                }
                return;
            }
            captureOnce(client, container, CaptureStage.LISTING);
            clickedContainerId = container.getMenu().containerId;
            clickedScreenSignature = containerSignature(client, container);
            if (!clickSlot(client, container, chestSlot)) {
                if (clickDeferred) return;   // paced, not impossible: try again next tick
                abort(client, "The own-listings chest could not be clicked");
                return;
            }
            cancelStage = 1;
            lastMessage = "Opening your listings to pull back " + shortName(targetItemId);
            resetPhaseObservation();
            return;
        }
        if (!isOwnItemsPage(container)) {
            // The click did not take and the auction page is still up. Try it
            // again before abandoning the whole pull-back: the listing that
            // needed repricing is the one that stays at yesterday's price.
            if (ticksInPhase >= 40 && cancelNavRetries < 2) {
                int again = ownItemsChestSlot(container, inventory);
                if (again >= 0 && clickSlot(client, container, again)) {
                    cancelNavRetries++;
                    DoughBayClient.LOGGER.info(
                            "DoughBay: the own-listings click did not take; retry {} of 2", cancelNavRetries);
                    resetPhaseObservation();
                    return;
                }
            }
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                abort(client, "Your Items page did not open after clicking the chest (page: \""
                        + container.getTitle().getString() + "\")");
            }
            return;
        }

        int rowSlot = -1;
        for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !itemId(stack).equals(targetItemId)
                    || stack.getCount() != targetCount) continue;
            PriceEvidence price = authoritativePriceEvidence(tooltipLines(client, stack));
            if (!price.sawAuthoritativeField() || price.unclear()) continue;
            long displayed = price.displayed();
            long band = displayedBand(price.tolerance());
            // The page rounds ("$892.2K" for 892,154), so the match is by
            // distance within the display's resolution, either side.
            if (Math.abs(targetPrice - displayed) >= Math.max(1, band)) continue;
            rowSlot = menuSlot;
            break;
        }
        if (rowSlot < 0) {
            // Your Items is paginated and this only ever read the page it
            // happened to open on. With ninety listings up, most of them are
            // not on that page - and the ones a pull-back wants are the oldest,
            // which are the deepest in. So it reported "not among your
            // listings" for stock plainly sitting there, and gave up: pull-backs
            // succeeded about one time in ten, the stale stock was never
            // recycled, and the slots silently filled with things that would
            // not sell. Turn the page and keep looking, as the order house and
            // the watch already do.
            int nextPageSlot = -1;
            for (int menuSlot = 45; menuSlot < Math.min(54, container.getMenu().slots.size()); menuSlot++) {
                Slot slot = container.getMenu().slots.get(menuSlot);
                if (slot.container == inventory) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                if (stack.getHoverName().getString().strip().toLowerCase(Locale.ROOT)
                        .startsWith("next page")) {
                    nextPageSlot = menuSlot;
                }
            }
            if (nextPageSlot >= 0 && cancelPagesTurned < MAX_OWN_LISTING_PAGES) {
                // Counted when the page turns, not when we ask. The same
                // pre-increment that made the audit report half the book made
                // this give up looking for a listing a page early.
                if (!clickSlot(client, container, nextPageSlot)) {
                    if (clickDeferred) return;   // paced, not impossible: try again next tick
                    abort(client, "The next page of your listings could not be clicked");
                    return;
                }
                cancelPagesTurned++;
                lastMessage = "Looking for " + shortName(targetItemId) + " on page "
                        + (cancelPagesTurned + 1) + " of your listings";
                resetPhaseObservation();
                return;
            }
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                abort(client, String.format(Locale.ROOT,
                        "%s x%d at %,d is not among your listings after %d page(s) (sold or already collected?)",
                        shortName(targetItemId), targetCount, targetPrice, cancelPagesTurned + 1));
            }
            return;
        }
        listingCaptureTaken = false;
        captureOnce(client, container, CaptureStage.LISTING);
        clickedContainerId = container.getMenu().containerId;
        clickedScreenTitle = container.getTitle().getString();
        clickedScreenSignature = containerSignature(client, container);
        if (!clickSlot(client, container, rowSlot)) {
            if (clickDeferred) return;   // paced, not impossible: try again next tick
            abort(client, "Your listing could not be clicked");
            return;
        }
        enterPhase(Phase.WAITING_FOR_COMPLETION,
                String.format(Locale.ROOT, "Clicked your %s x%d; waiting for it to return to the inventory",
                        shortName(targetItemId), targetCount));
    }

    /** The own-listings page is titled "Auction -> Your Items". */
    /**
     * The slot that opens your own listings, never the one that opens your
     * orders.
     *
     * <p>Both are plain chests on the auction page, and taking the first chest
     * found meant clicking whichever sat lower. That opened "Orders (Page 1)",
     * the page check refused to read it, and the pull-back was abandoned: a
     * dozen reprices in one evening never happened and those listings stayed at
     * a price the market had already moved past. The named button is looked for
     * first, and anything calling itself an order is never clicked.
     */
    /**
     * The chest that opens your own listings.
     *
     * <p>Matched on the hover name alone, and on this page the buttons often
     * have none - so it fell through to "any chest that is not orders", which
     * on the auction page is a coin toss. Thirteen pull-backs in one session
     * died as "Your Items page did not open", one of them having landed on
     * Orders -> Your Orders, which is the fallback picking the wrong chest.
     *
     * <p>Where a button has no name the lore is what names it, exactly as on
     * the Edit Order page. The blind fallback stays, but it is now the last
     * resort rather than the second.
     */
    private static int ownItemsChestSlot(AbstractContainerScreen<?> container, Inventory inventory) {
        int fallback = -1;
        Minecraft client = Minecraft.getInstance();
        for (int pass = 0; pass < 2; pass++) {
            for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
                Slot slot = container.getMenu().slots.get(menuSlot);
                if (slot.container == inventory) continue;
                ItemStack stack = slot.getItem();
                if (stack.isEmpty() || menuSlot < 45) continue;
                String text = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
                if (pass == 1 && client != null) {
                    // Second pass: read what the button says it does.
                    text = String.join(" ", tooltipLines(client, stack)).toLowerCase(Locale.ROOT);
                }
                if (text.contains("order") || text.contains("bid")) continue;
                if (text.contains("your items") || text.contains("your listing") || text.contains("listing")) {
                    return menuSlot;
                }
                if (pass == 0 && fallback < 0 && itemId(stack).equals("minecraft:chest")) fallback = menuSlot;
            }
        }
        return fallback;
    }

    private static boolean isOwnItemsPage(AbstractContainerScreen<?> container) {
        String title = container.getTitle().getString().toLowerCase(Locale.ROOT);
        return title.contains("your items") || title.contains("your listings");
    }

    private void tickCancelCompletion(Minecraft client) {
        Screen screen = client.gui.screen();
        if (screen instanceof DialogScreen<?> dialogScreen) {
            tickConfirmationDialog(client, dialogScreen);
            return;
        }
        if (countInventory(client, targetItemId) >= inventoryCountBefore + targetCount) {
            complete(client, String.format(Locale.ROOT,
                    "Pulled back %s x%d from the auction house", shortName(targetItemId), targetCount));
            return;
        }
        if (ticksInPhase >= COMPLETION_TIMEOUT_TICKS) {
            abort(client, "Clicked your listing, but the stack did not return to the inventory within 7 seconds");
        }
    }

    /**
     * One look at the auction page while watching: make sure the hopper
     * filter says Recently Listed, then take the best row under a ceiling.
     */
    private void tickWatchPage(Minecraft client, AbstractContainerScreen<?> container) {
        Inventory inventory = client.player.getInventory();
        int filterSlot = -1;
        String selection = "";
        for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String name = stack.getHoverName().getString().strip();
            observeComponents(client, stack);
            if (name.equalsIgnoreCase("Filter")) {
                filterSlot = menuSlot;
                selection = filterSelection(stack);
                break;
            }
        }
        // Unreadable colours: the page opens on Lowest Price, and two clicks
        // from there land on Recently Listed.
        String wanted = watchFilter();
        int clicksAllowed = selection.isBlank() ? 2 : MAX_FILTER_CLICKS;
        if (filterSlot >= 0 && !wanted.equalsIgnoreCase(selection)) {
            if (filterClicks < clicksAllowed) {
                filterClicks++;
                lastMessage = "Switching the auction filter to " + wanted + " (click " + filterClicks
                        + ", currently " + (selection.isBlank() ? "unknown" : selection) + ")";
                if (!clickSlot(client, container, filterSlot)) {
                    if (clickDeferred) return;   // paced, not impossible: try again next tick
                    abort(client, "The auction filter button could not be clicked");
                    return;
                }
                resetPhaseObservation();
                return;
            }
        }
        // Which page this is. The auction titles them "Auction (Page 2)";
        // without the number a page that has not swapped yet reads twice.
        Matcher watchPageMatch = ORDER_PAGE.matcher(container.getTitle().getString());
        int thisPage = watchPageMatch.find() ? Integer.parseInt(watchPageMatch.group(1)) : watchLastPage + 1;
        if (watchPagesRead > 0 && thisPage <= watchLastPage) {
            // The click has not landed yet; wait rather than read the same rows.
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                watchLastPage = thisPage;   // give up on paging, read what is here
            } else {
                return;
            }
        }
        watchLastPage = thisPage;
        // The bottom row carries the page arrows beside the filter.
        int watchNextSlot = -1;
        for (int menuSlot = 45; menuSlot < Math.min(54, container.getMenu().slots.size()); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (stack.getHoverName().getString().strip().toLowerCase(Locale.ROOT).startsWith("next page")) {
                watchNextSlot = menuSlot;
            }
        }
        captureOnce(client, container, CaptureStage.LISTING);

        int bestSlot = -1;
        WatchTarget bestTarget = null;
        String bestItemKey = null;
        long bestDisplayed = 0;
        long bestSpan = 1;
        int bestCount = 0;
        long bestMargin = -1;
        int seen = 0;
        for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            String id = itemId(stack);
            for (WatchTarget target : watchTargets) {
                if (!target.itemId().equals(id)) continue;
                if (target.count() > 0 && stack.getCount() != target.count()) continue;
                // A plain market's statistics price plain stacks. A tipped arrow, a
                // potion or an enchanted item under the same id is another market
                // the feed cannot see, so it is never bought against this target.
                // Page rows carry the plugin's own components (lore, custom data), so
                // only value-bearing parts disqualify: potion, enchantments, trim, contents.
                if (ItemDescriptor.of(stack).hasParts()) continue;
                List<String> rowTooltip = tooltipLines(client, stack);
                // Never buy back an own listing: the fee would be the whole trade.
                if (client.player != null && tooltipContainsSeller(rowTooltip, client.player.getGameProfile().name())) continue;
                seen++;
                PriceEvidence price = authoritativePriceEvidence(rowTooltip);
                if (!price.sawAuthoritativeField() || price.unclear()) continue;
                long displayed = price.displayed();
                long band = displayedBand(price.tolerance());
                if (displayed + band - 1 > target.ceiling()) continue;
                if (isGhost(id + "|" + stack.getCount() + "|" + displayed)) continue;
                // The page is sorted newest first. The newest qualifying row has
                // had the least time to be bought by someone else, so it is the
                // least likely ghost; the cheapest row is the most likely one.
                long margin = target.ceiling() - displayed;
                if (bestTarget == null) {
                    bestMargin = margin;
                    bestSlot = menuSlot;
                    bestTarget = target;
                    bestDisplayed = displayed;
                    bestSpan = Math.max(1, price.tolerance());
                    bestCount = stack.getCount();
                }
            }
            // A filled shulker box or bundle is priced by what is inside it.
            if (bestTarget == null) {
                long[] priced = boxPrice(stack);
                if (priced != null && priced[0] > 0) {
                    PriceEvidence price = authoritativePriceEvidence(tooltipLines(client, stack));
                    if (price.sawAuthoritativeField() && !price.unclear()) {
                        long displayed = price.displayed();
                        long band = displayedBand(price.tolerance());
                        if (displayed + band - 1 <= priced[0]
                                && !isGhost(id + "|" + stack.getCount() + "|" + displayed)) {
                            bestMargin = priced[0] - displayed;
                            bestSlot = menuSlot;
                            bestTarget = new WatchTarget(id, stack.getCount(), priced[0]);
                            bestDisplayed = displayed;
                            bestSpan = Math.max(1, price.tolerance());
                            bestCount = stack.getCount();
                            bestItemKey = id + "#" + ItemDescriptor.of(stack).hash();
                            DoughBayClient.LOGGER.info("DoughBay watch: box {} at {} priced {} (ceiling {})",
                                    bestItemKey, displayed, priced[1], priced[0]);
                        }
                    }
                }
            }
        }

        if (bestTarget != null) {
            targetItemId = bestTarget.itemId();
            targetItemKey = bestItemKey != null ? bestItemKey : bestTarget.itemId();
            lastWatchClickedKey = targetItemKey;
            targetCeiling = bestTarget.ceiling();
            targetRequiredCount = bestCount;
            targetCount = bestCount;
            targetPrice = bestDisplayed;
            targetPriceSpan = bestSpan;
            targetListingKey = "watch:" + targetItemId + ":" + targetCeiling;
            inventoryCountBefore = countInventory(client, targetItemId);
            listingCaptureTaken = false;
            captureOnce(client, container, CaptureStage.LISTING);
            clickedContainerId = container.getMenu().containerId;
            clickedScreenTitle = container.getTitle().getString();
            clickedScreenSignature = containerSignature(client, container);
            if (!clickSlot(client, container, bestSlot)) {
                if (clickDeferred) return;   // paced, not impossible: try again next tick
                abort(client, "The watched listing could not be clicked");
                return;
            }
            enterPhase(Phase.WAITING_FOR_CONFIRMATION,
                    String.format(Locale.ROOT,
                            "Recent listing %s x%d shown at %s under the %s ceiling; waiting for confirmation",
                            shortName(targetItemId), targetCount, money(targetPrice), money(targetCeiling)));
            DoughBayClient.LOGGER.info("DoughBay watch: clicking {} x{} at {} (ceiling {})",
                    targetItemId, targetCount, targetPrice, targetCeiling);
            notifyPlayer(client, lastMessage);
            return;
        }

        if (huntLooksRemaining <= 0) {
            if (ticksInPhase >= WATCH_INTERVAL_TICKS) {
                abort(client, "No recent listing came in under any watched ceiling; nothing was charged");
            }
            return;
        }
        // Nothing here worth buying. Page one is where a mispriced item first
        // lands, but it is not the only place one sits: the look walks deeper
        // before closing the page, the same way the order house is read.
        watchPagesRead++;
        if (watchPagesRead < watchPagesWanted() && watchNextSlot >= 0) {
            lastMessage = String.format(Locale.ROOT,
                    "Watching %s: page %d had nothing under a ceiling; turning the page",
                    watchFilter().toLowerCase(Locale.ROOT), thisPage);
            if (clickSlot(client, container, watchNextSlot)) {
                resetPhaseObservation();
                ownSettleUntilTick = humanStep(6);
                return;
            }
        }
        lastMessage = String.format(Locale.ROOT,
                "Watching recent listings: %d row(s) of watched markets, none under a ceiling; %d look(s) left",
                seen, huntLooksRemaining);
        // Nothing to click: get the page off the screen and look again in a
        // few seconds. The page is visible for a fraction of each interval.
        watchWaiting = true;
        nextWatchDelayTicks = drawWatchDelayTicks();
        DoughBayClient.LOGGER.info(
                "DoughBay watch look: filter={} clicks={} pages={} rows={} page open {} ms; next look in {} ms",
                selection.isBlank() ? "unknown" : selection, filterClicks, watchPagesRead + 1, seen,
                watchOpenedAtMillis > 0 ? System.currentTimeMillis() - watchOpenedAtMillis : -1,
                nextWatchDelayTicks * 50);
        closeAuctionScreen(client);
    }

    /** Re-open the auction house for the next look. */
    private void watchAgain(Minecraft client) {
        huntLooksRemaining--;
        filterClicks = 0;
        watchPagesRead = 0;
        watchLastPage = 0;
        watchFilterIndex++;   // the next look reads the next sort
        watchWaiting = false;
        watchOpenedAtMillis = 0;
        huntedSinceCapture = true;
        try {
            sendCommand(client, "ah");
        } catch (RuntimeException e) {
            abort(client, "Could not re-open the auction house while watching");
            return;
        }
        enterPhase(Phase.WAITING_FOR_LISTING,
                String.format(Locale.ROOT, "Watching recent listings; %d look(s) left", huntLooksRemaining));
    }

    /**
     * Which sort the hopper's lore marks as current. The three options are
     * listed with the chosen one in a brighter colour than the other two,
     * so the odd one out is the selection.
     */
    private static String filterSelection(ItemStack stack) {
        return filterSelection(stack, List.of("Lowest Price", "Highest Price", RECENTLY_LISTED));
    }

    private static String filterSelection(ItemStack stack, List<String> options) {
        net.minecraft.world.item.component.ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return "";
        java.util.Map<String, Integer> colours = new java.util.LinkedHashMap<>();
        for (Component line : lore.lines()) {
            String text = line.getString();
            for (String option : options) {
                if (!text.contains(option)) continue;
                int[] colour = {-1};
                line.visit((style, part) -> {
                    if (colour[0] < 0 && style.getColor() != null && part.contains(option.split(" ")[0])) {
                        colour[0] = style.getColor().getValue();
                    }
                    return java.util.Optional.<Boolean>empty();
                }, net.minecraft.network.chat.Style.EMPTY);
                if (colour[0] < 0) {
                    line.visit((style, part) -> {
                        if (colour[0] < 0 && style.getColor() != null && !part.isBlank()) {
                            colour[0] = style.getColor().getValue();
                        }
                        return java.util.Optional.<Boolean>empty();
                    }, net.minecraft.network.chat.Style.EMPTY);
                }
                colours.put(option, colour[0]);
            }
        }
        if (colours.size() < 2) return "";
        // The selected option is drawn white and the other two grey.
        for (var entry : colours.entrySet()) {
            if (entry.getValue() == 0xFFFFFF) return entry.getKey();
        }
        // Otherwise the selection is the option whose colour no other shares.
        for (var entry : colours.entrySet()) {
            int same = 0;
            for (var other : colours.entrySet()) {
                if (other.getValue().intValue() == entry.getValue().intValue()) same++;
            }
            if (same == 1) return entry.getKey();
        }
        return "";
    }

    /**
     * Server chat during a buy. "This item was already bought" is the server's
     * verdict on a sold-but-still-displayed row, whether it arrives after the
     * listing click or after Yes on the dialog; it means no money moved.
     */
    /** A server line refusing the listing command, e.g. "That is not a valid number." */
    /** Times the opening command has been repeated for the operation in flight. */
    private int openRetries;
    /** The order house's own confirmation that a bid was taken down. */
    private String cancelOrderReceipt = "";
    private String serverRejection = "";
    /** The server's own word that a listing went up: "You listed 64 Torch for $ 20K". */
    private String listingReceipt = "";
    /** Ticks spent waiting for the server to say which way a listing went. */
    private int listingSettleTicks;
    /** How long to wait for that word before taking the empty slot as the answer. */
    private static final int LISTING_RECEIPT_TICKS = 20;

    /** One "You bought N X for $Y" line, kept so an unlisted purchase can be found later. */
    public record PurchaseReceipt(String itemName, int count, long price, long atMillis) {
    }

    private static final java.util.regex.Pattern RECEIPT = java.util.regex.Pattern.compile(
            "(?i)^you bought (\\d+) (.+?) for \\$\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kmb]?)$");
    private final java.util.ArrayDeque<PurchaseReceipt> receipts = new java.util.ArrayDeque<>();

    /** Receipts from the last fifteen minutes, newest first. */
    public synchronized List<PurchaseReceipt> recentPurchaseReceipts() {
        long cutoff = System.currentTimeMillis() - Tuning.millis("buy.receipt_window_min");
        receipts.removeIf(r -> r.atMillis() < cutoff);
        return List.copyOf(receipts);
    }

    public synchronized void consumeReceipt(PurchaseReceipt receipt) {
        receipts.remove(receipt);
    }

    private void rememberReceipt(String text) {
        java.util.regex.Matcher m = RECEIPT.matcher(text.strip());
        if (!m.matches()) return;
        try {
            int count = Integer.parseInt(m.group(1));
            double amount = Double.parseDouble(m.group(3).replace(",", ""));
            long mult = switch (m.group(4).toLowerCase(Locale.ROOT)) {
                case "k" -> 1_000L; case "m" -> 1_000_000L; case "b" -> 1_000_000_000L; default -> 1L;
            };
            receipts.addFirst(new PurchaseReceipt(m.group(2).strip(), count,
                    Math.round(amount * mult), System.currentTimeMillis()));
            while (receipts.size() > 40) receipts.removeLast();
        } catch (RuntimeException ignored) {
            // A receipt that does not parse is simply not remembered.
        }
    }

    /** When the server last said "You collected your item": a pull-back landed in the inventory. */
    private volatile long lastCollectedAt;

    public long lastCollectedAt() {
        return lastCollectedAt;
    }

    private static final Pattern COMMAND_WAIT = Pattern.compile(
            "(?i)you need to wait another ([0-9]+(?:[.][0-9]+)?) seconds? to execute a command.*");
    private volatile long lastCommandSentAt;
    private String lastCommand = "";
    private String resendCommand;
    private long resendCommandAt;
    private long commandQueuedAt;
    private long pendingCommandGap;
    private long pendingCommandHoldUntil;
    private String lastDispatchedCommand = "";
    private static final long COMMAND_QUEUE_TIMEOUT_MILLIS = 45_000L;

    // A small transport boundary lets the real scheduling path be tested
    // without opening Minecraft or sending commands to a server.
    interface CommandTransport {
        boolean menuOpen();
        void closeMenu();
        void send(String command);
        default long notBeforeMillis() { return 0; }
    }

    private CommandTransport commandTransport(Minecraft client) {
        return new CommandTransport() {
            public boolean menuOpen() { return AutomatedExecutionDriver.menuOpen(client); }
            public void closeMenu() { closeAuctionScreen(client); }
            public long notBeforeMillis() { return ServerStrain.commandNotBeforeMillis(); }
            public void send(String command) {
                CommandSendProbe.verify(command, () -> client.getConnection().sendCommand(command));
            }
        };
    }

    /** The least time between any two commands the mod sends; the server spaces them itself at a quarter second. */
    private static final long COMMAND_GAP_FLOOR_MILLIS = 600;

    /**
     * How fast container clicks are actually going out.
     *
     * <p>Commands are paced - nine tenths of a second to nearly two between
     * them, with a floor under that. Container clicks are not paced at all:
     * every one goes the instant the state machine asks for it. A click is
     * also the bigger packet of the two, because it carries the changed slot's
     * item data, and the server that closed the connection on 2026-09-07 said
     * "too many large packets", not too many commands.
     *
     * <p>Pacing them properly is not a one-line change: {@code clickSlot}
     * returning false makes its callers abort the whole operation, so a naive
     * "too soon, refuse" would turn a rate limit into a flood of failed
     * pull-backs. So measure first. This counts clicks in a rolling ten
     * seconds and says so when a burst goes past what a person could do,
     * which is the number the pacing should be built against.
     */
    private static final int CLICK_BURST_WARN = 25;
    private static final long CLICK_WINDOW_MILLIS = 10_000L;
    private static final java.util.ArrayDeque<Long> CLICK_TIMES = new java.util.ArrayDeque<>();
    private static long clickBurstLoggedAt;

    private static synchronized void noteClick() {
        long now = System.currentTimeMillis();
        CLICK_TIMES.addLast(now);
        while (!CLICK_TIMES.isEmpty() && now - CLICK_TIMES.peekFirst() > CLICK_WINDOW_MILLIS) {
            CLICK_TIMES.removeFirst();
        }
        if (CLICK_TIMES.size() < CLICK_BURST_WARN || now - clickBurstLoggedAt < 30_000L) return;
        clickBurstLoggedAt = now;
        DoughBayClient.LOGGER.warn("DoughBay: {} container clicks in the last {} s",
                CLICK_TIMES.size(), CLICK_WINDOW_MILLIS / 1000);
    }

    /** Container clicks in the last ten seconds, for the diagnostics line. */
    public static synchronized int clicksInLastTenSeconds() {
        long now = System.currentTimeMillis();
        while (!CLICK_TIMES.isEmpty() && now - CLICK_TIMES.peekFirst() > CLICK_WINDOW_MILLIS) {
            CLICK_TIMES.removeFirst();
        }
        return CLICK_TIMES.size();
    }

    /** The gap the mod leaves between two commands: never below the server's own, and drawn fresh each time. */
    private static long commandGapMillis() {
        if (Tuning.get("pace.human_steps") < 0.5) return COMMAND_GAP_FLOOR_MILLIS;
        long min = Math.round(Tuning.get("pace.command_min_sec") * 1000);
        long max = Math.max(min, Math.round(Tuning.get("pace.command_max_sec") * 1000));
        long gap = min + (long) PACE.nextInt((int) (max - min) + 1);
        // Ease off while the server is struggling so the command actually
        // lands instead of being dropped into a cooldown and lost.
        gap = Math.round(gap * ServerStrain.pacingMultiplier());
        return Math.max(COMMAND_GAP_FLOOR_MILLIS, gap);
    }

    /**
     * Every command the mod sends passes here. One that follows another too
     * closely, whoever sent the first (a listing, a page open, payroll), is
     * held and sent when the gap is up; the phase's timer starts then.
     */
    /**
     * Commands this mod must never send, whatever any setting says.
     *
     * <p>Every one of them moves the player between backends, and on this
     * server that reliably leaves the client one-way: chat still arrives,
     * nothing the client sends is ever handled again, and only a reconnect
     * clears it. No error is shown, so it reads as the server being down, or
     * the account being restricted, or the mod eating chat - it was diagnosed
     * as all three on 2026-09-07 before the pattern was clear.
     *
     * <p>It cost a session on the same day: the escape sent /home 2 at
     * 16:57:43 with a player nearby, the connection went one-way in that
     * second, and everything stopped. A teleport is not worth a dead client,
     * and evasion pausing does the job on its own.
     *
     * <p>This is a block in code rather than a setting because a setting can
     * be turned back on by someone who has forgotten why, and the failure is
     * silent.
     */
    private static final java.util.Set<String> NEVER_SENT =
            java.util.Set.of("spawn", "tp", "tpa", "warp", "hub", "lobby", "server");

    /**
     * The two that move you about the world you are already on, rather than
     * putting you somewhere other players gather or on a different server.
     *
     * <p>These were in the block above until 2026-09-08, because a teleport
     * left the connection one-way: the client kept sending and the server had
     * stopped listening, which is invisible from inside and looks exactly like
     * a quiet market. That was measured and it was real. It has since been
     * retested and /rtp comes back cleanly, so the block on these two is now a
     * setting rather than a wall - and if it starts costing connections again,
     * one toggle puts the wall back without a build.
     *
     * <p>The rest stay blocked in code. None of them was ever the one tested,
     * and a bot that lands on spawn is a bot standing in the one place every
     * player on the server walks through.
     */
    private static final java.util.Set<String> ONLY_WHEN_TELEPORTS_WORK =
            java.util.Set.of("rtp", "home");

    /** Whether teleports are refused; evasion asks before arming a warm-up it cannot win. */
    public boolean refusesTeleports() {
        return Tuning.get("safety.teleports_work") < 0.5;
    }

    private static boolean isTeleport(String command) {
        String head = command.strip().toLowerCase(Locale.ROOT);
        int cut = head.indexOf(' ');
        if (cut > 0) head = head.substring(0, cut);
        if (NEVER_SENT.contains(head)) return true;
        return ONLY_WHEN_TELEPORTS_WORK.contains(head)
                && Tuning.get("safety.teleports_work") < 0.5;
    }

    /** One of the bot's own menus - an auction page or a confirm dialog - is on screen. */
    private static boolean menuOpen(Minecraft client) {
        if (client == null) return false;
        Screen screen = client.gui.screen();
        return screen instanceof AbstractContainerScreen<?> || screen instanceof DialogScreen<?>;
    }

    private void sendCommand(Minecraft client, String command) {
        sendCommand(commandTransport(client), command, System.currentTimeMillis(), commandGapMillis());
    }

    void sendCommand(CommandTransport transport, String command, long now, long gap) {
        if (isTeleport(command)) {
            DoughBayClient.LOGGER.warn(
                    "DoughBay refused to send /{}: teleports leave this server's connection one-way", command);
            return;
        }
        lastCommand = command;
        long holdUntil = transport.notBeforeMillis();
        if (now < holdUntil) {
            deferCommand(command, now, Math.max(holdUntil, lastCommandSentAt + gap), gap);
            pendingCommandHoldUntil = holdUntil;
            lastMessage = "Holding /" + command + " until the server-response backoff ends";
            return;
        }
        // A chat command typed while one of the bot's own menus is still open is
        // exactly what the server answers with "close your menu first", and it is
        // what happens when the next step of work starts before the last one has
        // finished putting its page away. The page closing is part of the step,
        // not a thing that happens on its own afterwards: close it here and hold
        // the command, so the menu is gone before the command that follows it
        // ever leaves the client. The tick resends it, and finds the screen shut.
        if (transport.menuOpen()) {
            transport.closeMenu();
            deferCommand(command, now, now + gap, gap);
            lastMessage = "Holding /" + command + " until the open menu closes";
            return;
        }
        if (now - lastCommandSentAt < gap) {
            deferCommand(command, now, lastCommandSentAt + gap, gap);
            lastMessage = "Holding /" + command + " for the gap between commands";
            return;
        }
        dispatchCommand(transport, command, now);
    }

    private void deferCommand(String command, long now, long dueAt, long gap) {
        if (resendCommand == null) commandQueuedAt = now;
        resendCommand = command;
        resendCommandAt = dueAt;
        pendingCommandGap = gap;
        DoughBayClient.LOGGER.info("DoughBay: queued /{}; operation={}, phase={}, delay={}ms",
                command, operation, phase, Math.max(0, dueAt - now));
    }

    private void dispatchCommand(CommandTransport transport, String command, long now) {
        transport.send(command);
        long queuedMillis = resendCommand == null ? 0 : Math.max(0, now - commandQueuedAt);
        lastCommandSentAt = now;
        lastDispatchedCommand = command;
        resendCommand = null;
        commandQueuedAt = 0;
        resendCommandAt = 0;
        pendingCommandGap = 0;
        pendingCommandHoldUntil = 0;
        DoughBayClient.LOGGER.info("DoughBay: dispatched /{}; operation={}, phase={}, queued={}ms",
                command, operation, phase, queuedMillis);
    }

    enum PendingDispatch { NONE, WAITING, SENT, EXPIRED }

    PendingDispatch dispatchPendingCommand(CommandTransport transport, long now) {
        if (resendCommand == null) return PendingDispatch.NONE;
        pendingCommandHoldUntil = Math.max(pendingCommandHoldUntil, transport.notBeforeMillis());
        if (now < pendingCommandHoldUntil) return PendingDispatch.WAITING;
        // An intentional server hold is not a stuck queue/menu timeout.
        if (now - Math.max(commandQueuedAt, pendingCommandHoldUntil) >= COMMAND_QUEUE_TIMEOUT_MILLIS)
            return PendingDispatch.EXPIRED;
        if (now < resendCommandAt || now - lastCommandSentAt < pendingCommandGap) {
            return PendingDispatch.WAITING;
        }
        if (transport.menuOpen()) {
            transport.closeMenu();
            resendCommandAt = now + pendingCommandGap;
            return PendingDispatch.WAITING;
        }
        String command = resendCommand;
        dispatchCommand(transport, command, now);
        resetPhaseObservation();
        lastMessage = "Sent /" + command + "; waiting for the server";
        return PendingDispatch.SENT;
    }

    /** Ends the running operation as aborted; for a caller that sees no terminal event for too long. */
    public synchronized void abandon(String why) {
        if (!isActive()) return;
        abort(Minecraft.getInstance(), why);
    }

    /** When the mod last sent any command; payroll waits on this before its own. */
    public long lastCommandSentAt() {
        return lastCommandSentAt;
    }

    /** Something else in the mod sent a command; the next one keeps its distance. */
    public void noteCommandSent() {
        lastCommandSentAt = System.currentTimeMillis();
    }

    /** Commands from payroll and evasion, waiting for the gap after whatever went out last. */
    private final java.util.ArrayDeque<String> externalCommands = new java.util.ArrayDeque<>();

    /**
     * The one door for a command from outside the driver. Noting a send was
     * never enough: payroll noted its /pay and sent it in the same instant the
     * desk opened the order house, and the server answered "wait another 0.25
     * seconds". Now anything that would follow another command too closely is
     * queued and goes out from the tick once the gap is up.
     */
    /** When the last /rtp or /home actually left the client; 0 before any did. */
    private volatile long lastEscapeSentAt;

    public long lastEscapeSentAt() {
        return lastEscapeSentAt;
    }

    private void noteEscapeSent(String command, long now) {
        if (command.equals("rtp") || command.startsWith("home ")) lastEscapeSentAt = now;
    }

    public synchronized void sendWhenClear(Minecraft client, String command) {
        sendWhenClear(commandTransport(client), command, System.currentTimeMillis(),
                client != null && client.getConnection() != null);
    }

    void sendWhenClear(CommandTransport transport, String command, long now, boolean connected) {
        // This path does not go through sendCommand, so the teleport block has
        // to be repeated here - and this is the path that matters, because it
        // is the one evasion escapes on.
        if (isTeleport(command)) {
            DoughBayClient.LOGGER.warn(
                    "DoughBay refused to send /{}: teleports leave this server's connection one-way", command);
            return;
        }
        if (!isActive() && connected && now >= transport.notBeforeMillis()
                && externalCommands.isEmpty() && resendCommand == null
                && now - lastCommandSentAt >= commandGapMillis() && !transport.menuOpen()) {
            transport.send(command);
            lastCommandSentAt = now;
            lastDispatchedCommand = "";
            noteEscapeSent(command, now);
            return;
        }
        externalCommands.addLast(command);
    }

    private void drainExternalCommands(Minecraft client) {
        if (isActive() || client == null || client.getConnection() == null) return;
        drainExternalCommands(commandTransport(client), System.currentTimeMillis());
    }

    void drainExternalCommands(CommandTransport transport, long now) {
        if (isActive() || externalCommands.isEmpty() || resendCommand != null) return;
        if (now < transport.notBeforeMillis()) return;
        if (now - lastCommandSentAt < commandGapMillis()) return;
        // Background work never owns a menu, including a late confirmation.
        if (transport.menuOpen()) return;
        String command = externalCommands.pollFirst();
        if (isTeleport(command)) {
            DoughBayClient.LOGGER.warn(
                    "DoughBay dropped queued /{}: teleports leave this server's connection one-way", command);
            return;
        }
        transport.send(command);
        lastCommandSentAt = now;
        lastDispatchedCommand = "";
        noteEscapeSent(command, now);
        DoughBayClient.LOGGER.info("DoughBay: sent /{} after the gap between commands", command);
    }

    public synchronized void observeGameMessage(String text) {
        if (text == null) return;
        rememberReceipt(text);
        if (DELIVER_RECEIPT.matcher(text.strip()).find()) {
            // Kept whatever the driver is doing: the server's receipt can
            // arrive after the wait has already given up, and a delivery that
            // really happened must not be mistaken for a lost stack.
            lastDeliveryReceipt = text.strip();
            lastDeliveryReceiptAt = System.currentTimeMillis();
            if (operation == Operation.DELIVER) deliverReceipt = text.strip();
        }
        if (operation == Operation.PLACE_ORDER && placeConfirmed && PLACE_RECEIPT.matcher(text.strip()).find()) {
            placeReceipt = text.strip();
        }
        Matcher wait = COMMAND_WAIT.matcher(text.strip());
        if (wait.matches() && isActive() && phase != Phase.WAITING_FOR_COMPLETION
                && resendCommand == null && !lastDispatchedCommand.isEmpty()) {
            // The server spaced our command out. Send it again after the wait
            // it named, rather than sit out the eight-second open timeout.
            double seconds = Double.parseDouble(wait.group(1));
            long now = System.currentTimeMillis();
            deferCommand(lastDispatchedCommand, now, now + (long) (seconds * 1000) + 150,
                    commandGapMillis());
            lastMessage = "Server command cooldown; sending /" + lastDispatchedCommand + " again in " + seconds + " s";
        }
        if (text.strip().equalsIgnoreCase("You collected your item")) lastCollectedAt = System.currentTimeMillis();
        // The order house says so itself when a bid comes down. That line is
        // the fact; the page redrawing to match it is a consequence, and the
        // page is often a second or two behind - which was read as the cancel
        // having failed, forty times over four days, on cancels that had all
        // worked. The same mistake as judging a listing by the inventory.
        if (text.toLowerCase(Locale.ROOT).contains("cancelled the order successfully")
                || text.toLowerCase(Locale.ROOT).contains("canceled the order successfully")) {
            cancelOrderReceipt = text.strip();
        }
        // Both waiting phases, not just the first. A full auction house says so
        // after the confirm is pressed, not before it, so listening only up to
        // the confirmation missed the one refusal that happens all day - and
        // the listing was then judged by the inventory alone, which says the
        // stack left whether the server took it or handed it straight back.
        if (operation == Operation.LIST
                && (phase == Phase.WAITING_FOR_CONFIRMATION || phase == Phase.WAITING_FOR_COMPLETION)) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("not a valid number") || lower.contains("cannot sell")
                    || lower.contains("can't sell") || lower.contains("you must wait")
                    || lower.contains("too many listed items")) {
                serverRejection = text.strip();
            } else if (lower.startsWith("you listed ")) {
                // The other half of the pair, and the one that was missing.
                // Without it the only evidence was the stack going, which both
                // outcomes produce.
                listingReceipt = text.strip();
            }
            return;
        }
        if (operation != Operation.BUY) return;
        if (phase != Phase.WAITING_FOR_CONFIRMATION && phase != Phase.WAITING_FOR_COMPLETION) return;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("already bought")) {
            ghostSignalled = true;
        } else if (lower.startsWith("you bought ") && lower.contains(" for ")) {
            purchaseReceipt = text.strip();
        }
    }

    /**
     * Lists whatever plain stack is in the selected hotbar slot at
     * {@code price}. A test of the listing path that needs no tracked
     * position: the stack itself is the target.
     */
    public synchronized ExecutionResult sellHeldStack(long price) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        if (price <= 0) {
            return ExecutionResult.refused("The listing price must be positive");
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }
        ItemStack selected = client.player.getInventory().getSelectedItem();
        if (selected.isEmpty()) {
            return ExecutionResult.refused("Hold the stack to sell in the selected hotbar slot");
        }
        if (!listableWithParts(selected)) {
            return ExecutionResult.refused("Only a stack whose value is in its item, enchantments, potion, trim or contents can be listed automatically");
        }
        String itemId = itemId(selected);

        opportunity = null;
        operation = Operation.LIST;
        targetListingKey = "held:" + itemId + ":" + price;
        targetItemKey = itemId;
        targetItemId = itemId;
        targetCount = selected.getCount();
        targetPrice = price;
        targetCeiling = 0;
        targetPriceSpan = 1;
        targetSeller = "";
        inventoryCountBefore = countInventory(client, itemId);
        clickedContainerId = -1;
        clickedScreenTitle = "";
        clickedScreenSignature = "";
        confirmationContainerId = -1;
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        lastCapturePath = null;

        String command = "ah sell " + price;
        try {
            sendCommand(client, command);
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED,
                    "Could not send the auction listing command");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /" + command + ": " + e.getMessage();
            DoughBayClient.LOGGER.error("DoughBay could not start held-stack listing", e);
            return ExecutionResult.aborted(lastMessage);
        }
        enterPhase(Phase.WAITING_FOR_CONFIRMATION,
                String.format(Locale.ROOT,
                        "Listing %s x%d at %,d; waiting for the sell confirmation",
                        shortName(targetItemId), targetCount, targetPrice));
        notifyPlayer(client, lastMessage);
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    @Override
    public synchronized ExecutionResult list(Position requested, long price) {
        if (!authorizedExecutionEnabled) {
            return ExecutionResult.refused(EXECUTION_DISABLED);
        }
        if (requested == null) {
            return ExecutionResult.refused("Cannot list a missing position");
        }
        if (isActive()) {
            return ExecutionResult.refused("Another DoughBay execution is already in progress");
        }
        if (requested.quantity() <= 0 || price <= 0) {
            return ExecutionResult.refused("Position quantity and listing price must be positive");
        }

        Minecraft client = Minecraft.getInstance();
        String unavailable = unavailableReason(client);
        if (unavailable != null) {
            return ExecutionResult.refused(unavailable);
        }

        String itemId = baseItemId(requested.itemKey());
        ItemStack selected = client.player.getInventory().getSelectedItem();
        if (selected.isEmpty()) {
            return ExecutionResult.refused("Hold the position's exact stack in the selected hotbar slot");
        }
        if (!itemId(selected).equals(itemId)) {
            return ExecutionResult.refused(String.format(Locale.ROOT,
                    "Selected item is %s, expected %s",
                    shortName(itemId(selected)), shortName(itemId)));
        }
        if (selected.getCount() != requested.quantity()) {
            return ExecutionResult.refused(String.format(Locale.ROOT,
                    "Selected stack is x%d, expected exactly x%d; split it before listing",
                    selected.getCount(), requested.quantity()));
        }
        // A plain commodity's key is its item id; a box's key carries the
        // descriptor hash of its contents, and the selected stack must match it.
        boolean hashedKey = requested.itemKey().indexOf('#') >= 0;
        // A gear market's key is the plain item id even though every copy of
        // it carries enchantments, because the market has standardised on one
        // kit and prices them alike. Undamaged is not negotiable: a worn one
        // is a different product and the plain price is not its price.
        boolean gear = !hashedKey && Tuning.gearMarket(itemId)
                && ItemDescriptor.of(selected).hasParts() && !worn(selected);
        boolean identityMatches = hashedKey
                ? requested.itemKey().equals(itemId + "#" + ItemDescriptor.of(selected).hash())
                : requested.itemKey().equals(itemId)
                        && (gear || !ItemDescriptor.of(selected).hasParts());
        if (!identityMatches || !(hashedKey || gear ? listableWithParts(selected) : plainForListing(selected))) {
            return ExecutionResult.refused(hashedKey
                    ? "Selected box does not match the tracked contents"
                    : "Automatic listing accepts only a metadata-plain stack whose item key equals its item id");
        }

        opportunity = null;
        operation = Operation.LIST;
        targetListingKey = "position:" + requested.positionId();
        targetItemKey = requested.itemKey();
        targetItemId = itemId;
        targetCount = requested.quantity();
        targetPrice = price;
        targetSeller = "";
        inventoryCountBefore = countInventory(client, targetItemId);
        clickedContainerId = -1;
        clickedScreenTitle = "";
        clickedScreenSignature = "";
        confirmationContainerId = -1;

        String command = "ah sell " + price;
        try {
            sendCommand(client, command);
        } catch (RuntimeException e) {
            publishTerminal(TerminalOutcome.ABORTED,
                    "Could not send the auction listing command");
            clearTarget();
            phase = Phase.ABORTED;
            lastMessage = "Could not send /" + command + ": " + e.getMessage();
            DoughBayClient.LOGGER.error("DoughBay could not start automated listing", e);
            return ExecutionResult.aborted(lastMessage);
        }

        enterPhase(Phase.WAITING_FOR_CONFIRMATION,
                String.format(Locale.ROOT,
                        "Listing %s x%d at %,d; waiting for server confirmation",
                        shortName(targetItemId), targetCount, targetPrice));
        notifyPlayer(client, lastMessage);
        return ExecutionResult.awaitingPlayer(lastMessage);
    }

    @Override
    public synchronized ExecutionStatus inspect() {
        if (operation == Operation.DRY_RUN) {
            // Do not derive the emergency-stop latch from a display report.
            // As long as the state machine owns a DRY_RUN operation it is
            // armed, even if a future reporting bug produces stale metadata.
            return new ExecutionStatus(ExecutionStatus.State.VERIFYING,
                    lastMessage, true);
        }
        if (!authorizedExecutionEnabled) {
            return ExecutionStatus.disabled();
        }
        return switch (phase) {
            case IDLE -> new ExecutionStatus(ExecutionStatus.State.IDLE, lastMessage, false);
            case WAITING_FOR_LISTING -> operation == Operation.DRY_RUN
                    ? new ExecutionStatus(ExecutionStatus.State.VERIFYING, lastMessage, false)
                    : new ExecutionStatus(ExecutionStatus.State.PREPARED, lastMessage, true);
            case WAITING_FOR_CONFIRMATION, WAITING_FOR_COMPLETION -> new ExecutionStatus(
                    ExecutionStatus.State.VERIFYING, lastMessage, true);
            case ABORTED -> new ExecutionStatus(
                    ExecutionStatus.State.ABORTED, lastMessage, false);
        };
    }

    @Override
    public synchronized void emergencyStop() {
        boolean wasActive = isActive();
        if (operation == Operation.DRY_RUN) {
            String stopped = "DRY RUN FAILED: emergency-stopped; no GUI input was sent";
            lastPreflightReport = reportFor(opportunity, PreflightOutcome.FAILED,
                    verifyAgainstOpenScreen(), lastCapturePath, stopped);
        }
        if (wasActive) {
            publishTerminal(TerminalOutcome.EMERGENCY_STOPPED,
                    "Emergency stop requested before the next driver tick");
        }
        clearTarget();
        phase = Phase.IDLE;
        resetPhaseObservation();
        lastMessage = wasActive
                ? "Emergency stop: no further actions will be sent; "
                + "verify inventory and balance if confirmation was already submitted"
                : "Stopped — no execution was active";
        notifyPlayer(Minecraft.getInstance(), lastMessage);
    }

    @Override
    public String modeName() {
        if (!authorizedExecutionEnabled) {
            return preflightEnabled ? "Read-only preflight" : "Disabled / observe";
        }
        return authorizedServers.isEmpty()
                ? "Authorized gate (no servers)"
                : "Automated (server allowlist)";
    }

    /**
     * Advances the live operation. Registered once from DoughBayClient's end-of-
     * client-tick callback so it keeps running after the analysis screen closes
     * and the server opens its own auction GUI.
     */
    public synchronized void tick(Minecraft client) {
        drainExternalCommands(client);
        if (!isActive()) {
            return;
        }

        if (operation == Operation.DRY_RUN && !preflightEnabled) {
            abort(client, PREFLIGHT_DISABLED);
            return;
        }
        if (operation != Operation.DRY_RUN && !authorizedExecutionEnabled) {
            abort(client, EXECUTION_DISABLED);
            return;
        }

        String unavailable = operation == Operation.DRY_RUN
                ? preflightUnavailableReason(client)
                : unavailableReason(client);
        if (unavailable != null) {
            abort(client, unavailable);
            return;
        }

        // Queue time is not server response time. Do not inspect/timeout a GUI
        // until its command has actually left the client. Keep a separate bound
        // on a menu or command queue that never clears.
        try {
            PendingDispatch dispatch = dispatchPendingCommand(commandTransport(client), System.currentTimeMillis());
            if (dispatch == PendingDispatch.EXPIRED) {
                abort(client, "Command /" + resendCommand + " was not dispatched within 45 seconds; command queue/menu blocked");
                return;
            }
            if (dispatch == PendingDispatch.WAITING || dispatch == PendingDispatch.SENT) return;
        } catch (RuntimeException e) {
            abort(client, "Could not dispatch queued command /" + resendCommand + ": " + e.getMessage());
            return;
        }

        ticksInPhase++;

        // Wall-clock backstop: every timeout in this driver is counted in render
        // ticks, but the loop runs on the render thread, which the OS throttles
        // toward ~1fps when the window is unfocused or covered. A tick-based 8s
        // timeout then stretches into minutes, so a phase whose auction container
        // never renders freezes the session. Aborting here recovers in seconds
        // via the same safe path the tick timeouts use (a terminal ABORTED event
        // the controller turns into a recount + rescan).
        if (phase != Phase.IDLE && phase != Phase.ABORTED) {
            boolean read = operation == Operation.ORDERS || operation == Operation.OWN_ORDERS;
            long budget = read ? READ_WALLCLOCK_TIMEOUT_MILLIS : PHASE_WALLCLOCK_TIMEOUT_MILLIS;
            if (System.currentTimeMillis() - phaseStartedAtMillis >= budget) {
                abort(client, "phase " + phase + " stalled past " + budget / 1000
                        + "s of real time (render thread throttled); recounting");
                return;
            }
        }

        // A buy cannot legitimately enter inventory before the listing has
        // been clicked. A listing may complete directly, but an open container
        // can temporarily escrow the selected stack before its confirm control
        // is pressed, so that delta is not terminal until confirmation.
        if (inventoryChangeCanComplete(client) && inventoryChangeObserved(client)) {
            // A listing is not judged by the inventory. The stack leaves the
            // inventory whether the auction house took it or handed it
            // straight back, so both outcomes look identical here - and
            // calling the good one every time is how the ledger came to hold
            // listings the server had never made. That is the drift the panel
            // could never be reconciled against.
            //
            // The server says which it was, a fraction of a second later:
            // "You listed 64 Torch for $ 20K", or "You have too many listed
            // items". So wait for the word rather than guess ahead of it.
            if (operation == Operation.LIST) {
                if (!serverRejection.isEmpty()) {
                    abort(client, "The server refused the listing: \"" + serverRejection + "\"");
                    return;
                }
                if (listingReceipt.isEmpty() && ++listingSettleTicks < LISTING_RECEIPT_TICKS) {
                    // Still gone and still nothing said. If the stack comes
                    // back in the meantime this branch stops being reached at
                    // all, and the operation times out as the failure it is.
                    return;
                }
            }
            complete(client, completionMessage());
            return;
        }

        switch (phase) {
            case WAITING_FOR_LISTING -> tickWaitingForListing(client);
            case WAITING_FOR_CONFIRMATION -> tickWaitingForConfirmation(client);
            case WAITING_FOR_COMPLETION -> tickWaitingForCompletion(client);
            case IDLE, ABORTED -> {
                // isActive() already excludes these states.
            }
        }
    }

    private void tickWaitingForListing(Minecraft client) {
        Screen screen = client.gui.screen();
        if (watching && watchWaiting && !(screen instanceof AbstractContainerScreen<?>)) {
            if (ticksInPhase >= nextWatchDelayTicks) {
                watchWaiting = false;
                watchAgain(client);
            }
            return;
        }
        if (operation == Operation.DELIVER && screen instanceof DialogScreen<?> dialogScreen) {
            tickDeliverDialog(client, dialogScreen);
            return;
        }
        if (operation == Operation.PLACE_ORDER && screen instanceof DialogScreen<?> dialogScreen) {
            tickPlaceDialog(client, dialogScreen);
            return;
        }
        if (operation == Operation.PLACE_ORDER && placeConfirmed && !placeReceipt.isEmpty()) {
            // On the receipt, not on the click: the alert says what the order
            // house accepted, which is the only version worth showing.
            DoughBayClient.alertOrderPlaced(shortName(targetItemId), placeCount, placeUnitPrice);
            complete(client, String.format(Locale.ROOT, "Bid placed: %s x%d at %s each (%s)",
                    shortName(targetItemId), placeCount, money(placeUnitPrice), placeReceipt));
            return;
        }
        if (operation == Operation.DELIVER && deliverStage == 6 && !deliverReceipt.isEmpty()) {
            completeDelivery(client);
            return;
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            // Ask once more before giving up on it.
            //
            // Three hundred and eighty-six of these over four days, in a
            // hundred and seventy-one runs of two or three - so the auction
            // was not down, it just did not answer, and the next attempt a
            // moment later usually worked. There is no "you must wait" in any
            // of them, so the server was not holding us off; the command
            // simply went nowhere.
            //
            // Waiting the full eight seconds and abandoning the operation
            // spends a whole cycle on silence. Sending the same command again
            // halfway through costs one command and saves the cycle, and the
            // server opening two pages is harmless - the second replaces the
            // first.
            if (ticksInPhase == OPEN_TIMEOUT_TICKS / 2 && !lastCommand.isEmpty() && openRetries < 1) {
                openRetries++;
                DoughBayClient.LOGGER.info(
                        "DoughBay: nothing opened in {} s after /{}; asking once more",
                        OPEN_TIMEOUT_TICKS / 40, lastCommand);
                sendCommand(client, lastCommand);
                return;
            }
            if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                abort(client, "Auction search did not open a container within 8 seconds");
            }
            return;
        }

        sawContainerInPhase = true;
        if (!containerStable(client, container)) {
            return;
        }
        if (operation == Operation.ORDERS) {
            tickOrdersPage(client, container);
            return;
        }
        if (operation == Operation.DELIVER) {
            tickDeliverPage(client, container);
            return;
        }
        if (operation == Operation.OWN_ORDERS || operation == Operation.PLACE_ORDER
                || operation == Operation.COLLECT_ORDER || operation == Operation.CANCEL_ORDER) {
            tickOwnOrdersPage(client, container);
            return;
        }
        if (operation == Operation.CANCEL) {
            tickCancelPage(client, container);
            return;
        }
        if (operation == Operation.AUDIT) {
            tickAuditPage(client, container);
            return;
        }
        if (operation == Operation.PROBE) {
            tickProbePage(client, container);
            return;
        }
        if (watching && targetItemId.isEmpty()) {
            if (watchOpenedAtMillis == 0) watchOpenedAtMillis = System.currentTimeMillis();
            tickWatchPage(client, container);
            return;
        }

        captureOnce(client, container, CaptureStage.LISTING);
        if (operation == Operation.DRY_RUN && lastCapturePath == null) {
            abort(client, "diagnostic capture could not be written; no GUI input was sent");
            return;
        }

        ListingChoice choice = chooseListing(client, container);
        switch (choice.resolution()) {
            case FOUND -> {
                if (operation == Operation.DRY_RUN) {
                    completeDryRun(client, choice);
                    return;
                }
                if (huntedSinceCapture) {
                    listingCaptureTaken = false;
                    captureOnce(client, container, CaptureStage.LISTING);
                    huntedSinceCapture = false;
                }
                clickedContainerId = container.getMenu().containerId;
                clickedScreenTitle = container.getTitle().getString();
                clickedScreenSignature = containerSignature(client, container);
                if (!clickSlot(client, container, choice.menuSlot())) {
                    if (clickDeferred) return;   // paced, not impossible: try again next tick
                    abort(client, "The matched listing could not be clicked");
                    return;
                }
                String verification = choice.verification().isBlank()
                        ? "item and stack size"
                        : choice.verification();
                enterPhase(Phase.WAITING_FOR_CONFIRMATION,
                        "Matched listing by " + verification + "; waiting for confirmation GUI");
            }
            case PRICE_MISMATCH -> {
                // A page whose cheapest row is well above the ceiling is not
                // worth a hundred seconds of re-reading: give up now and let
                // the session move to the next market. Only a near miss, where
                // one new listing could land under the ceiling, is hunted.
                boolean farOff = lastCheapestVisible > 0 && targetCeiling > 0
                        && lastCheapestVisible > targetCeiling * 1.1;
                if (targetCeiling > 0 && huntLooksRemaining > 0 && !farOff) {
                    huntAgain(client, choice.detail());
                } else {
                    abort(client, choice.detail());
                }
            }
            case AMBIGUOUS -> {
                if (ticksInPhase >= STABLE_TICKS_REQUIRED + 10) {
                    abort(client, choice.detail());
                }
            }
            case NOT_FOUND -> {
                if (targetCeiling > 0 && huntLooksRemaining > 0
                        && ticksInPhase >= HUNT_INTERVAL_TICKS) {
                    huntAgain(client, choice.detail());
                } else if (ticksInPhase >= OPEN_TIMEOUT_TICKS) {
                    abort(client, choice.detail());
                }
            }
        }
    }

    private void tickWaitingForConfirmation(Minecraft client) {
        if (!serverRejection.isEmpty()) {
            abort(client, "The server refused the listing command: \"" + serverRejection + "\"");
            return;
        }
        Screen screen = client.gui.screen();
        if (screen instanceof DialogScreen<?> dialogScreen) {
            tickConfirmationDialog(client, dialogScreen);
            return;
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            if (sawContainerInPhase) {
                screenGoneTicks++;
                if (screenGoneTicks >= 12) {
                    if (targetCeiling > 0 && ghostRetries < MAX_GHOST_RETRIES) {
                        retryAfterGhost(client);
                        return;
                    }
                    abort(client, "The auction GUI closed before a confirmation could be verified");
                    return;
                }
            }
            if (ticksInPhase >= CONFIRM_TIMEOUT_TICKS) {
                String screenName = screen == null ? "none" : screen.getClass().getSimpleName();
                abort(client, "No confirmation GUI appeared within 7 seconds; last dispatched /"
                        + lastDispatchedCommand + ", "
                        + Math.max(0, System.currentTimeMillis() - lastCommandSentAt)
                        + "ms ago; current screen=" + screenName);
            }
            return;
        }

        sawContainerInPhase = true;
        screenGoneTicks = 0;
        if (ghostSignalled && targetCeiling > 0 && ghostRetries < MAX_GHOST_RETRIES) {
            // The server answered the click with "already bought" and simply
            // refreshed the listing page; no dialog is coming.
            retryAfterGhost(client);
            return;
        }
        if (!containerStable(client, container)) {
            return;
        }

        String currentSignature = containerSignature(client, container);
        boolean transitioned = operation == Operation.LIST
                || container.getMenu().containerId != clickedContainerId
                || !container.getTitle().getString().equals(clickedScreenTitle)
                || !currentSignature.equals(clickedScreenSignature);

        ConfirmChoice choice = chooseConfirmation(client, container);
        boolean explicitConfirmation = choice.explicitConfirmation();

        // On a buy, do not treat the original listing screen's "click to buy"
        // lore as the second confirmation click. The container must have
        // transitioned, unless the candidate explicitly says confirm/accept.
        if (!transitioned && !explicitConfirmation) {
            if (ticksInPhase >= CONFIRM_TIMEOUT_TICKS) {
                abort(client, "Listing click did not transition to a confirmation GUI");
            }
            return;
        }

        if (transitioned || choice.resolution() != Resolution.NOT_FOUND) {
            captureOnce(client, container, CaptureStage.CONFIRMATION);
        }

        switch (choice.resolution()) {
            case FOUND -> {
                confirmationContainerId = container.getMenu().containerId;
                if (!clickSlot(client, container, choice.menuSlot())) {
                    if (clickDeferred) return;   // paced, not impossible: try again next tick
                    abort(client, "The confirmation control could not be clicked");
                    return;
                }
                enterPhase(Phase.WAITING_FOR_COMPLETION,
                        "Confirmation submitted; verifying the inventory change");
            }
            case AMBIGUOUS -> abort(client, choice.detail());
            case PRICE_MISMATCH -> abort(client, choice.detail());
            case NOT_FOUND -> {
                boolean listingPageAgain = targetCeiling > 0
                        && container.getTitle().getString().equals(clickedScreenTitle)
                        && ticksInPhase >= STABLE_TICKS_REQUIRED + 10;
                if (listingPageAgain && ghostRetries < MAX_GHOST_RETRIES) {
                    // A dead row: the page refreshed without the row and no
                    // dialog followed. Same outcome as "already bought".
                    retryAfterGhost(client);
                } else if (ticksInPhase >= CONFIRM_TIMEOUT_TICKS) {
                    abort(client, choice.detail());
                }
            }
        }
    }

    /**
     * Nothing on the page qualifies right now. Wait a beat, search again, and
     * look once more; the row we want is the one that appears next.
     */
    private void huntAgain(Minecraft client, String why) {
        if (ticksInPhase < HUNT_INTERVAL_TICKS) return;
        huntLooksRemaining--;
        // Not every look is worth a screenshot; the page that gets clicked is.
        huntedSinceCapture = true;
        String command = "ah search " + searchTermForItemId(targetItemId);
        try {
            sendCommand(client, command);
        } catch (RuntimeException e) {
            abort(client, "Could not re-send the auction search while hunting");
            return;
        }
        enterPhase(Phase.WAITING_FOR_LISTING,
                String.format(Locale.ROOT, "Hunting %s under %,d: %s; %d look(s) left",
                        shortName(targetItemId), targetCeiling, why, huntLooksRemaining));
    }

    /**
     * The clicked row was a ghost: the screen closed instead of confirming.
     * Remember the row, search again, and take the next cheapest.
     */
    private void retryAfterGhost(Minecraft client) {
        if (watching) {
            watchGhosts.put(targetItemId + "|" + targetCount + "|" + targetPrice, System.currentTimeMillis());
            ghostRetries++;
            ghostSignalled = false;
            String vanished = shortName(targetItemId);
            targetItemId = "";
            targetItemKey = "";
            targetListingKey = "watch:all";
            targetCount = 0;
            targetPrice = 0;
            targetPriceSpan = 1;
            targetRequiredCount = 0;
            clickedContainerId = -1;
            clickedScreenTitle = "";
            clickedScreenSignature = "";
            confirmationContainerId = -1;
            listingCaptureTaken = false;
            confirmationCaptureTaken = false;
            filterClicks = 0;
            try {
                sendCommand(client, "ah");
            } catch (RuntimeException e) {
                abort(client, "Could not re-open the auction house after a vanished listing");
                return;
            }
            enterPhase(Phase.WAITING_FOR_LISTING,
                    String.format(Locale.ROOT,
                            "Recent %s vanished on click (already bought); back to watching (ghost %d of %d)",
                            vanished, ghostRetries, MAX_GHOST_RETRIES));
            notifyPlayer(client, lastMessage);
            return;
        }
        ghostRows.add(new long[] {targetCount, targetPrice});
        ghostRetries++;
        ghostSignalled = false;
        targetCount = 0;
        targetPrice = 0;
        targetPriceSpan = 1;
        clickedContainerId = -1;
        clickedScreenTitle = "";
        clickedScreenSignature = "";
        confirmationContainerId = -1;
        listingCaptureTaken = false;
        confirmationCaptureTaken = false;
        String command = "ah search " + searchTermForItemId(targetItemId);
        try {
            sendCommand(client, command);
        } catch (RuntimeException e) {
            abort(client, "Could not re-send the auction search after a vanished listing");
            return;
        }
        enterPhase(Phase.WAITING_FOR_LISTING,
                String.format(Locale.ROOT,
                        "Listing vanished on click (already bought); retry %d of %d for the next cheapest %s",
                        ghostRetries, MAX_GHOST_RETRIES, shortName(targetItemId)));
        notifyPlayer(client, lastMessage);
    }

    /**
     * DonutSMP confirms a purchase with a server dialog ("Are you sure you
     * want to buy this?", the item, its exact price, and No/Yes), not with a
     * container. Read the dialog data, verify item, stack, and exact price
     * against the target, and press Yes.
     */
    private void tickConfirmationDialog(Minecraft client, DialogScreen<?> dialogScreen) {
        sawContainerInPhase = true;
        screenGoneTicks = 0;
        Dialog dialog = ((DialogScreenAccessor) dialogScreen).doughbay$dialog();
        if (dialog == null || dialog.common() == null) {
            abort(client, "A dialog opened but carried no readable data; refusing to confirm blindly");
            return;
        }
        String title = dialog.common().title().getString().strip();
        List<String> lines = new ArrayList<>();
        ItemStack shown = ItemStack.EMPTY;
        for (DialogBody body : dialog.common().body()) {
            if (body instanceof ItemBody item) {
                try {
                    shown = item.item().create();
                } catch (RuntimeException ignored) {
                    shown = ItemStack.EMPTY;
                }
                item.description().ifPresent(d -> lines.add(d.contents().getString()));
            } else if (body instanceof PlainMessage message) {
                lines.add(message.contents().getString());
            }
        }
        captureDialogOnce(client, dialogScreen, dialog, title, lines, shown);

        String lower = title.toLowerCase(Locale.ROOT);
        boolean buyTitle = lower.contains("buy") || lower.contains("purchase");
        boolean sellTitle = lower.contains("sell") || lower.contains("list");
        if (operation == Operation.BUY ? !buyTitle : operation == Operation.LIST && !sellTitle) {
            abort(client, "The dialog is not a " + (operation == Operation.BUY ? "purchase" : "listing")
                    + " confirmation: \"" + title + "\"");
            return;
        }
        if (!shown.isEmpty() || operation == Operation.BUY) {
            if (shown.isEmpty() || !itemId(shown).equals(targetItemId)
                    || shown.getCount() != targetCount) {
                abort(client, String.format(Locale.ROOT,
                        "Confirmation dialog shows %s x%d, expected %s x%d; refusing",
                        shown.isEmpty() ? "nothing" : shortName(itemId(shown)), shown.getCount(),
                        shortName(targetItemId), targetCount));
                return;
            }
        }

        PriceEvidence price = authoritativePriceEvidence(lines);
        if (operation != Operation.CANCEL && (!price.sawAuthoritativeField() || price.unclear())) {
            abort(client, "Confirmation dialog shows no single readable price; refusing to confirm blindly");
            return;
        }
        // The dialog abbreviates too: 1,000 shows as "$ 1K" and 7,499 as
        // "$ 7.4K", truncated to one decimal with a trailing zero dropped.
        // So the exact figure is unknowable on this server; the dialog gives
        // a band. A whole-K figure therefore spans one decimal step (1K is
        // 1,000..1,099), not a whole thousand.
        long span = operation == Operation.CANCEL ? 1 : displayedBand(price.tolerance());
        long asked = operation == Operation.CANCEL ? targetPrice : price.displayed();
        long upper = asked + span - 1;
        if (operation == Operation.CANCEL) {
            // A pull-back names no price worth checking; the row was matched
            // on item, stack, and price before the click.
        } else if (targetCeiling > 0) {
            if (upper > targetCeiling || asked < targetPrice
                    || asked - targetPrice >= targetPriceSpan) {
                abort(client, "Confirmation dialog asks " + money(asked)
                        + (span > 1 ? " (up to " + money(upper) + ")" : "")
                        + " for a row displayed as " + money(targetPrice)
                        + " under a " + money(targetCeiling) + " ceiling; refusing");
                return;
            }
            targetPrice = asked;
        } else if (!price.isExact(targetPrice)) {
            abort(client, "Confirmation dialog asks " + money(asked) + " instead of "
                    + money(targetPrice) + "; refusing the changed price");
            return;
        }

        String yesLabel = dialog instanceof ConfirmationDialog confirmation
                ? confirmation.yesButton().button().label().getString().strip()
                : "Yes";
        Button yes = null;
        int positive = 0;
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialogScreen.children(), buttons);
        for (Button button : buttons) {
            if (!button.visible || !button.isActive()) continue;
            String label = button.getMessage().getString().strip();
            if (label.equalsIgnoreCase(yesLabel) || label.equalsIgnoreCase("yes")
                    || label.equalsIgnoreCase("confirm")) {
                yes = button;
                positive++;
            }
        }
        if (yes == null) {
            if (ticksInPhase >= CONFIRM_TIMEOUT_TICKS) {
                abort(client, "Confirmation dialog has no \"" + yesLabel + "\" button to press");
            }
            return;
        }
        if (positive > 1) {
            abort(client, "Confirmation dialog has several positive buttons; refusing to guess");
            return;
        }
        if (operation == Operation.DRY_RUN) {
            abort(client, "A dry run reached a confirmation dialog; no button was pressed");
            return;
        }
        yes.onPress(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
        confirmationContainerId = DIALOG_CONFIRMATION;
        enterPhase(Phase.WAITING_FOR_COMPLETION,
                String.format(Locale.ROOT, "Pressed %s for %s x%d at %,d; verifying the inventory change",
                        yesLabel, shortName(targetItemId), targetCount, targetPrice));
        notifyPlayer(client, lastMessage);
    }

    /** Buttons anywhere in a screen's widget tree; dialog controls sit inside nested containers. */
    private static void collectButtons(List<? extends GuiEventListener> children, List<Button> out) {
        for (GuiEventListener child : children) {
            if (child instanceof Button button) {
                out.add(button);
            } else if (child instanceof ContainerEventHandler container) {
                collectButtons(container.children(), out);
            }
        }
    }

    private void captureDialogOnce(Minecraft client, DialogScreen<?> dialogScreen, Dialog dialog,
                                   String title, List<String> lines, ItemStack shown) {
        if (operation != Operation.DRY_RUN) return;
        if (confirmationCaptureTaken) return;
        confirmationCaptureTaken = true;
        try {
            Path directory = client.gameDirectory.toPath().resolve("doughbay-captures");
            Files.createDirectories(directory);
            String timestamp = LocalDateTime.now().format(CAPTURE_TIME);
            Path dump = directory.resolve(CaptureStage.CONFIRMATION.fileStem + "-" + timestamp + ".txt");
            List<String> out = new ArrayList<>();
            out.add("GoNuts auction dialog capture");
            out.add("stage=CONFIRMATION_DIALOG");
            out.add("dialogType=" + dialog.getClass().getSimpleName());
            out.add("title=" + title);
            out.add("item=" + (shown.isEmpty() ? "<none>" : itemId(shown) + " x" + shown.getCount()));
            for (String line : lines) out.add("  body=" + line);
            List<Button> buttons = new ArrayList<>();
            collectButtons(dialogScreen.children(), buttons);
            for (Button button : buttons) {
                out.add("  button=" + button.getMessage().getString()
                        + " active=" + button.isActive() + " visible=" + button.visible);
            }
            out.add("targetItem=" + targetItemId + " x" + targetCount + " price=" + targetPrice
                    + " ceiling=" + targetCeiling);
            Files.write(dump, out, StandardCharsets.UTF_8);
            lastCapturePath = dump;
            DoughBayClient.LOGGER.info("Captured confirmation dialog. Dump: {}", dump);
        } catch (IOException | RuntimeException e) {
            DoughBayClient.LOGGER.warn("Could not capture DoughBay confirmation dialog: {}", e.toString());
        }
    }

    private void tickWaitingForCompletion(Minecraft client) {
        if (operation == Operation.CANCEL) {
            tickCancelCompletion(client);
            return;
        }
        Screen screen = client.gui.screen();
        boolean confirmationStillOpen = (screen instanceof AbstractContainerScreen<?> container
                && container.getMenu().containerId == confirmationContainerId)
                || (confirmationContainerId == DIALOG_CONFIRMATION
                && screen instanceof DialogScreen<?>);

        if (confirmationStillOpen) {
            screenGoneTicks = 0;
        } else {
            screenGoneTicks++;
        }

        if (!purchaseReceipt.isEmpty()) {
            // The server's receipt is the strongest completion evidence there
            // is, and the only place it states the amount it actually took.
            complete(client, "Buy verified by server receipt: " + purchaseReceipt);
            return;
        }
        if (ghostSignalled) {
            if (targetCeiling > 0 && ghostRetries < MAX_GHOST_RETRIES) {
                retryAfterGhost(client);
            } else {
                abort(client, "The server reported the listing was already bought; nothing was charged");
            }
            return;
        }
        if (screenGoneTicks >= CLOSED_WITHOUT_RESULT_TICKS) {
            abort(client, "The server closed confirmation, but the expected inventory change was not observed");
            return;
        }
        if (ticksInPhase >= COMPLETION_TIMEOUT_TICKS) {
            abort(client, "Confirmation was sent, but no matching inventory update arrived within 7 seconds");
        }
    }

    /** Shift-click: moves the slot's stack straight into the inventory instead of lifting it onto the cursor. */
    private boolean quickMoveSlot(Minecraft client, AbstractContainerScreen<?> container, int menuSlot) {
        if (operation == Operation.DRY_RUN || client.gameMode == null || client.player == null) return false;
        if (menuSlot < 0 || menuSlot >= container.getMenu().slots.size()) return false;
        try {
            client.gameMode.handleContainerInput(container.getMenu().containerId, menuSlot, 0,
                    ContainerInput.QUICK_MOVE, client.player);
            return true;
        } catch (RuntimeException e) {
            DoughBayClient.LOGGER.error("DoughBay container shift-click failed", e);
            return false;
        }
    }

    /** The client tick the last container click went out on; one per tick is the ceiling. */
    private int lastClickTick = -1;
    /**
     * Whether the last refused click was refused for now rather than for good.
     *
     * <p>The pacing gate says no to a click that is too soon after the last
     * one, and every caller reads a refusal as "this control cannot be
     * clicked" and abandons the operation. That assumption was written into
     * the gate's own comment and it was wrong: seventeen places abort on it,
     * so a gap of a third of a second turned into an abandoned buy.
     *
     * <p>Not yet and cannot are different answers and now they are told apart.
     */
    private boolean clickDeferred;
    /** Ticks that must pass before the next click, rolled fresh after every one. */
    private int clickGapTicks;

    /**
     * How long to wait before the next click.
     *
     * <p>Not a flat random band. A uniform gap between two bounds is still a
     * signature - plot a thousand of them and you get a rectangle, which is
     * not a shape a hand makes. Real clicking is mostly quick with a long tail:
     * a run of fast ones, then a pause where somebody read the screen, or
     * looked away, or lost their place. So most gaps sit in the band and about
     * one in ten runs well past it.
     */
    private static int rollClickGap() {
        double min = Tuning.get("pace.click_gap_min_sec");
        double span = Math.max(0.0, Tuning.get("pace.click_gap_max_sec") - min);
        double gap = min + span * Math.random();
        if (Math.random() < 0.1) gap += span * (1.0 + 2.0 * Math.random());
        return (int) Math.max(1, Math.round(gap * 20));
    }

    /**
     * The stash desk's way in, so its clicks are paced by the same gate as
     * everything else. One choke point for every container click the mod ever
     * sends is the whole point of the gate; a second path around it would make
     * the one-a-tick ceiling a suggestion.
     */
    public boolean stashClick(Minecraft client, AbstractContainerScreen<?> container,
                              int menuSlot, int button, ContainerInput input) {
        return clickSlot(client, container, menuSlot, button, input);
    }

    private boolean clickSlot(Minecraft client, AbstractContainerScreen<?> container, int menuSlot) {
        return clickSlot(client, container, menuSlot, 0, ContainerInput.PICKUP);
    }

    private boolean clickSlot(Minecraft client, AbstractContainerScreen<?> container, int menuSlot,
                              int button, ContainerInput input) {
        // Defense in depth: even if a future state-machine change routes a
        // preflight here, read-only mode can never emit container input.
        if (operation == Operation.DRY_RUN) {
            DoughBayClient.LOGGER.error(
                    "Blocked an internal attempt to click during a DoughBay dry run");
            return false;
        }
        if (client.gameMode == null || client.player == null) {
            return false;
        }
        if (menuSlot < 0 || menuSlot >= container.getMenu().slots.size()) {
            return false;
        }
        // One click a tick, and that is a ceiling rather than a habit.
        //
        // A player's clicks arrive one per tick at the very most, because that
        // is how often the client can send them; two in the same tick is a
        // thing no hand can do and the cheapest possible check to run against
        // us. Until now nothing enforced it - there was a warning at
        // twenty-five clicks in ten seconds, which is a rate, and a rate says
        // nothing about two landing together.
        //
        // Refusing the second is safe: every caller treats a false return as
        // "the click did not happen" and comes back on the next tick, which is
        // exactly the right answer. If this ever logs, a path is trying to
        // double-click within a tick and that path is the bug.
        clickDeferred = false;
        int tick = client.player.tickCount;
        if (tick == lastClickTick) {
            clickDeferred = true;
            DoughBayClient.LOGGER.warn(
                    "DoughBay: refused a second container click in tick {} (slot {}); it will go on the next tick",
                    tick, menuSlot);
            return false;
        }
        // And a gap on top of the ceiling. One a tick is twenty a second,
        // which breaks no rule and is still nothing a hand does. Silent when
        // it defers - this is the pacing working, not a fault, and it would
        // otherwise log every tick of every gap.
        if (Tuning.get("pace.human_steps") >= 0.5
                && lastClickTick >= 0 && tick - lastClickTick < clickGapTicks) {
            clickDeferred = true;
            return false;
        }
        lastClickTick = tick;
        clickGapTicks = rollClickGap();
        try {
            noteClick();
            client.gameMode.handleContainerInput(
                    container.getMenu().containerId,
                    menuSlot,
                    button,
                    input,
                    client.player);
            return true;
        } catch (RuntimeException e) {
            DoughBayClient.LOGGER.error("DoughBay container click failed", e);
            return false;
        }
    }

    private ListingChoice chooseListing(Minecraft client, AbstractContainerScreen<?> container) {
        List<AuctionCandidate> candidates = new ArrayList<>();
        Inventory inventory = client.player.getInventory();

        for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()
                    || !itemId(stack).equals(targetItemId)
                    || (targetCeiling <= 0 && stack.getCount() != targetCount)
                    || (targetCeiling > 0 && targetRequiredCount > 0
                    && stack.getCount() != targetRequiredCount)) {
                continue;
            }
            List<String> tooltip = tooltipLines(client, stack);
            PriceEvidence price = authoritativePriceEvidence(tooltip);
            boolean sellerMatch = !targetSeller.isBlank()
                    && tooltipContainsSeller(tooltip, targetSeller);
            candidates.add(new AuctionCandidate(menuSlot, stack.getCount(), tooltip, price, sellerMatch));
        }

        if (targetCeiling > 0) {
            return chooseCheapestUnderCeiling(candidates);
        }

        if (candidates.isEmpty()) {
            return new ListingChoice(Resolution.NOT_FOUND, -1, "",
                    String.format(Locale.ROOT,
                            "%s x%d is not visible in the auction result slots",
                            shortName(targetItemId), targetCount));
        }

        List<AuctionCandidate> remaining = new ArrayList<>(candidates);
        List<AuctionCandidate> exactPrice = remaining.stream()
                .filter(c -> c.price().isExact(targetPrice))
                .toList();

        if (exactPrice.isEmpty()) {
            Set<Long> observed = new LinkedHashSet<>();
            remaining.forEach(c -> observed.addAll(c.price().values()));
            boolean sawField = remaining.stream()
                    .anyMatch(c -> c.price().sawAuthoritativeField());
            boolean unclear = remaining.stream().anyMatch(c -> c.price().unclear());
            if (!sawField) {
                return new ListingChoice(Resolution.PRICE_MISMATCH, -1, "",
                        "Matching item/stack found, but no recognized listing price/cost/total "
                                + "field was present; GUI capture saved and no click sent");
            }
            if (unclear) {
                return new ListingChoice(Resolution.PRICE_MISMATCH, -1, "",
                        "Matching item/stack found, but its authoritative total-price field "
                                + "was malformed or ambiguous; refusing to guess");
            }
            return new ListingChoice(Resolution.PRICE_MISMATCH, -1, "",
                    "Matching item/stack found, but authoritative total price was "
                            + formatObservedPrices(observed)
                            + " instead of " + money(targetPrice));
        }
        remaining = new ArrayList<>(exactPrice);

        boolean sellerVerified = false;
        if (!targetSeller.isBlank()) {
            List<AuctionCandidate> sellerMatches = remaining.stream()
                    .filter(AuctionCandidate::sellerMatch)
                    .toList();
            if (sellerMatches.isEmpty()) {
                // DonutSMP's auction GUI names no seller anywhere, so this can
                // never be satisfied there and would block every buy. Refuse
                // only when the screen does publish sellers and this one does
                // not match — that is a genuinely wrong listing. When no slot
                // shows a seller at all, fall through: the remaining rows are
                // already identical in item, stack, and price, so which of
                // them is clicked cannot change what is bought or paid.
                boolean guiPublishesSellers = candidates.stream()
                        .anyMatch(c -> tooltipMentionsAnySeller(c.tooltip()));
                if (guiPublishesSellers) {
                    return new ListingChoice(Resolution.AMBIGUOUS, -1, "",
                            "Matching item, stack, and price found, but seller "
                                    + targetSeller + " could not be verified; refusing to guess");
                }
                sellerMatches = remaining;
            }
            remaining = new ArrayList<>(sellerMatches);
            sellerVerified = true;
        }

        if (remaining.size() == 1) {
            String verification = sellerVerified
                    ? "item, stack, price, and seller"
                    : "item, stack, and price";
            return new ListingChoice(Resolution.FOUND, remaining.getFirst().menuSlot(),
                    verification, "");
        }

        // Several rows survive. On a screen that publishes sellers this is a
        // genuine ambiguity and must not be guessed at. Where it publishes
        // none, the rows differ only within an abbreviated price's rounding —
        // and the next screen states the exact figure, which is checked at
        // zero tolerance before any purchase. Clicking the closest one cannot
        // overpay: a wrong price aborts at the confirmation gate.
        boolean guiPublishesSellers = candidates.stream()
                .anyMatch(c -> tooltipMentionsAnySeller(c.tooltip()));
        if (guiPublishesSellers) {
            return new ListingChoice(Resolution.AMBIGUOUS, -1, "",
                    String.format(Locale.ROOT,
                            "%d indistinguishable %s x%d listings remain; refusing to guess",
                            remaining.size(), shortName(targetItemId), targetCount));
        }

        AuctionCandidate closest = remaining.stream()
                .min(Comparator
                        .comparingLong((AuctionCandidate c) -> priceDistance(c, targetPrice))
                        .thenComparingInt(AuctionCandidate::menuSlot))
                .orElse(remaining.getFirst());
        return new ListingChoice(Resolution.FOUND, closest.menuSlot(),
                "item, stack, and displayed price",
                String.format(Locale.ROOT,
                        "%d listings matched within the displayed price precision; "
                                + "the exact price is verified on the confirmation screen",
                        remaining.size()));
    }

    /**
     * Screen-driven choice: the cheapest visible row whose whole displayed
     * price range sits at or under the ceiling. The row's stack size and
     * displayed price become the target the confirmation is checked against.
     */
    private ListingChoice chooseCheapestUnderCeiling(List<AuctionCandidate> candidates) {
        if (candidates.isEmpty()) {
            return new ListingChoice(Resolution.NOT_FOUND, -1, "",
                    String.format(Locale.ROOT, "No %s listing is visible in the auction result slots",
                            shortName(targetItemId)));
        }
        List<AuctionCandidate> priced = candidates.stream()
                .filter(c -> c.price().sawAuthoritativeField() && !c.price().unclear())
                .filter(c -> ghostRows.stream().noneMatch(g ->
                        g[0] == c.count() && g[1] == c.price().displayed()))
                .toList();
        if (priced.isEmpty()) {
            return new ListingChoice(Resolution.PRICE_MISMATCH, -1, "",
                    ghostRows.isEmpty()
                            ? "Listings are visible but none shows a readable price; no click sent"
                            : "Every remaining visible listing matches a row that vanished on click");
        }
        AuctionCandidate cheapest = priced.stream()
                .min(Comparator.comparingLong((AuctionCandidate c) -> c.price().displayed())
                        .thenComparingInt(AuctionCandidate::menuSlot))
                .orElseThrow();
        lastCheapestVisible = cheapest.price().displayed();
        long band = displayedBand(cheapest.price().tolerance());
        if (cheapest.price().displayed() + band - 1 > targetCeiling) {
            return new ListingChoice(Resolution.PRICE_MISMATCH, -1, "",
                    String.format(Locale.ROOT,
                            "Cheapest visible %s shows %s, which can mean up to %s; "
                                    + "above the %s ceiling, no click sent",
                            shortName(targetItemId), money(cheapest.price().displayed()),
                            money(cheapest.price().displayed() + band - 1),
                            money(targetCeiling)));
        }
        targetCount = cheapest.count();
        targetPrice = cheapest.price().displayed();
        targetPriceSpan = Math.max(1, cheapest.price().tolerance());
        return new ListingChoice(Resolution.FOUND, cheapest.menuSlot(),
                "item and displayed price under the ceiling",
                String.format(Locale.ROOT, "%s x%d shown at %s; exact price verified next",
                        shortName(targetItemId), targetCount, money(targetPrice)));
    }

    /**
     * How many coins an abbreviated figure can stand for on this server.
     * Prices truncate to one decimal with a trailing zero dropped, so a
     * whole-K figure ("$ 1K") spans one decimal step, 1,000..1,099, not a
     * whole thousand; "$ 1.1K" spans 1,100..1,199; an unabbreviated figure
     * names the coin.
     */
    private static long displayedBand(long tolerance) {
        return tolerance >= 1000 ? tolerance / 10 : Math.max(1, tolerance);
    }

    /** How far a candidate's displayed price sits from the one being sought. */
    private static long priceDistance(AuctionCandidate candidate, long targetPrice) {
        return candidate.price().values().stream()
                .mapToLong(value -> Math.abs(value - targetPrice))
                .min()
                .orElse(Long.MAX_VALUE);
    }

    private ConfirmChoice chooseConfirmation(Minecraft client,
                                             AbstractContainerScreen<?> container) {
        List<ControlCandidate> candidates = new ArrayList<>();
        Inventory inventory = client.player.getInventory();
        String title = container.getTitle().getString().toLowerCase(Locale.ROOT);
        boolean confirmingTitle = containsWord(title, "confirm")
                || containsWord(title, "confirmation")
                || containsWord(title, "verify")
                || containsWord(title, "verification")
                || title.contains("are you sure");
        Set<Long> visiblePrices = new LinkedHashSet<>();
        boolean sawAuthoritativePrice = false;
        boolean malformedAuthoritativePrice = false;

        for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
            Slot slot = container.getMenu().slots.get(menuSlot);
            if (slot.container == inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            List<String> tooltip = tooltipLines(client, stack);
            PriceEvidence controlPrice = authoritativePriceEvidence(tooltip);
            visiblePrices.addAll(controlPrice.values());
            sawAuthoritativePrice |= controlPrice.sawAuthoritativeField();
            malformedAuthoritativePrice |= controlPrice.malformed();
            String tooltipText = String.join(" ", tooltip).toLowerCase(Locale.ROOT);
            if (containsAny(tooltipText, "cancel", "decline", "go back", "return", "do not")) {
                continue;
            }

            boolean explicitConfirm = containsWord(tooltipText, "confirm")
                    || containsWord(tooltipText, "confirmation")
                    || containsWord(tooltipText, "accept")
                    || containsWord(tooltipText, "yes");
            boolean actionText = operation == Operation.BUY
                    ? containsAny(tooltipText, "click to buy", "purchase", "buy item")
                    : containsAny(tooltipText, "click to sell", "list item", "create auction", "sell item");

            String id = itemId(stack).toLowerCase(Locale.ROOT);
            boolean greenControl = isGreenControl(id);
            boolean targetDisplayItem = id.equals(targetItemId)
                    && stack.getCount() == targetCount
                    && !explicitConfirm;

            boolean targetPriceShown = targetCeiling > 0
                    ? controlPrice.sawAuthoritativeField() && !controlPrice.unclear()
                    && controlPrice.displayed() <= targetCeiling
                    : controlPrice.isExact(targetPrice);

            // Never accept generic "click to buy" lore by itself: an auction
            // results refresh after the first click can otherwise look like a
            // screen transition and turn another listing into a false confirm.
            // The second click needs explicit confirmation text, or a positive
            // control on a GUI whose title itself establishes confirmation.
            boolean confirmationContext = explicitConfirm
                    || (confirmingTitle && actionText)
                    || (confirmingTitle && greenControl && targetPriceShown);
            if (!confirmationContext || targetDisplayItem) {
                continue;
            }

            int score = 0;
            if (explicitConfirm) score += 100;
            if (confirmingTitle) score += 50;
            if (actionText) score += 30;
            if (greenControl) score += 20;
            if (targetPriceShown) score += 10;

            candidates.add(new ControlCandidate(menuSlot, score, explicitConfirm,
                    shortName(id) + " — " + firstUsefulLine(tooltip)));
        }

        if (candidates.isEmpty()) {
            return new ConfirmChoice(Resolution.NOT_FOUND, -1, false,
                    "No unique positive confirmation control was found on the open GUI");
        }

        // Zero tolerance on purpose. The listing screen may shortlist from an
        // abbreviated price, but this is the last check before money moves and
        // it must be exact.
        PriceEvidence visiblePrice = new PriceEvidence(visiblePrices,
                sawAuthoritativePrice, malformedAuthoritativePrice, 0);
        if (!visiblePrice.sawAuthoritativeField()) {
            return new ConfirmChoice(Resolution.PRICE_MISMATCH, -1, false,
                    "Confirmation GUI had no recognized listing price/cost/total field; "
                            + "refusing to confirm blindly");
        }
        if (visiblePrice.unclear()) {
            return new ConfirmChoice(Resolution.PRICE_MISMATCH, -1, false,
                    "Confirmation GUI authoritative total price was malformed or ambiguous; "
                            + "refusing to confirm blindly");
        }
        if (targetCeiling > 0) {
            long exact = visiblePrice.displayed();
            if (exact > targetCeiling || exact < targetPrice
                    || exact - targetPrice >= targetPriceSpan) {
                return new ConfirmChoice(Resolution.PRICE_MISMATCH, -1, false,
                        "Confirmation GUI showed " + money(exact) + " for a row displayed as "
                                + money(targetPrice) + " under a " + money(targetCeiling)
                                + " ceiling; refusing");
            }
            // The exact figure is what will be paid and what the record shows.
            targetPrice = exact;
        } else if (!visiblePrice.isExact(targetPrice)) {
            return new ConfirmChoice(Resolution.PRICE_MISMATCH, -1, false,
                    "Confirmation GUI showed " + formatObservedPrices(visiblePrices)
                            + " instead of " + money(targetPrice) + "; refusing the changed price");
        }

        candidates.sort(Comparator.comparingInt(ControlCandidate::score).reversed());
        ControlCandidate best = candidates.getFirst();
        if (candidates.size() > 1 && candidates.get(1).score() == best.score()) {
            return new ConfirmChoice(Resolution.AMBIGUOUS, -1, false,
                    "Multiple equally likely confirmation controls were visible; refusing to guess");
        }
        return new ConfirmChoice(Resolution.FOUND, best.menuSlot(), best.explicitConfirm(),
                best.description());
    }

    private boolean containerStable(Minecraft client, AbstractContainerScreen<?> container) {
        String signature = containerSignature(client, container);
        if (signature.equals(stableSignature)) {
            stableTicks++;
        } else {
            stableSignature = signature;
            stableTicks = 1;
        }
        return stableTicks >= STABLE_TICKS_REQUIRED;
    }

    private String containerSignature(Minecraft client, AbstractContainerScreen<?> container) {
        StringBuilder signature = new StringBuilder(256)
                .append(container.getMenu().containerId)
                .append('|').append(container.getTitle().getString());
        Inventory inventory = client.player == null ? null : client.player.getInventory();
        for (int i = 0; i < container.getMenu().slots.size(); i++) {
            Slot slot = container.getMenu().slots.get(i);
            if (inventory != null && slot.container == inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                signature.append('|').append(i).append(':')
                        .append(itemId(stack)).append(':').append(stack.getCount());
            }
        }
        return signature.toString();
    }

    private boolean inventoryChangeCanComplete(Minecraft client) {
        return switch (operation) {
            // A gameplay pickup/drop/use/manual move must never masquerade as
            // a terminal auction action. The inventory delta only counts after
            // one verified positive confirmation control was clicked.
            case BUY, LIST -> phase == Phase.WAITING_FOR_COMPLETION
                    && (confirmationContainerId >= 0
                    || confirmationContainerId == DIALOG_CONFIRMATION);
            case CANCEL -> phase == Phase.WAITING_FOR_COMPLETION;
            case NONE, DRY_RUN, AUDIT, PROBE, ORDERS, DELIVER, OWN_ORDERS, PLACE_ORDER, COLLECT_ORDER, CANCEL_ORDER -> false;
        };
    }

    private boolean inventoryChangeObserved(Minecraft client) {
        int current = countInventory(client, targetItemId);
        return switch (operation) {
            case BUY, CANCEL -> current >= inventoryCountBefore + targetCount;
            case LIST -> current <= inventoryCountBefore - targetCount;
            case NONE, DRY_RUN, AUDIT, PROBE, ORDERS, DELIVER, OWN_ORDERS, PLACE_ORDER, COLLECT_ORDER, CANCEL_ORDER -> false;
        };
    }

    private String completionMessage() {
        return switch (operation) {
            case BUY -> String.format(Locale.ROOT,
                    "Buy verified: %s x%d entered inventory",
                    shortName(targetItemId), targetCount);
            case LIST -> String.format(Locale.ROOT,
                    "Listing verified: %s x%d left inventory at %,d",
                    shortName(targetItemId), targetCount, targetPrice);
            case CANCEL -> String.format(Locale.ROOT,
                    "Pulled back %s x%d from the auction house",
                    shortName(targetItemId), targetCount);
            case AUDIT -> "Auction slots checked";
            case PROBE -> "Live price checked";
            case ORDERS -> "Order house read";
            case DELIVER -> "Delivered to an order";
            case OWN_ORDERS -> "Your orders read";
            case PLACE_ORDER -> "Bid placed";
            case COLLECT_ORDER -> "Collected from your order";
            case CANCEL_ORDER -> "Bid cancelled";
            case NONE, DRY_RUN -> "Execution complete";
        };
    }

    private void completeDryRun(Minecraft client, ListingChoice choice) {
        List<Check> checks = verifyAgainstOpenScreen();
        String capture = lastCapturePath == null
                ? "capture unavailable"
                : lastCapturePath.getFileName().toString();
        lastMessage = String.format(Locale.ROOT,
                "DRY RUN PASSED: unique %s x%d at %,d by %s verified (%s); "
                        + "saved %s; no click or purchase was sent",
                shortName(targetItemId), targetCount, targetPrice, targetSeller,
                choice.verification(), capture);
        lastPreflightReport = reportFor(opportunity, PreflightOutcome.PASSED,
                checks, lastCapturePath, lastMessage);
        DoughBayClient.LOGGER.info("{}", lastMessage);
        publishTerminal(TerminalOutcome.SUCCEEDED, lastMessage);
        clearTarget();
        phase = Phase.IDLE;
        resetPhaseObservation();
        notifyPlayer(client, lastMessage);
    }

    private void complete(Minecraft client, String message) {
        dev.doughbay.fabric.ServerStrain.noteSuccess();
        lastMessage = message;
        DoughBayClient.LOGGER.info("{}", message);
        publishTerminal(TerminalOutcome.SUCCEEDED, message);
        clearTarget();
        phase = Phase.IDLE;
        resetPhaseObservation();
        notifyPlayer(client, message);
        closeAuctionScreen(client);
    }

    /**
     * The server returns to the listing page after a purchase and leaves it
     * open. Close it the way a player would, so the next action, and the
     * player, start from the world rather than from a stale auction page.
     */
    /** A confirmation dialog left open after an abort is declined, not abandoned. */
    private static void declineOpenDialog(Minecraft client) {
        if (client == null || !(client.gui.screen() instanceof DialogScreen<?> dialogScreen)) return;
        List<Button> buttons = new ArrayList<>();
        collectButtons(dialogScreen.children(), buttons);
        for (Button button : buttons) {
            String label = button.getMessage().getString().strip();
            if (button.visible && button.isActive()
                    && (label.equalsIgnoreCase("no") || label.equalsIgnoreCase("cancel"))) {
                button.onPress(new MouseButtonEvent(0, 0, new MouseButtonInfo(0, 0)));
                return;
            }
        }
        client.gui.setScreen(null);
    }

    private static void closeAuctionScreen(Minecraft client) {
        if (client == null || client.player == null) return;
        Screen open = client.gui.screen();
        if (open instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
        } else if (open instanceof DialogScreen<?>) {
            client.gui.setScreen(null);
        }
    }

    private void abort(Minecraft client, String reason) {
        // Counted across every subsystem: a run of these is how a server says
        // it has stopped listening, well before it says so in words.
        dev.doughbay.fabric.ServerStrain.noteFailure(reason);
        boolean dryRun = operation == Operation.DRY_RUN;
        // A screen-driven buy runs unattended; nobody will close the page it
        // leaves behind, and an open page blocks the session's next buy.
        boolean unattended = targetCeiling > 0 || operation == Operation.CANCEL
                || operation == Operation.AUDIT || operation == Operation.PROBE;
        String message = dryRun
                ? "DRY RUN FAILED: " + reason
                : "ABORTED: " + reason;
        if (dryRun) {
            lastPreflightReport = reportFor(opportunity, PreflightOutcome.FAILED,
                    verifyAgainstOpenScreen(), lastCapturePath, message);
        }
        lastMessage = message;
        DoughBayClient.LOGGER.warn("{}", message);
        publishTerminal(TerminalOutcome.ABORTED, message);
        clearTarget();
        phase = Phase.ABORTED;
        resetPhaseObservation();
        notifyPlayer(client, message);
        declineOpenDialog(client);
        if (unattended) closeAuctionScreen(client);
    }

    private void enterPhase(Phase next, String message) {
        phase = next;
        lastMessage = message;
        resetPhaseObservation();
    }

    private void resetPhaseObservation() {
        ticksInPhase = 0;
        phaseStartedAtMillis = System.currentTimeMillis();
        screenGoneTicks = 0;
        sawContainerInPhase = false;
        stableSignature = "";
        stableTicks = 0;
    }

    private String validateExactOpportunity(Opportunity requested,
                                            boolean requireSeller) {
        if (requested == null || requested.listing() == null) {
            return "Cannot verify a missing opportunity";
        }
        String listingKey = requested.listing().listingKey();
        if (listingKey == null || listingKey.isBlank()) {
            return "Listing identity is missing; refusing live execution";
        }
        if (listingKey.toLowerCase(Locale.ROOT).startsWith("demo-")) {
            return "Demo opportunities are previews only and can never send server actions";
        }
        String itemId = requested.listing().itemId();
        if (itemId == null
                || itemId.isBlank()
                || requested.listing().itemCount() <= 0
                || requested.buyPrice() <= 0) {
            return "Opportunity has an invalid item, stack size, or price";
        }
        try {
            if (!NamespacedId.normalize(itemId).equals(itemId)) {
                return "Opportunity item id is not a canonical namespaced id";
            }
        } catch (IllegalArgumentException ignored) {
            return "Opportunity item id is not a safe namespaced id";
        }
        if (requested.buyPrice() != requested.listing().totalPrice()) {
            return "Opportunity buy price does not match the listing's total price";
        }
        if (requireSeller
                && (requested.listing().sellerName() == null
                || requested.listing().sellerName().isBlank())) {
            return "An exact seller name is required so DoughBay cannot guess between listings";
        }
        if (requireSeller
                && !PLAYER_NAME.matcher(requested.listing().sellerName()).matches()) {
            return "Seller name is not a canonical Minecraft player name";
        }
        return null;
    }

    private ExecutionResult refusePreflight(Opportunity requested, String reason) {
        String detail = "DRY RUN REFUSED: " + reason;
        lastPreflightReport = reportFor(requested, PreflightOutcome.REFUSED,
                List.of(), null, detail);
        return ExecutionResult.refused(detail);
    }

    private static PreflightReport reportFor(Opportunity requested,
                                             PreflightOutcome outcome,
                                             List<Check> checks,
                                             Path diagnosticsPath,
                                             String detail) {
        if (requested == null || requested.listing() == null) {
            return new PreflightReport(outcome, "", 0, 0, "", checks,
                    diagnosticsPath, detail, System.currentTimeMillis());
        }
        return new PreflightReport(outcome,
                requested.listing().itemId(),
                requested.listing().itemCount(),
                requested.buyPrice(),
                requested.listing().sellerName(),
                checks,
                diagnosticsPath,
                detail,
                System.currentTimeMillis());
    }

    private void prepareOpportunity(Minecraft client, Opportunity requested,
                                    Operation requestedOperation) {
        opportunity = requested;
        operation = requestedOperation;
        targetListingKey = requested.listing().listingKey();
        targetItemKey = requested.listing().itemKey();
        targetItemId = requested.listing().itemId();
        targetCount = requested.listing().itemCount();
        targetPrice = requested.buyPrice();
        targetSeller = nullToEmpty(requested.listing().sellerName());
        inventoryCountBefore = countInventory(client, targetItemId);
        clickedContainerId = -1;
        clickedScreenTitle = "";
        clickedScreenSignature = "";
        confirmationContainerId = -1;
    }

    private void clearTarget() {
        resendCommand = null;
        resendCommandAt = 0;
        commandQueuedAt = 0;
        pendingCommandGap = 0;
        pendingCommandHoldUntil = 0;
        lastCommand = "";
        lastDispatchedCommand = "";
        operation = Operation.NONE;
        opportunity = null;
        targetListingKey = "";
        targetItemKey = "";
        targetItemId = "";
        targetCount = 0;
        targetPrice = 0;
        targetSeller = "";
        targetCeiling = 0;
        targetPriceSpan = 1;
        targetRequiredCount = 0;
        huntLooksRemaining = 0;
        huntedSinceCapture = false;
        watchTargets = List.of();
        watching = false;
        watchWaiting = false;
        watchOpenedAtMillis = 0;
        filterClicks = 0;
        cancelStage = 0;
        cancelPagesTurned = 0;
        cancelNavRetries = 0;
        auditPagesTurned = 0;
        auditRows.clear();
        auditUnreadable = 0;
        auditCollects = 0;
        lastAuditPage = List.of();
        auditPageWaits = 0;
        wrongPageReopens = 0;
        serverRejection = "";
        openRetries = 0;
        cancelOrderReceipt = "";
        listingReceipt = "";
        listingSettleTicks = 0;
        pruneGhosts();
        ghostRows.clear();
        ghostRetries = 0;
        ghostSignalled = false;
        purchaseReceipt = "";
        inventoryCountBefore = 0;
        clickedContainerId = -1;
        clickedScreenTitle = "";
        clickedScreenSignature = "";
        confirmationContainerId = -1;
    }

    private void publishTerminal(TerminalOutcome outcome, String detail) {
        if (operation == Operation.NONE || outcome == null || outcome == TerminalOutcome.NONE) {
            return;
        }
        terminalSequence++;
        lastTerminalEvent = new TerminalEvent(
                terminalSequence,
                operationIntent(),
                outcome,
                targetListingKey,
                targetItemKey,
                targetItemId,
                targetCount,
                targetPrice,
                targetSeller,
                phase == Phase.WAITING_FOR_COMPLETION
                        && (confirmationContainerId >= 0
                        || confirmationContainerId == DIALOG_CONFIRMATION),
                System.currentTimeMillis(),
                detail);
    }

    private boolean isActive() {
        return operation != Operation.NONE;
    }

    private boolean isLiveActive() {
        return operation == Operation.BUY || operation == Operation.LIST;
    }

    private String unavailableReason(Minecraft client) {
        if (client == null || client.player == null || client.getConnection() == null) {
            return "Join a server before starting live execution";
        }
        if (client.gameMode == null) {
            return "Minecraft's multiplayer interaction controller is unavailable";
        }
        String server = currentServerIdentity(client);
        if (server == null || !authorizedServers.contains(server)) {
            return server == null
                    ? "The current server identity is unavailable; execution is locked"
                    : "Server " + server + " is not in GoNuts's authorizedServers allowlist";
        }
        return null;
    }

    private String preflightUnavailableReason(Minecraft client) {
        if (client == null || client.player == null || client.getConnection() == null) {
            return "Join an authorized server before starting a read-only preflight";
        }
        String server = currentServerIdentity(client);
        if (server == null || !preflightServers.contains(server)) {
            return server == null
                    ? "The current server identity is unavailable; preflight is locked"
                    : "Server " + server + " is not in DoughBay's preflightServers allowlist";
        }
        return null;
    }

    private static Set<String> normalizeServers(List<String> servers) {
        if (servers == null || servers.isEmpty()) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String server : servers) {
            if (server != null && !server.isBlank()) {
                normalized.add(server.strip().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private static String currentServerIdentity(Minecraft client) {
        if (client.isLocalServer() || client.hasSingleplayerServer()) {
            return "singleplayer";
        }
        var server = client.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return null;
        return server.ip.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a stack is plain enough to list as the base item. DonutSMP
     * stamps a sell-value hint into the lore of everything in the player's
     * inventory, so lore alone is not a modification of the item; any other
     * component (a name, enchantment, trim, contents) is.
     */
    /**
     * Rows with enchantments, effects, trims or contents are logged with
     * their asking price: the only price history that exists for them.
     */
    private void observeComponents(Minecraft client, ItemStack stack) {
        try {
            if (!ItemDescriptor.of(stack).hasParts()) return;
            List<String> lines = tooltipLines(client, stack);
            PriceEvidence price = authoritativePriceEvidence(lines);
            if (!price.sawAuthoritativeField() || price.unclear()) return;
            ComponentObserver.observeRow(stack, price.displayed(), ComponentObserver.sellerFrom(lines), "recent");
        } catch (RuntimeException ignored) {
            // Observation never interferes with the trade.
        }
    }

    /**
     * A stack the mod can list: nothing attached that changes its value
     * except what the descriptor accounts for. Lore and a custom name are
     * cosmetic; container contents are the whole point of a box and are
     * matched by descriptor hash where a position carries one.
     */
    public static boolean plainForListing(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        // The server stamps stacks with lore, custom data and other cosmetic
        // components of its own; an allowlist kept rejecting stacks it had
        // never seen (70 maps sat unlisted on 2026-09-03). Only components
        // that make the item a different product disqualify it here: the
        // descriptor's parts, and wear on gear.
        if (dev.doughbay.fabric.ItemDescriptor.of(stack).hasParts()) return false;
        return !worn(stack);
    }

    /** Damaged gear is a different product from new gear, and is never bought. */
    public static boolean worn(ItemStack stack) {
        Integer damage = stack.get(DataComponents.DAMAGE);
        return damage != null && damage > 0;
    }

    /** The component keys on a stack, for the log. */
    public static List<String> componentKeys(ItemStack stack) {
        List<String> keys = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return keys;
        for (var entry : stack.getComponentsPatch().entrySet()) keys.add(String.valueOf(entry.getKey()));
        return keys;
    }

    /**
     * A stack the mod can list under a hashed key: the parts the descriptor
     * accounts for (enchantments, a potion, a trim, contents) are allowed
     * because the key's hash pins them; anything else is refused.
     */
    public static boolean listableWithParts(ItemStack stack) {
        // Anything that exists can be listed, wear included: a damaged trident
        // is a real item with a real price, and it is tracked under its own
        // descriptor hash so it is never priced as if it were new. Only
        // "plain" stays strict, because that is what the market stats mean.
        return stack != null && !stack.isEmpty();
    }

    /** Prices a filled box on the watch page: {ceiling, value}, or null to ignore it. */
    private volatile java.util.function.Function<ItemStack, long[]> containerPricer;

    public void setContainerPricer(java.util.function.Function<ItemStack, long[]> pricer) {
        containerPricer = pricer;
    }

    private long[] boxPrice(ItemStack stack) {
        java.util.function.Function<ItemStack, long[]> pricer = containerPricer;
        if (pricer == null) return null;
        try {
            return pricer.apply(stack);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The item key (with descriptor hash for a box) the watch clicked, or "". */
    public synchronized String watchClickedItemKey() {
        return operation == Operation.BUY && watching ? targetItemKey : "";
    }

    /** The last key a watch clicked, kept after the operation ends so the terminal can be matched. */
    private String lastWatchClickedKey = "";

    public synchronized String lastWatchClickedKey() {
        return lastWatchClickedKey;
    }

    private static int countInventory(Minecraft client, String itemId) {
        if (client == null || client.player == null || itemId == null || itemId.isBlank()) {
            return 0;
        }
        int total = 0;
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && itemId(stack).equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** The opportunity currently being bought, or null for idle/list operations. */
    public synchronized Opportunity armedOpportunity() {
        return opportunity;
    }

    /**
     * Immutable, exact target evidence emitted once for every operation that
     * reaches a terminal state. Consumers must advance by {@code sequence};
     * timestamps and detail text are diagnostic only.
     */
    public record TerminalEvent(
            long sequence,
            OperationIntent intent,
            TerminalOutcome outcome,
            String listingKey,
            String itemKey,
            String itemId,
            int itemCount,
            long price,
            String seller,
            boolean confirmationSubmitted,
            long occurredAtMillis,
            String detail
    ) {
        public TerminalEvent {
            intent = intent == null ? OperationIntent.NONE : intent;
            outcome = outcome == null ? TerminalOutcome.NONE : outcome;
            listingKey = nullToEmpty(listingKey);
            itemKey = nullToEmpty(itemKey);
            itemId = nullToEmpty(itemId);
            seller = nullToEmpty(seller);
            detail = nullToEmpty(detail);
        }

        private static TerminalEvent none() {
            return new TerminalEvent(0, OperationIntent.NONE, TerminalOutcome.NONE,
                    "", "", "", 0, 0, "", false, 0, "No terminal event");
        }
    }

    /** Immutable evidence from the most recent read-only preflight attempt. */
    public record PreflightReport(
            PreflightOutcome outcome,
            String itemId,
            int itemCount,
            long price,
            String seller,
            List<Check> checks,
            Path diagnosticsPath,
            String detail,
            long updatedAtMillis
    ) {
        public PreflightReport {
            outcome = outcome == null ? PreflightOutcome.NOT_RUN : outcome;
            itemId = nullToEmpty(itemId);
            seller = nullToEmpty(seller);
            checks = checks == null ? List.of() : List.copyOf(checks);
            detail = nullToEmpty(detail);
        }

        private static PreflightReport notRun() {
            return new PreflightReport(PreflightOutcome.NOT_RUN, "", 0, 0,
                    "", List.of(), null, "Read-only preflight has not run", 0);
        }
    }

    /** One line in DoughBayScreen's live verification panel. */
    public record Check(String label, String expected, String observed, Status status) {
        public enum Status { MATCH, MISMATCH, UNKNOWN }
    }

    /**
     * Reads the currently open auction screen without changing it. Unlike the
     * original assisted driver, this also parses only recognized authoritative
     * price and seller fields; unknown formats remain UNKNOWN rather than being
     * treated as a match.
     */
    public synchronized List<Check> verifyAgainstOpenScreen() {
        List<Check> checks = new ArrayList<>();
        Opportunity target = opportunity;
        if (target == null) {
            return checks;
        }

        String expectedItem = shortName(target.listing().itemId());
        String expectedCount = String.valueOf(target.listing().itemCount());
        String expectedPrice = money(target.buyPrice());
        String expectedSeller = nullToEmpty(target.listing().sellerName());

        Minecraft client = Minecraft.getInstance();
        Screen screen = client.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            checks.add(new Check("Auction screen open", "auction/container GUI",
                    "no container open", Check.Status.UNKNOWN));
            checks.add(new Check("Item", expectedItem, "—", Check.Status.UNKNOWN));
            checks.add(new Check("Stack size", expectedCount, "—", Check.Status.UNKNOWN));
            checks.add(new Check("Price", expectedPrice, "—", Check.Status.UNKNOWN));
            if (!expectedSeller.isBlank()) {
                checks.add(new Check("Seller", expectedSeller, "—", Check.Status.UNKNOWN));
            }
            return checks;
        }

        checks.add(new Check("Auction screen open", "auction/container GUI",
                container.getTitle().getString(), Check.Status.MATCH));

        Inventory inventory = client.player == null ? null : client.player.getInventory();
        List<ItemObservation> sameItem = new ArrayList<>();
        for (Slot slot : container.getMenu().slots) {
            if (inventory != null && slot.container == inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !itemId(stack).equals(target.listing().itemId())) {
                continue;
            }
            List<String> tooltip = tooltipLines(client, stack);
            sameItem.add(new ItemObservation(stack.getCount(), tooltip,
                    authoritativePriceEvidence(tooltip),
                    !expectedSeller.isBlank()
                            && tooltipContainsSeller(tooltip, expectedSeller)));
        }

        if (sameItem.isEmpty()) {
            checks.add(new Check("Item", expectedItem, "not visible", Check.Status.MISMATCH));
            checks.add(new Check("Stack size", expectedCount, "—", Check.Status.UNKNOWN));
            checks.add(new Check("Price", expectedPrice, "—", Check.Status.UNKNOWN));
            if (!expectedSeller.isBlank()) {
                checks.add(new Check("Seller", expectedSeller, "—", Check.Status.UNKNOWN));
            }
            return checks;
        }

        checks.add(new Check("Item", expectedItem, expectedItem, Check.Status.MATCH));
        List<ItemObservation> exactCount = sameItem.stream()
                .filter(o -> o.count() == target.listing().itemCount())
                .toList();
        List<ItemObservation> exactPrice = List.of();
        if (exactCount.isEmpty()) {
            int observedCount = sameItem.getFirst().count();
            checks.add(new Check("Stack size", expectedCount, String.valueOf(observedCount),
                    Check.Status.MISMATCH));
            checks.add(new Check("Price", expectedPrice, "stack mismatch", Check.Status.UNKNOWN));
        } else {
            checks.add(new Check("Stack size", expectedCount, expectedCount, Check.Status.MATCH));
            exactPrice = exactCount.stream()
                    .filter(o -> o.price().isExact(target.buyPrice()))
                    .toList();
            Set<Long> observedPrices = new LinkedHashSet<>();
            exactCount.forEach(o -> observedPrices.addAll(o.price().values()));
            boolean sawPrice = exactCount.stream()
                    .anyMatch(o -> o.price().sawAuthoritativeField());
            boolean unclearPrice = exactCount.stream().anyMatch(o -> o.price().unclear());
            if (!exactPrice.isEmpty()) {
                checks.add(new Check("Price", expectedPrice, expectedPrice, Check.Status.MATCH));
            } else if (unclearPrice) {
                checks.add(new Check("Price", expectedPrice,
                        "authoritative field ambiguous", Check.Status.UNKNOWN));
            } else if (sawPrice) {
                checks.add(new Check("Price", expectedPrice,
                        formatObservedPrices(observedPrices), Check.Status.MISMATCH));
            } else {
                checks.add(new Check("Price", expectedPrice,
                        "listing price/cost/total field not recognized", Check.Status.UNKNOWN));
            }
        }

        if (!expectedSeller.isBlank()) {
            boolean sellerMatch = exactPrice.stream().anyMatch(ItemObservation::sellerMatch);
            boolean anyTooltip = exactPrice.stream().anyMatch(o -> !o.tooltip().isEmpty());
            checks.add(new Check("Seller", expectedSeller,
                    sellerMatch ? expectedSeller : (anyTooltip ? "not recognized" : "—"),
                    sellerMatch ? Check.Status.MATCH : Check.Status.UNKNOWN));
        }
        return checks;
    }

    private enum CaptureStage {
        LISTING("auction-listing"),
        CONFIRMATION("auction-confirmation");

        private final String fileStem;

        CaptureStage(String fileStem) {
            this.fileStem = fileStem;
        }
    }

    /**
     * Captures the first results GUI and first confirmation GUI in a game
     * session. Minecraft writes the PNG to its normal screenshots directory;
     * DoughBay writes a paired slot/lore dump to {@code doughbay-captures/}.
     */
    private void captureOnce(Minecraft client, AbstractContainerScreen<?> container,
                             CaptureStage stage) {
        // Evidence captures are for the read-only preflight. Live trading
        // wrote a lore dump and a screenshot for every page it opened.
        if (operation != Operation.DRY_RUN) return;
        if (stage == CaptureStage.LISTING) {
            if (listingCaptureTaken) return;
            listingCaptureTaken = true;
        } else {
            if (confirmationCaptureTaken) return;
            confirmationCaptureTaken = true;
        }

        try {
            Path directory = client.gameDirectory.toPath().resolve("doughbay-captures");
            Files.createDirectories(directory);
            String timestamp = LocalDateTime.now().format(CAPTURE_TIME);
            Path dump = directory.resolve(stage.fileStem + "-" + timestamp + ".txt");

            List<String> lines = new ArrayList<>();
            lines.add("GoNuts auction GUI capture");
            lines.add("stage=" + stage.name());
            lines.add("screenTitle=" + container.getTitle().getString());
            lines.add("containerId=" + container.getMenu().containerId);
            lines.add("targetItem=" + targetItemId);
            lines.add("targetCount=" + targetCount);
            lines.add("targetPrice=" + targetPrice);
            lines.add("targetSeller=" + targetSeller);
            lines.add("");

            Inventory inventory = client.player == null ? null : client.player.getInventory();
            for (int menuSlot = 0; menuSlot < container.getMenu().slots.size(); menuSlot++) {
                Slot slot = container.getMenu().slots.get(menuSlot);
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                boolean playerSlot = inventory != null && slot.container == inventory;
                lines.add(String.format(Locale.ROOT,
                        "slot=%d area=%s item=%s count=%d backingIndex=%d",
                        menuSlot, playerSlot ? "player" : "gui", itemId(stack),
                        stack.getCount(), slot.getContainerSlot()));
                List<String> tooltip = tooltipLines(client, stack);
                if (tooltip.isEmpty()) {
                    lines.add("  tooltip=<empty>");
                } else {
                    for (String line : tooltip) {
                        lines.add("  tooltip=" + line);
                    }
                }
                PriceEvidence parsed = authoritativePriceEvidence(tooltip);
                if (parsed.sawAuthoritativeField()) {
                    lines.add("  authoritativePrices=" + parsed.values());
                    lines.add("  authoritativePriceUnclear=" + parsed.unclear());
                }
            }

            Files.write(dump, lines, StandardCharsets.UTF_8);
            lastCapturePath = dump;
            Screenshot.grab(client, false);
            DoughBayClient.LOGGER.info(
                    "Captured {} GUI. Lore dump: {} (PNG saved by Minecraft in screenshots)",
                    stage.name().toLowerCase(Locale.ROOT), dump);
        } catch (IOException | RuntimeException e) {
            DoughBayClient.LOGGER.warn("Could not capture DoughBay auction GUI: {}", e.toString());
        }
    }

    public synchronized Path lastCapturePath() {
        return lastCapturePath;
    }

    private static List<String> tooltipLines(Minecraft client, ItemStack stack) {
        try {
            return Screen.getTooltipFromItem(client, stack).stream()
                    .map(Component::getString)
                    .toList();
        } catch (RuntimeException e) {
            DoughBayClient.LOGGER.debug("Could not read tooltip for {}: {}",
                    itemId(stack), e.toString());
            return List.of();
        }
    }

    /**
     * The tolerance implied by an abbreviated price's precision.
     *
     * <p>{@code $ 22K} is not 22,000; it is any price the server abbreviates
     * to 22K. Observed live: a listing of 7,499 displays as {@code $ 7.4K},
     * which rounding could not produce — the server truncates. The true price
     * therefore sits in {@code [displayed, displayed + unit)}: "22K" spans a
     * thousand, "7.7K" spans a hundred, and an unabbreviated "361" is exact.
     *
     * <p>This is only ever used to shortlist a slot to click. The exact price
     * is verified against the confirmation screen before anything is bought,
     * so a wide bound here cannot cause an overpay — only a refusal later.
     */
    static long displayedPriceUnit(String digits, String suffix) {
        long unit = 1;
        if (suffix != null && !suffix.isBlank()) {
            unit = switch (Character.toLowerCase(suffix.charAt(0))) {
                case 'k' -> 1_000L;
                case 'm' -> 1_000_000L;
                case 'b' -> 1_000_000_000L;
                case 't' -> 1_000_000_000_000L;
                default -> 1L;
            };
        }
        int decimals = 0;
        int dot = digits.indexOf('.');
        if (dot >= 0) decimals = digits.length() - dot - 1;
        for (int i = 0; i < decimals; i++) {
            unit /= 10;
        }
        // A price shown to the coin is exact; anything coarser spans a unit.
        return Math.max(1, unit);
    }

    /**
     * Prices read from a listing's lore, with the tolerance each implies.
     *
     * <p>Labelled fields are exact. A bare {@code $ 22K} is accepted as a
     * shortlisting candidate carrying its rounding tolerance, because the
     * server publishes nothing more precise on this screen.
     */
    /** Whether this lore names a seller in a recognized ownership field. */
    private static boolean tooltipMentionsAnySeller(List<String> tooltip) {
        for (String line : tooltip) {
            if (SELLER_FIELD.matcher(line).matches()) return true;
        }
        return false;
    }

    private static PriceEvidence authoritativePriceEvidence(List<String> tooltip) {
        Set<Long> prices = new LinkedHashSet<>();
        boolean sawField = false;
        boolean malformed = false;
        long tolerance = 0;
        for (String line : tooltip) {
            if (!AUTHORITATIVE_PRICE_PREFIX.matcher(line).find()) {
                continue;
            }
            sawField = true;
            Matcher matcher = AUTHORITATIVE_PRICE_FIELD.matcher(line);
            if (!matcher.matches()) {
                malformed = true;
                continue;
            }
            Long value = parseAmount(matcher.group(1), matcher.group(2));
            if (value == null || value <= 0) {
                malformed = true;
            } else {
                prices.add(value);
            }
        }
        if (!sawField) {
            // No labelled field: fall back to the bare currency line, which is
            // the only price DonutSMP's auction GUI shows.
            for (String line : tooltip) {
                Matcher bare = BARE_PRICE_FIELD.matcher(line);
                if (!bare.matches()) continue;
                sawField = true;
                Long value = parseAmount(bare.group(1), bare.group(2));
                if (value == null || value <= 0) {
                    malformed = true;
                } else {
                    prices.add(value);
                    tolerance = Math.max(tolerance,
                            displayedPriceUnit(bare.group(1), bare.group(2)));
                }
            }
        }
        return new PriceEvidence(prices, sawField, malformed, tolerance);
    }

    /** Package-visible numeric view retained for focused parser tests. */
    static Set<Long> pricesFromTooltip(List<String> tooltip) {
        return authoritativePriceEvidence(tooltip).values();
    }

    private static Long parseAmount(String numeric, String suffix) {
        try {
            String normalized = numeric.replace(",", "")
                    .replace("_", "")
                    .replace(" ", "");
            BigDecimal value = new BigDecimal(normalized);
            long multiplier = switch (suffix == null ? "" : suffix.toLowerCase(Locale.ROOT)) {
                case "k" -> 1_000L;
                case "m" -> 1_000_000L;
                case "b" -> 1_000_000_000L;
                case "t" -> 1_000_000_000_000L;
                default -> 1L;
            };
            return value.multiply(BigDecimal.valueOf(multiplier))
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean tooltipContainsSeller(List<String> tooltip, String expected) {
        String seller = expected.trim();
        if (!PLAYER_NAME.matcher(seller).matches()) return false;
        for (String line : tooltip) {
            Matcher field = SELLER_FIELD.matcher(line);
            if (field.matches() && field.group(1).equalsIgnoreCase(seller)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        Pattern exactWord = Pattern.compile(
                "(?i)(?<![A-Za-z0-9_])" + Pattern.quote(word)
                        + "(?![A-Za-z0-9_])");
        return exactWord.matcher(text).find();
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    private static boolean isGreenControl(String itemId) {
        boolean green = itemId.contains("lime") || itemId.contains("green")
                || itemId.contains("emerald");
        boolean controlMaterial = itemId.contains("glass_pane")
                || itemId.contains("stained_glass")
                || itemId.contains("concrete")
                || itemId.contains("terracotta")
                || itemId.contains("wool")
                || itemId.contains("dye")
                || itemId.contains("emerald");
        return green && controlMaterial;
    }

    private static String firstUsefulLine(List<String> tooltip) {
        for (String line : tooltip) {
            if (!line.isBlank()) return line;
        }
        return "no tooltip text";
    }

    private static String formatObservedPrices(Set<Long> prices) {
        if (prices.isEmpty()) return "unparsed";
        return prices.stream().limit(4).map(AutomatedExecutionDriver::money)
                .reduce((a, b) -> a + ", " + b).orElse("unparsed");
    }

    private static String money(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String baseItemId(String itemKey) {
        if (itemKey == null) return "";
        int hash = itemKey.indexOf('#');
        return hash >= 0 ? itemKey.substring(0, hash) : itemKey;
    }

    /** The text accepted by /ah search for the opportunity's item. */
    public static String searchTermFor(Opportunity opportunity) {
        if (opportunity == null || opportunity.listing() == null) {
            throw new IllegalArgumentException("Cannot build a search for a missing opportunity");
        }
        String raw = opportunity.listing().itemId();
        final String canonical;
        try {
            canonical = NamespacedId.normalize(raw);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException(
                    "Cannot build a command from a malformed item id");
        }
        if (!canonical.equals(raw)) {
            throw new IllegalArgumentException(
                    "Cannot build a command from a non-canonical item id");
        }
        return searchTermForItemId(canonical);
    }

    /** The /ah search term for a canonical item id. */
    static String searchTermForItemId(String canonical) {
        String bare = canonical.substring(canonical.indexOf(':') + 1);
        String fromId = bare.replace('_', ' ');

        // The auction house searches display names, and Minecraft's do not
        // always follow the id: minecraft:iron_block reads "Block of Iron",
        // not "Iron Block", so an id-derived term finds nothing. Ask the
        // registry for the real name and fall back to the id when the client
        // has never heard of the item.
        String displayName = displayNameFor(canonical);
        return displayName.isEmpty() ? fromId : displayName;
    }

    /** The client's display name for an item id, or "" when unknown. */
    public static String displayNameFor(String canonicalItemId) {
        try {
            Identifier id = Identifier.tryParse(canonicalItemId);
            if (id == null) return "";
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null) return "";
            String name = new ItemStack(item).getHoverName().getString().strip();
            // A missing translation renders as its own key; that is not a name.
            return name.isEmpty() || name.contains("item.") || name.contains("block.")
                    ? "" : name;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String shortName(String itemId) {
        String base = baseItemId(nullToEmpty(itemId));
        String bare = base.contains(":") ? base.substring(base.indexOf(':') + 1) : base;
        return bare.replace('_', ' ');
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Chat carries only what the player would want to read: a completed
     * buy, a completed listing, a pull-back, an emergency stop, and an
     * abort that is not part of normal trading. Everything else stays in
     * the log and on the HUD line.
     */
    private static void notifyPlayer(Minecraft client, String message) {
        if (client == null || client.player == null || message == null) return;
        String shown = chatLine(message);
        if (shown == null) return;
        client.player.sendSystemMessage(Component.literal("[GoNuts] " + shown));
    }

    private static final String[] ROUTINE_ABORTS = {
        "No recent listing came in", "vanished on click", "did not open a container",
        "not among your listings", "Your Items page did not open", "is not visible in the auction",
        "Cheapest visible", "no click sent", "already bought",
    };

    static String chatLine(String message) {
        String m = message.strip();
        if (m.startsWith("Buy verified by server receipt: You bought ")) {
            return "Bought " + m.substring("Buy verified by server receipt: You bought ".length());
        }
        if (m.startsWith("Buy verified: ")) {
            String rest = m.substring("Buy verified: ".length());
            int cut = rest.indexOf(" entered inventory");
            return "Bought " + (cut > 0 ? rest.substring(0, cut) : rest);
        }
        if (m.startsWith("Listing verified: ")) {
            String rest = m.substring("Listing verified: ".length());
            return "Listed " + rest.replace(" left inventory at ", " at ");
        }
        if (m.startsWith("Pulled back ")) return m;
        if (m.startsWith("Emergency stop")) return m;
        if (m.startsWith("DRY RUN")) return m;
        if (m.startsWith("Auction slots checked")) return m;
        if (m.startsWith("ABORTED")) {
            for (String routine : ROUTINE_ABORTS) {
                if (m.contains(routine)) return null;
            }
            return m;
        }
        return null;
    }

    private record AuctionCandidate(
            int menuSlot,
            int count,
            List<String> tooltip,
            PriceEvidence price,
            boolean sellerMatch
    ) {
    }

    /**
     * @param tolerance the span a displayed price covers, since the server
     *        truncates rather than rounds. One for a labelled or coin-exact
     *        field; larger only for an abbreviated bare price like
     *        {@code $ 22K}, which covers 22,000 through 22,999.
     */
    private record PriceEvidence(
            Set<Long> values,
            boolean sawAuthoritativeField,
            boolean malformed,
            long tolerance
    ) {
        private PriceEvidence {
            values = values == null ? Set.of() : Set.copyOf(values);
        }

        /**
         * Whether this lore can be the listing being sought.
         *
         * <p>Within tolerance rather than equal, because an abbreviated price
         * names a range. This only decides which slot to click; the exact
         * price is checked again on the confirmation screen, where a mismatch
         * aborts before anything is bought.
         */
        private boolean isExact(long expected) {
            if (!sawAuthoritativeField || malformed || values.size() != 1) return false;
            long observed = values.iterator().next();
            // Truncated, so the true price is at or above what is displayed and
            // below the next step. A unit of one means the lore named the coin
            // and this collapses to equality.
            return expected >= observed && expected - observed < Math.max(1, tolerance);
        }

        private boolean unclear() {
            return sawAuthoritativeField && (malformed || values.size() != 1);
        }

        /** The single displayed amount; only meaningful when not unclear. */
        private long displayed() {
            return values.isEmpty() ? 0 : values.iterator().next();
        }

        /** The highest true price this displayed amount can stand for. */
        private long upperBound() {
            return displayed() + Math.max(1, tolerance) - 1;
        }
    }

    private record ListingChoice(
            Resolution resolution,
            int menuSlot,
            String verification,
            String detail
    ) {
    }

    private record ControlCandidate(
            int menuSlot,
            int score,
            boolean explicitConfirm,
            String description
    ) {
    }

    private record ConfirmChoice(
            Resolution resolution,
            int menuSlot,
            boolean explicitConfirmation,
            String detail
    ) {
    }

    private record ItemObservation(
            int count,
            List<String> tooltip,
            PriceEvidence price,
            boolean sellerMatch
    ) {
    }
}
