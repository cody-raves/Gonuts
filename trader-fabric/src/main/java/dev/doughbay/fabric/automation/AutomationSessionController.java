package dev.doughbay.fabric.automation;

import dev.doughbay.core.automation.ContinuousAutomationContext;
import dev.doughbay.core.automation.ContinuousAutomationPolicy;
import dev.doughbay.core.automation.ContinuousOpportunitySelector;
import dev.doughbay.core.automation.ContinuousOpportunityRevalidator;
import dev.doughbay.core.automation.ActiveBookEvidence;
import dev.doughbay.core.automation.ActiveListingReconciler;
import dev.doughbay.core.automation.TrackedSaleMatcher;
import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.analysis.RiskConfig;
import dev.doughbay.core.execution.ExecutionResult;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import dev.doughbay.core.text.DonutSaleMessageParser;
import dev.doughbay.fabric.AutomatedExecutionDriver;
import dev.doughbay.fabric.DoughBayScreen;
import dev.doughbay.fabric.MarketWatcher;
import dev.doughbay.storage.AutomationManualResolution;
import dev.doughbay.storage.AutomationPersistencePort;
import dev.doughbay.storage.AutomationSessionCheckpoint;
import dev.doughbay.storage.AutomationSessionRecovery;
import dev.doughbay.storage.AutomationUncertainExposure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Authorized buy-list-monitor orchestration that advances once per client tick.
 *
 * <p>The controller never derives success from UI strings. It consumes the
 * driver's immutable terminal events, allows only one open position, and
 * pauses on every post-purchase ambiguity. Network and database collection
 * remain outside the render thread; this class performs only bounded inventory
 * inspection and explicit client interactions.
 */
public final class AutomationSessionController {
    private static final int MAX_ATTEMPTED_LISTINGS = 4_096;
    private static final int SWAP_VERIFY_TICKS = 10;
    private static final int PREPARE_MAX_RETRIES = 3;
    // A sell command lost to the server's post-burst chat-command rate limit
    // produces a "no confirmation GUI" terminal with nothing listed, so it is
    // always safe to send again. Two tries is not enough to outlast a busy
    // command window; give it more, spaced further apart each time.
    private static final int LIST_MAX_RETRIES = 5;
    // How long the durable LIST intent may stay un-acknowledged before the
    // stack is re-queued and the session scans on, rather than returning that
    // "waiting for durable LIST intent" line every tick forever.
    private static final long LIST_INTENT_WAIT_TIMEOUT_MILLIS = 20_000L;
    private boolean staleFeedListingLogged;
    private int prepareRetries;
    private long prepareRetryPositionId = -1;
    private static final long RUNTIME_HEARTBEAT_NANOS = 10_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    public enum State {
        STOPPED,
        SCANNING,
        BUYING,
        PREPARING_LIST,
        LISTING,
        MONITORING,
        COOLDOWN,
        /** Pulling an unsold parked listing back to relist it a step cheaper. */
        REPRICING,
        /** Reading the own-listings page to learn what sold while offline. */
        AUDITING_SLOTS,
        /** Reading the order house: every open buy order and its price. */
        READING_ORDERS,
        /** Working the player's own orders: reading, placing, collecting, cancelling. */
        BID_DESK,
        PAUSED
    }

    public enum RunMode { SINGLE, CONTINUOUS }

    private final AutomatedExecutionDriver driver;
    private final ContinuousOpportunitySelector selector = new ContinuousOpportunitySelector();
    private volatile ContinuousOpportunityRevalidator revalidator =
            new ContinuousOpportunityRevalidator();
    private final ActiveBookEvidence activeBookEvidence = new ActiveBookEvidence();
    // The server names items the way the client does ("Bottle o' Enchanting"),
    // not by id ("experience bottle"); without the alias those sales never closed.
    private final TrackedSaleMatcher saleMatcher = new TrackedSaleMatcher(key -> {
        int hash = key == null ? -1 : key.indexOf('#');
        String id = hash >= 0 ? key.substring(0, hash) : key;
        return dev.doughbay.fabric.AutomatedExecutionDriver.displayNameFor(id);
    });
    private final ActiveListingReconciler listingReconciler = new ActiveListingReconciler();
    private final LinkedHashSet<String> attemptedListingKeys = new LinkedHashSet<>();

    private AutomationPersistencePort persistence;
    private boolean recoveryApplied;
    private String persistenceStatus = "Automation recovery is not connected";
    private boolean sessionOpen;
    private boolean recoveredSession;
    private long sessionStartedAtMillis;
    private long sessionActiveMillis;
    private long lifetimeActiveMillis;
    private long activeStateStartedNanos;
    private long activeSubMillisNanos;
    private long lastRuntimeHeartbeatNanos;
    private long runtimePersistenceGeneration;
    private List<Position> recoveredOpenPositions = List.of();
    /** Further recovered purchases waiting their turn: each goes through the ordinary listing flow after the one before it. */
    private final List<Position> recoveredQueue = new ArrayList<>();
    private AutomationUncertainExposure uncertainExposure =
            AutomationUncertainExposure.none();
    private long sessionStartPersistenceGeneration;
    private long buyIntentPersistenceGeneration;
    private long purchasePersistenceGeneration;
    private long listIntentPersistenceGeneration;
    private long listedPersistenceGeneration;
    private long settlementPersistenceGeneration;
    private Position settlementPendingPosition;
    private long endSessionPersistenceGeneration;
    private long manualResolutionPersistenceGeneration;
    private boolean buyCommandStarted;
    private long buyIntentCoverageBoundaryMillis;
    private boolean listCommandStarted;
    private long listIntentCoverageBoundaryMillis;
    private long manualResolutionFirstConfirmedAt;
    private long manualResolutionCoverageBoundary;
    private long manualResolutionEvidenceAt;
    private String manualResolutionEvidence = "";
    private boolean manualResolutionEvidenceReady;

    private volatile boolean continuousAuthorizationEnabled;
    private volatile boolean auctionFeesConfirmed;
    private volatile FeeConfig auctionFees = FeeConfig.zero();
    private volatile Set<String> continuousAuthorizedServers = Set.of();
    private volatile Set<String> saleNotificationTrustedServers = Set.of();

    private State state = State.STOPPED;
    private RunMode runMode = RunMode.SINGLE;
    private ContinuousAutomationPolicy policy;
    private Opportunity pendingOpportunity;
    private Opportunity positionValuation;
    private Position trackedPosition;
    private long awaitedTerminalSequence;
    private int tradesStarted;
    private long committedSpend;
    /**
     * Listings that are up on the auction house while the session keeps
     * trading. Each is a real LISTED position in the database; the server's
     * sale notice closes it. Continuous mode parks a verified listing here
     * and goes back to scanning instead of monitoring it to the sale.
     */
    private final List<Position> openListings = new ArrayList<>();
    /**
     * The current buy is a recent-listings watch across every market that
     * clears the gates, rather than a search for one candidate. Which
     * market actually fills is known only when the driver reports it.
     */
    private boolean watchBuy;
    private List<Opportunity> watchCandidates = List.of();

    /**
     * The caps grow with the bankroll: a purchase may be up to this share of
     * the balance and the money parked in listings up to this share, never
     * below the configured caps. The balance comes from the market feed.
     */
    private static double PURCHASE_CAP_PERCENT_OF_BALANCE() {
        return dev.doughbay.fabric.Tuning.get("buy.purchase_cap_pct");
    }
    private static double SPEND_CAP_PERCENT_OF_BALANCE() {
        return dev.doughbay.fabric.Tuning.get("buy.outstanding_cap_pct");
    }
    private long lastKnownBalance = -1;

    /**
     * With the balance known the caps are pure shares of it, so a $20K
     * player is held to $1,600 purchases and a $10M player is allowed
     * $800K ones. The configured caps only apply while the balance is
     * unknown, such as before the first API sweep.
     */
    private long effectivePurchaseCap() {
        if (lastKnownBalance <= 0) return policy.maxPurchasePrice();
        return Math.max(1_000, Math.round(lastKnownBalance * PURCHASE_CAP_PERCENT_OF_BALANCE() / 100.0));
    }

    /**
     * The policy the selector gates against: the config's per-purchase cap
     * is the floor, and once the balance is known the cap scales with it,
     * the same figure the buy ceiling already uses. Without this the tiers
     * above the config cap could never be selected.
     */
    /**
     * Whether the market has gone quiet: our own sales in the last 45 minutes
     * against the rate the last day has run at. Below the "quiet" share the
     * session stops holding out for the big clean flips that are not coming,
     * and takes the smaller, steadier ones instead.
     */
    private volatile boolean quietMarket;
    private volatile String quietDetail = "";
    private long quietCheckedAt;

    // Adaptive market regime: watches what is actually selling and, when the
    // market's character changes, applies a named preset of settings on top of
    // the configured ones - and puts them back when it changes again. Off until
    // market.adaptive is on. NORMAL means "the configured settings, untouched".
    private volatile String marketRegime = "NORMAL";
    private String pendingRegime = "NORMAL";     // candidate awaiting the hysteresis window
    private long pendingRegimeSince;
    private long regimeCheckedAt;
    // The configured values a live preset is sitting on top of, restored when
    // the regime returns to NORMAL. Empty when no preset is applied.
    private final java.util.LinkedHashMap<String, Double> regimeBaseline = new java.util.LinkedHashMap<>();

    /**
     * Whether the client may leave the server without losing anything.
     *
     * <p>False while a command is out, a purchase is in the inventory and not
     * yet listed, or the ledger is carrying uncertain exposure. Leaving then
     * would strand a stack or a payment nobody has a receipt for.
     */
    /**
     * Everything the session believes is in the player's hands right now: a
     * purchase not yet listed, a stack pulled back for repricing, a collected
     * fill. This is what a death can drop, and what the audit after a respawn
     * has to look for.
     */
    /** Whether a position with this exact key is up on the auction right now. */
    public synchronized boolean isListed(String itemKey) {
        for (Position p : openListings) {
            if (p.itemKey().equals(itemKey)) return true;
        }
        return false;
    }

    public synchronized List<Position> stockInHand() {
        List<Position> out = new ArrayList<>();
        if (trackedPosition != null && trackedPosition.status() != PositionStatus.LISTED) out.add(trackedPosition);
        if (repricing != null) out.add(repricing);
        return out;
    }

    /**
     * Base market ids the session is holding an unlisted position in - the
     * stack it just bought, one it is repricing, or a recovered exposure it
     * has yet to resolve. The watcher must keep these scanned: listing or
     * resolving one needs an exact scan of its live book, and that scan is
     * refused for any market not in the scanned set. Auto-selection can
     * otherwise rotate a bought market out from under an open position, and
     * the relist then deadlocks on "not among the scanned markets" until the
     * market is pinned by hand. Already-listed stock is left out - it needs no
     * scan to stay listed, and it re-enters this set as {@code repricing} the
     * moment it needs one - so the pin stays a handful of ids, not the whole
     * shelf.
     */
    public synchronized java.util.Set<String> heldExposureBaseIds() {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Position p : stockInHand()) {
            String b = baseItemId(p.itemKey());
            if (b != null && !b.isBlank()) ids.add(b);
        }
        String recovered = recoveredExposureItemId();
        if (recovered != null && !recovered.isBlank()) ids.add(recovered);
        return ids;
    }

    /**
     * Called after a death and respawn. Anything the session thought it was
     * holding is checked against the inventory; what is gone is written off in
     * the ledger so the books match the world, and named in the log.
     */
    public synchronized List<String> auditAfterDeath(Minecraft client, long now) {
        List<String> lost = new ArrayList<>();
        for (Position p : stockInHand()) {
            if (client != null && client.player != null
                    && unlistedPurchaseSlot(client, p.itemKey(), p.quantity()) >= 0) {
                continue;   // still here
            }
            lost.add(p.itemKey() + " x" + p.quantity() + " (cost " + p.purchasePrice() + ")");
            Position written = p.closed(PositionStatus.CANCELLED, now, 0, -(double) p.purchasePrice());
            committedSpend = Math.max(0, committedSpend - p.purchasePrice());
            persistence.submit(checkpoint(sessionOpen, state.name(), written, uncertainExposure,
                    "Lost on death"));
            if (trackedPosition == p) {
                trackedPosition = null;
                positionValuation = null;
            }
            if (repricing == p) repricing = null;
        }
        if (!lost.isEmpty()) {
            uncertainExposure = AutomationUncertainExposure.none();
            LOGGER.warn("DoughBay automation: died holding {}; written off", lost);
        }
        return lost;
    }

    public synchronized boolean safeToLeave() {
        // A command still out, or exposure the ledger cannot describe, is what
        // makes leaving unsafe. Carrying a bought stack is not: a teleport
        // keeps the inventory, and the position is already checkpointed, so
        // waiting for it to be listed would deadlock whenever the escape has
        // paused the very session that would do the listing.
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return false;
        if (uncertainExposure.active()) return false;
        if (pendingOpportunity != null) return false;
        return persistence.status().durable(persistence.status().lastAcceptedGeneration());
    }

    public boolean quietMarket() {
        return quietMarket;
    }

    public String quietDetail() {
        return quietDetail;
    }

    private void refreshQuietMarket(long now) {
        if (now - quietCheckedAt < 2 * 60_000L) return;
        quietCheckedAt = now;
        if (dev.doughbay.fabric.Tuning.get("quiet.enabled") < 0.5) {
            quietMarket = false;
            quietDetail = "";
            return;
        }
        int recent = 0;
        int window = 0;
        for (long at : saleTimes) {
            if (at > now - 45 * 60_000L) recent++;
            window++;   // the deque holds the last six hours
        }
        double usual = window / 6.0 * 0.75;   // sales an hour over six hours, in a 45-minute window
        double share = usual <= 0 ? 1.0 : recent / usual;
        boolean quiet = usual >= 1 && share < dev.doughbay.fabric.Tuning.get("quiet.share_pct") / 100.0;
        if (quiet != quietMarket) {
            LOGGER.info("DoughBay automation: the market has gone {} ({} sales in 45 min against {} usual)",
                    quiet ? "quiet; taking smaller, steadier trades" : "busy again", recent, Math.round(usual));
        }
        quietMarket = quiet;
        quietDetail = quiet
                ? String.format(java.util.Locale.ROOT, "quiet market (%d sales in 45 min, %.0f%% of usual)", recent, share * 100)
                : "";
    }

    public String marketRegime() {
        return marketRegime;
    }

    /**
     * Classify the market from what is actually selling, and when it settles
     * into a new regime apply that regime's preset of settings.
     *
     * <p>LOW_VALUE = flooded with cheap/small items; the configured value and
     * margin floors skip them, so the preset drops those floors and pours slots
     * into small stock. SLOW = few sales; walk the order house less. NORMAL =
     * the configured settings, untouched. A candidate regime must hold for
     * market.switch_min minutes before it is applied, so the settings do not
     * flip-flop, and the previous preset is put back on every switch.</p>
     */
    private void refreshMarketRegime(long now) {
        if (dev.doughbay.fabric.Tuning.get("market.adaptive") < 0.5) {
            if (!"NORMAL".equals(marketRegime)) enterRegime("NORMAL", now);
            pendingRegime = "NORMAL";
            return;
        }
        if (now - regimeCheckedAt < 60_000L) return;
        regimeCheckedAt = now;

        java.util.List<Long> vals = new java.util.ArrayList<>();
        for (long[] s : saleLog) if (s[0] > now - 90 * 60_000L) vals.add(s[1]);
        long median = 0;
        if (!vals.isEmpty()) {
            java.util.Collections.sort(vals);
            median = vals.get(vals.size() / 2);
        }
        String want = "NORMAL";
        if (vals.size() >= 8 && median > 0
                && median < dev.doughbay.fabric.Tuning.get("market.low_value_max")) {
            want = "LOW_VALUE";
        } else if (quietMarket) {
            want = "SLOW";
        }

        if (!want.equals(pendingRegime)) {
            pendingRegime = want;
            pendingRegimeSince = now;
            return;
        }
        if (want.equals(marketRegime)) return;
        long holdMs = (long) (dev.doughbay.fabric.Tuning.get("market.switch_min") * 60_000L);
        if (now - pendingRegimeSince < holdMs) return;
        enterRegime(want, now);
    }

    /** Restore the previous preset's overrides, then lay the new regime's on. */
    private void enterRegime(String regime, long now) {
        for (var e : regimeBaseline.entrySet()) dev.doughbay.fabric.Tuning.set(e.getKey(), e.getValue());
        regimeBaseline.clear();
        java.util.Map<String, Double> preset = presetFor(regime);
        for (var e : preset.entrySet()) {
            regimeBaseline.put(e.getKey(), dev.doughbay.fabric.Tuning.get(e.getKey()));
            dev.doughbay.fabric.Tuning.set(e.getKey(), e.getValue());
        }
        LOGGER.info("DoughBay automation: market regime {} -> {}{}", marketRegime, regime,
                preset.isEmpty() ? " (configured settings restored)" : " (preset " + preset + ")");
        marketRegime = regime;
        pendingRegime = regime;
        pendingRegimeSince = now;
    }

    /** The setting overrides a regime lays on; NORMAL is empty (configured values). */
    private static java.util.Map<String, Double> presetFor(String regime) {
        return switch (regime) {
            case "LOW_VALUE" -> java.util.Map.of(
                    "orders.bid_min_value", 0.0,
                    "orders.bid_min_margin_pct", 3.0,
                    "buy.max_open_per_market", 8.0,
                    "list.singles", 1.0);
            case "SLOW" -> java.util.Map.of(
                    "orders.scan_min", 20.0);
            default -> java.util.Map.of();
        };
    }

    /** In a quiet market the profit floors come down by the configured share; otherwise they stand. */
    private double quietRelax() {
        if (!quietMarket) return 1.0;
        double pct = dev.doughbay.fabric.Tuning.get("quiet.relax_pct");
        return Math.max(0.3, Math.min(1.0, 1.0 - pct / 100.0));
    }

    private ContinuousAutomationPolicy selectorPolicy() {
        // The session spend scales with the bank too; a per-purchase cap above
        // the session's spend is invalid, so it is clamped to it.
        long spend = Math.max(policy.maxSessionSpend(), effectiveSpendCap());
        long cap = Math.min(spend, Math.max(policy.maxPurchasePrice(), effectivePurchaseCap()));
        double relax = quietRelax();
        if (cap == policy.maxPurchasePrice() && spend == policy.maxSessionSpend() && relax == 1.0) return policy;
        try {
            return new ContinuousAutomationPolicy(policy.maxTradesPerSession(), spend, cap,
                    Math.max(1, Math.round(policy.minimumProfit() * relax)),
                    policy.minimumRoiPercent() * relax, policy.minimumConfidence(),
                    policy.cooldownMillis(), policy.reservedHotbarSlot(), policy.maximumHoldMillis(),
                    policy.maximumSnapshotAgeMillis(), policy.preparationTimeoutMillis(), policy.maxOpenListings());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("DoughBay automation: scaled caps rejected ({}); using the configured policy", e.getMessage());
            return policy;
        }
    }

    private long effectiveSpendCap() {
        if (lastKnownBalance <= 0) return policy.maxSessionSpend();
        return Math.max(5_000, Math.round(lastKnownBalance * SPEND_CAP_PERCENT_OF_BALANCE() / 100.0));
    }

    /**
     * A parked listing unsold for this long is pulled back and relisted one
     * step cheaper, down to the price that still clears the minimum profit.
     */
    private static final long REPRICE_AFTER_MILLIS = 10 * 60 * 1000;
    private static final double REPRICE_STEP_PERCENT = 10.0;

    /**
     * Ticket-size tiers, as a share of the bank. Small tickets are volume:
     * many slots, fast reprice. Large tickets are patience: few slots, slow
     * reprice, proven demand, and a stop-loss so one slow item never holds
     * a slot and the cash forever. Each tier has its own slot pool, and a
     * full pool only spills into another when a deal is too good to pass.
     */
    public enum Tier { SMALL, MID, LARGE }
    private static double MID_TIER_PERCENT_OF_BALANCE() {
        return dev.doughbay.fabric.Tuning.get("tier.mid_from_pct");
    }
    private static double LARGE_TIER_PERCENT_OF_BALANCE() {
        return dev.doughbay.fabric.Tuning.get("tier.large_from_pct");
    }
    /** Fallback thresholds while the balance is unknown. */
    private static final long MID_TIER_FALLBACK = 5_000;
    private static final long LARGE_TIER_FALLBACK = 20_000;
    /** Share of the open-listing cap reserved for MID and LARGE; SMALL gets the rest. */
    private static double MID_TIER_SHARE() {
        return dev.doughbay.fabric.Tuning.get("tier.mid_share_pct") / 100.0;
    }
    private static double LARGE_TIER_SHARE() {
        return dev.doughbay.fabric.Tuning.get("tier.large_share_pct") / 100.0;
    }
    private static double SPILL_MIN_ROI_PERCENT() {
        return dev.doughbay.fabric.Tuning.get("tier.spill_min_roi_pct");
    }
    private static double SPILL_MIN_CONFIDENCE() {
        return dev.doughbay.fabric.Tuning.get("tier.spill_min_confidence_pct") / 100.0;
    }
    private static double LARGE_MIN_SALES_PER_HOUR() {
        return dev.doughbay.fabric.Tuning.get("tier.large_min_sales_per_hour");
    }
    private static int LARGE_MAX_OPEN_PER_MARKET() {
        return (int) dev.doughbay.fabric.Tuning.get("tier.large_max_open_per_market");
    }
    private static long tierRepriceAfterMillis(Tier tier) {
        return switch (tier) {
            case SMALL -> dev.doughbay.fabric.Tuning.millis("reprice.small_after_min");
            case MID -> dev.doughbay.fabric.Tuning.millis("reprice.mid_after_min");
            case LARGE -> dev.doughbay.fabric.Tuning.millis("reprice.large_after_min");
        };
    }

    private static double tierRepriceStepPercent(Tier tier) {
        return switch (tier) {
            case SMALL -> dev.doughbay.fabric.Tuning.get("reprice.small_step_pct");
            case MID -> dev.doughbay.fabric.Tuning.get("reprice.mid_step_pct");
            case LARGE -> dev.doughbay.fabric.Tuning.get("reprice.large_step_pct");
        };
    }
    /** A large ticket unsold this long is let go at break-even. */
    private static long LARGE_STOP_LOSS_AFTER_MILLIS() {
        return dev.doughbay.fabric.Tuning.millis("tier.large_stop_loss_min");
    }
    /**
     * Rival-aware adjustments. A market a rival is working right now gets a
     * slightly lower ceiling, so the bot only fights them for clearly good
     * rows; a market rivals have proven liquid may hold one more listing;
     * and when every known rival has gone quiet the small pool widens and
     * the watch looks come sooner.
     */
    private static double CROWDED_CEILING_CUT_PERCENT() {
        return dev.doughbay.fabric.Tuning.get("rival.crowded_ceiling_cut_pct");
    }
    private static int PROVEN_MIN_RIVAL_FLIPS() {
        return (int) dev.doughbay.fabric.Tuning.get("rival.proven_min_flips");
    }
    private static int QUIET_FIELD_EXTRA_SMALL_SLOTS() {
        return (int) dev.doughbay.fabric.Tuning.get("rival.quiet_extra_small_slots");
    }
    private static double QUIET_FIELD_PACE() {
        return dev.doughbay.fabric.Tuning.get("rival.quiet_pace_factor");
    }
    private String lastRivalMode = "";
    private boolean lastFollowMode;
    /** When the session's own listings sold, newest last; six hours are kept. */
    private final java.util.ArrayDeque<Long> saleTimes = new java.util.ArrayDeque<>();
    /** Sale prices over the same six hours, for the market-regime detector. */
    private final java.util.ArrayDeque<long[]> saleLog = new java.util.ArrayDeque<>();   // {at, value}

    private void recordSaleTime(long at, long value) {
        saleTimes.addLast(at);
        saleLog.addLast(new long[] {at, Math.max(0, value)});
        // Also remember the market, for the underdog: a market we sold in
        // today is old ground, and the underdog is for the rest.
        if (trackedPosition != null) recentlySoldMarkets.add(trackedPosition.itemKey());
        while (!saleTimes.isEmpty() && at - saleTimes.peekFirst() > 6 * 3_600_000L) saleTimes.pollFirst();
        while (!saleLog.isEmpty() && at - saleLog.peekFirst()[0] > 6 * 3_600_000L) saleLog.pollFirst();
    }

    private int salesSince(long since) {
        int n = 0;
        for (long t : saleTimes) if (t > since) n++;
        return n;
    }

    /**
     * Rivals are on and our sales have gone quiet: their markets are moving
     * and ours are not, so their current markets join the watch list.
     */
    private boolean followRivals(long now) {
        if (dev.doughbay.fabric.Tuning.get("rival.mirror_when_slow") < 0.5) return false;
        dev.doughbay.fabric.RivalIntel.Snapshot r = rivals();
        if (r == null || r.activeNow() < 2 || r.crowded().isEmpty()) return false;
        int last30 = salesSince(now - 30 * 60_000L);
        int last6h = salesSince(now - 6 * 3_600_000L);
        if (last6h < 12) return false;   // not enough history to call anything slow
        double average30 = last6h / 12.0;
        return last30 < average30 * dev.doughbay.fabric.Tuning.get("rival.mirror_slow_pct") / 100.0;
    }

    private static boolean isMirror(Opportunity o) {
        return o != null && o.listing() != null && o.listing().listingKey().startsWith("mirror:");
    }

    /**
     * The markets rivals are working right now, priced by our own completed
     * sale statistics: buy under the quick-sale price less fees, minimum
     * profit and minimum ROI, list at the quick-sale price. They skip the
     * feed's ROI and confidence screens on purpose; the rival's activity is
     * the evidence the market is moving.
     */
    private List<Opportunity> mirrorCandidates(MarketWatcher.Snapshot snapshot, long now) {
        dev.doughbay.fabric.RivalIntel.Snapshot r = rivals();
        if (r == null || snapshot == null || policy == null) return List.of();
        long stamp = snapshot.updatedAt() > 0 ? Math.min(now, snapshot.updatedAt()) : now;
        double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
        double minimumRoi = Math.max(policy.minimumRoiPercent(), riskConfig.minimumRoiPercent()) / 100.0;
        List<Opportunity> out = new ArrayList<>();
        for (String key : r.crowded()) {
            if (out.size() >= 12) break;
            int bar = key.lastIndexOf('|');
            if (bar < 0) continue;
            String itemId = key.substring(0, bar);
            int count;
            try {
                count = Integer.parseInt(key.substring(bar + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (itemId.indexOf('#') >= 0 || !dev.doughbay.fabric.Tuning.itemAllowed(itemId)) continue;
            if (openOfMarket(itemId, count) >= MAX_OPEN_PER_MARKET()) continue;
            StackBucket bucket = StackBucket.of(count);
            MarketStats stats = null;
            for (MarketStats m : snapshot.markets()) {
                if (m.itemKey().equals(itemId) && m.bucket() == bucket && m.hasPrices()) stats = m;
            }
            if (stats == null || !(stats.quickSalePrice() > 0) || stats.sampleCount() < 8) continue;
            long sell = (long) Math.floor(stats.quickSalePrice());
            double net = auctionFees.netSale(sell);
            long ceiling = Math.min((long) Math.floor(net - minimumProfit), (long) Math.floor(net / (1.0 + minimumRoi)));
            ceiling = Math.min(ceiling, Math.min(effectivePurchaseCap(), effectiveSpendCap() - committedSpend));
            long buy = (long) Math.floor(ceiling * 0.95);
            if (buy <= 0) continue;
            double profit = net - buy;
            if (profit <= 0) continue;
            Listing signal = new Listing("mirror:" + itemId + ":" + count + ":" + r.refreshedAt(), stamp, "", "mirror",
                    itemId, itemId, count, buy, null);
            double holdHours = stats.salesPerHour() > 0 ? Math.min(3.5, 1.0 / stats.salesPerHour()) : 2.0;
            out.add(new Opportunity(signal, stats, buy, sell, profit, profit / buy * 100.0, holdHours,
                    Math.max(0.6, stats.confidence()), 0.7, 900 + stats.salesPerHour(),
                    List.of("Mirror: a rival is working this market right now")));
        }
        return out;
    }

    /** Told about every closed sale with the buyer's name; used for the buyer log. */
    private static volatile java.util.function.BiConsumer<Position, String> saleObserver;

    public static void setSaleObserver(java.util.function.BiConsumer<Position, String> observer) {
        saleObserver = observer;
    }

    private static void notifySaleObserver(Position sold, String buyer) {
        java.util.function.BiConsumer<Position, String> observer = saleObserver;
        if (observer == null) return;
        try {
            observer.accept(sold, buyer);
        } catch (RuntimeException ignored) {
            // The buyer log never affects the trade.
        }
    }

    /**
     * How busy this market is at the current hour against its own average,
     * multiplied by how the server's current population has traded against
     * its usual one. 1.0 when unknown or switched off.
     */
    private static double demandNow(String itemId, int count) {
        double factor = 1.0;
        if (dev.doughbay.fabric.Tuning.get("demand.clock") >= 0.5) {
            dev.doughbay.fabric.DemandClock clock = dev.doughbay.fabric.DoughBayClient.demandClock();
            if (clock != null) factor = clock.factorNow(itemId, count);
        }
        factor *= populationFactor();
        return Math.max(0.1, Math.min(3.0, factor));
    }

    /** The crowd on the server right now against the usual crowd; 1.0 when unknown or switched off. */
    private static double populationFactor() {
        if (dev.doughbay.fabric.Tuning.get("demand.population") < 0.5) return 1.0;
        dev.doughbay.fabric.PopulationModel model = dev.doughbay.fabric.DoughBayClient.population();
        return model == null ? 1.0 : model.factorNow();
    }

    /** The same opportunity ranked and timed for this hour's demand. */
    private static Opportunity timed(Opportunity o, double factor) {
        if (factor == 1.0) return o;
        List<String> reasons = new ArrayList<>(o.reasons());
        reasons.add(String.format(java.util.Locale.ROOT, "Demand this hour x%.1f", factor));
        return new Opportunity(o.listing(), o.stats(), o.buyPrice(), o.recommendedSellPrice(), o.expectedNetProfit(),
                o.expectedRoiPercent(), Math.min(24.0, o.estimatedHoldHours() / Math.max(0.1, factor)),
                o.saleProbability(), o.confidence(), o.score() * factor, reasons);
    }

    private static dev.doughbay.fabric.RivalIntel.Snapshot rivals() {
        dev.doughbay.fabric.RivalIntel intel = dev.doughbay.fabric.DoughBayClient.rivalIntel();
        return intel == null ? null : intel.snapshot();
    }

    private static boolean rivalCrowded(String itemId, int count) {
        dev.doughbay.fabric.RivalIntel.Snapshot r = rivals();
        return r != null && r.crowded(itemId, count);
    }

    private static boolean rivalProven(String itemId, int count) {
        dev.doughbay.fabric.RivalIntel.Snapshot r = rivals();
        return r != null && r.provenFlips(itemId, count) >= PROVEN_MIN_RIVAL_FLIPS();
    }

    private static boolean quietField() {
        dev.doughbay.fabric.RivalIntel.Snapshot r = rivals();
        return r != null && r.quietField();
    }

    /**
     * Underdog: shadow the rivals' proven flips. Buy where they buy, at their
     * buy price, and list a notch under their completed sale price. The
     * pull-backs walk it down from there. Only markets with enough
     * reconstructed flips and margin qualify, one listing per market at a
     * time, and the item lists still apply.
     */
    private List<Opportunity> underdogCandidates(long now, long snapshotAt, boolean follow) {
        long stamp = snapshotAt > 0 ? Math.min(now, snapshotAt) : now;
        if (dev.doughbay.fabric.Tuning.get("underdog.enabled") < 0.5) return List.of();
        dev.doughbay.fabric.RivalIntel.Snapshot r = rivals();
        if (r == null || r.shadows().isEmpty()) return List.of();
        double undercut = dev.doughbay.fabric.Tuning.get("underdog.undercut_pct") / 100.0;
        // Twice the shadowed markets while we are mirroring a busy field.
        int max = (int) dev.doughbay.fabric.Tuning.get("underdog.max_targets") * (follow ? 2 : 1);
        List<Opportunity> out = new ArrayList<>();
        for (dev.doughbay.fabric.RivalIntel.ShadowMarket m : r.shadows()) {
            if (out.size() >= max) break;
            if (!dev.doughbay.fabric.Tuning.itemAllowed(m.itemId())) continue;
            if (openOfMarket(m.itemId(), m.count()) > 0) continue;
            long sell = (long) Math.floor(m.medianSale() * (1.0 - undercut));
            long buy = Math.min(m.medianBuy(), Math.min(effectivePurchaseCap(), effectiveSpendCap() - committedSpend));
            if (buy <= 0 || sell <= buy) continue;
            double net = auctionFees.netSale(sell);
            double profit = net - buy;
            if (profit <= 0) continue;
            StackBucket bucket = StackBucket.of(m.count());
            String key = "underdog:" + m.itemId() + ":" + m.count() + ":" + r.refreshedAt();
            Listing signal = new Listing(key, stamp, "", "underdog", m.itemId(), m.itemId(), m.count(), buy, null);
            MarketStats stats = new MarketStats(m.itemId(), bucket, 24 * 3_600_000L, m.flips(), 0, 1, stamp,
                    m.medianBuy(), m.medianSale(), m.medianSale(), m.medianSale(), m.medianSale(),
                    m.flips() / 24.0, 0, 0, 0.8, stamp);
            double holdHours = m.medianTurnaroundMillis() > 0 ? m.medianTurnaroundMillis() / 3_600_000.0 : 1.0;
            out.add(new Opportunity(signal, stats, buy, sell, profit, profit / buy * 100.0,
                    Math.min(holdHours, 3.5), 0.8, 0.8, 1_000 + m.marginPercent(),
                    List.of("Underdog: " + m.rival() + " flips this at " + m.medianBuy() + " -> " + m.medianSale())));
        }
        return out;
    }

    private final java.util.Map<String, Long> underdogLogAt = new java.util.HashMap<>();

    /** Why a shadowed market did not make the watch list, at most once a minute per market. */
    private void logUnderdog(Opportunity o, String why, long now) {
        String key = o.listing().itemId() + "|" + o.listing().itemCount();
        if (now - underdogLogAt.getOrDefault(key, 0L) < 60_000) return;
        underdogLogAt.put(key, now);
        LOGGER.info("DoughBay underdog: {} x{} (buy {} -> list {}) {}", o.listing().itemId(),
                o.listing().itemCount(), o.buyPrice(), o.recommendedSellPrice(), why);
    }

    private static boolean isUnderdog(Opportunity o) {
        return o != null && o.listing() != null && o.listing().listingKey().startsWith("underdog:");
    }

    /** A filled box the watch page priced by its contents, kept until it is clicked. */
    private record BoxCandidate(dev.doughbay.fabric.ItemDescriptor descriptor, long value, long ceiling, String breakdown) {
    }

    private final java.util.Map<String, BoxCandidate> boxes = new java.util.HashMap<>();
    private volatile MarketWatcher.Snapshot lastSnapshot;

    /**
     * Prices a shulker box or bundle on the watch page by the ledger's
     * medians for every stack inside it. Only a complete, confident
     * valuation is tradable; the ceiling is the contents value less fees,
     * minimum profit and minimum ROI, within the usual caps and pools.
     */
    private long[] priceContainer(ItemStack stack) {
        if (dev.doughbay.fabric.Tuning.get("boxes.enabled") < 0.5) return null;
        MarketWatcher.Snapshot snapshot = lastSnapshot;
        if (snapshot == null || policy == null || stack == null) return null;
        dev.doughbay.fabric.ItemDescriptor d = dev.doughbay.fabric.ItemDescriptor.of(stack);
        if (!d.isContainer() || d.count() != 1) return null;
        if (!dev.doughbay.fabric.Tuning.itemAllowed(d.itemId())) return null;
        // What is inside obeys the same lists as the box: a shulker of map art
        // or potions is not a commodity box, whatever the medians say.
        if (!contentsAllowed(d)) return null;
        dev.doughbay.fabric.ComponentValuer.Valuation v = dev.doughbay.fabric.ComponentValuer.value(d, snapshot);
        if (!v.complete() || v.confidence() < dev.doughbay.fabric.Tuning.get("boxes.min_confidence_pct") / 100.0) return null;
        long value = v.total();
        if (value < (long) dev.doughbay.fabric.Tuning.get("boxes.min_value")) return null;
        double net = auctionFees.netSale(value);
        double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
        double minimumRoi = Math.max(policy.minimumRoiPercent(), riskConfig.minimumRoiPercent()) / 100.0;
        long ceiling = Math.min((long) Math.floor(net - minimumProfit), (long) Math.floor(net / (1.0 + minimumRoi)));
        ceiling = Math.min(ceiling, Math.min(effectivePurchaseCap(), effectiveSpendCap() - committedSpend));
        if (ceiling <= 0) return null;
        Tier tier = tierOf(ceiling);
        if (openOfTier(tier) >= tierSlots(tier)) return null;
        if (openOfMarket(d.itemId(), 1) >= MAX_OPEN_PER_MARKET()) return null;
        String key = d.itemId() + "#" + d.hash();
        // One of each contents: a vendor's wall of identical boxes is one
        // market, and five of the same box was a million tied up in TNT carts.
        for (Position p : openListings) if (p.itemKey().equals(key)) return null;
        if (trackedPosition != null && key.equals(trackedPosition.itemKey())) return null;
        int openBoxes = 0;
        for (Position p : openListings) if (p.itemKey().indexOf('#') >= 0) openBoxes++;
        if (openBoxes >= (int) dev.doughbay.fabric.Tuning.get("boxes.max_open")) return null;
        synchronized (boxes) {
            if (boxes.size() > 200) boxes.clear();
            boxes.put(key, new BoxCandidate(d, value, ceiling, v.summary()));
        }
        return new long[] {ceiling, value};
    }

    private static boolean contentsAllowed(dev.doughbay.fabric.ItemDescriptor d) {
        for (dev.doughbay.fabric.ItemDescriptor inner : d.contents()) {
            if (!dev.doughbay.fabric.Tuning.itemAllowed(inner.itemId())) return false;
            if (inner.isContainer() && !contentsAllowed(inner)) return false;
        }
        return true;
    }

    /** The opportunity a clicked box stands for, or null when the key is not a priced box. */
    private Opportunity boxOpportunity(String key, long now) {
        if (key == null || key.indexOf('#') < 0) return null;
        BoxCandidate b;
        synchronized (boxes) {
            b = boxes.get(key);
        }
        if (b == null) return null;
        Listing listing = new Listing("watch:box:" + key, now, "", "market", key, b.descriptor().itemId(),
                1, b.ceiling(), null);
        MarketStats stats = new MarketStats(key, StackBucket.of(1), 24 * 3_600_000L, 1, 0, 1, now,
                b.value(), b.value(), b.value(), b.value(), b.value(), 0, 0, 0, 0.8, now);
        double net = auctionFees.netSale(b.value());
        double profit = net - b.ceiling();
        return new Opportunity(listing, stats, b.ceiling(), b.value(), profit,
                profit / Math.max(1, b.ceiling()) * 100.0, 0.5, 0.8, 0.8, 0,
                List.of("Box priced by contents: " + b.breakdown()));
    }

    private static String rivalNote() {
        dev.doughbay.fabric.RivalIntel.Snapshot r = rivals();
        if (r == null || r.refreshedAt() == 0 || r.rivals().isEmpty()) return "";
        return r.quietField() ? " · rivals quiet" : " · rivals " + r.activeNow() + "/" + r.rivals().size() + " on";
    }
    /** How many listings of one item and stack size may be up at once. */
    private static int MAX_OPEN_PER_MARKET() {
        return (int) dev.doughbay.fabric.Tuning.get("buy.max_open_per_market");
    }

    /**
     * How reliably a whole stack of this market clears at a profit: the net
     * margin, weighted by how sure the sale is (confidence x sale probability),
     * by sustained volume (diminishing returns), and against volatility. A
     * strict superset of the rank-by-value term - it just adds the "reliably"
     * and "sustained" factors. A market without statistics sinks to the back.
     */
    private double reliabilityScore(Opportunity o) {
        MarketStats s = o.stats();
        if (s == null) return 0;
        double margin = Math.max(0, o.expectedNetProfit());
        double reliable = o.confidence() * Math.max(0.05, o.saleProbability());
        double liquidity = Math.log1p(Math.max(0, s.salesPerHour()));
        double steadiness = 1.0 / (1.0 + Math.max(0, s.robustVolatility()));
        return margin * reliable * liquidity * steadiness;
    }

    /**
     * Concentration for the reliability ranking: from an already-sorted list,
     * keep only the top {@code topN} distinct markets so the book fills with
     * the proven movers instead of grazing everything that passed the gates.
     * Underdog, mirror and order-backed rows are never trimmed - they hold
     * reserved slots and widen the book on purpose. The per-market depth cap is
     * unchanged, so a market that fills leaves room for the next-ranked one on
     * the following cycle. Mutates the list in place.
     */
    private void trimToTopMarkets(List<Opportunity> sorted, int topN) {
        if (topN <= 0) return;
        java.util.Set<String> kept = new java.util.LinkedHashSet<>();
        java.util.Iterator<Opportunity> it = sorted.iterator();
        while (it.hasNext()) {
            Opportunity o = it.next();
            if (isUnderdog(o) || isMirror(o) || isOrderBacked(o)) continue;
            String item = o.listing().itemId();
            if (kept.size() >= topN && !kept.contains(item)) {
                it.remove();
            } else {
                kept.add(item);
            }
        }
    }
    /** Before every sell, the cheapest live ask is read and undercut by this much. */
    private static final double UNDERCUT_PERCENT = 1.0;
    /**
     * Off. Tried live on 2026-09-02: the visible asks are dominated by ghost
     * rows nobody can buy, so undercutting them sold golden apples at 19,100
     * that had been selling at 25,000, and pistons at the floor. Buyers here
     * pay the completed-sale median; the time-based reprice handles markets
     * that have really fallen.
     */
    private static final boolean UNDERCUT_ENABLED = false;
    private boolean probePending;
    private boolean probeStarted;
    /** Cheapest live ask from other sellers per "item|count", with when it was read. */
    private final java.util.Map<String, long[]> liveAsks = new java.util.HashMap<>();
    private static final long LIVE_ASK_TTL_MILLIS = 30 * 60 * 1000;

    /**
     * The buy ceiling once the live market is known: whatever the statistics
     * say, a fill has to be resold under the cheapest ask others show, and
     * that resale must still clear cost plus the minimum profit.
     */
    private long liveAwareCeiling(String itemId, int count, long statisticalCeiling, long now) {
        long[] ask = liveAsks.get(itemId + "|" + count);
        // Disabled: ghost rows make the visible median an unreliable resale
        // estimate, and the completed-sale statistics have been the better
        // guide to what a fill resells for. Kept for the log and the display.
        if (true || ask == null || now - ask[1] > LIVE_ASK_TTL_MILLIS) return statisticalCeiling;
        double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
        long resale = ask[0] - Math.max(1, Math.round(ask[0] * UNDERCUT_PERCENT / 100.0));
        long byLiveAsk = (long) Math.floor(auctionFees.netSale(resale) - minimumProfit);
        return Math.min(statisticalCeiling, byLiveAsk);
    }
    private static long REPRICE_RETRY_MILLIS() {
        return dev.doughbay.fabric.Tuning.millis("reprice.retry_min");
    }
    /**
     * Set whenever parked listings are adopted from durable state: sales
     * that happened while the mod was offline left no chat notice, so the
     * own-listings page is read once before trading to settle them and to
     * count the slots other, hand-made listings occupy.
     */
    private boolean slotAuditPending;
    /**
     * Whether the current pause was asked for (END key, Stop button,
     * evasion) rather than raised by the session itself. Only the latter
     * may be resumed by the watchdog.
     */
    private boolean pausedByPlayer;
    /**
     * True only between the player's Start or Resume and their Stop or End.
     * Nothing automatic (resuming a pause, listing a bought stack found in
     * the inventory, adopting a paused buy) runs while it is false; after a
     * restart it is false until the player presses Resume, and the stuck
     * item is dealt with as part of that resume.
     */
    private boolean armed;

    /** The balance the market watcher last reported, or -1 before the first read. */
    public synchronized long knownBalance() {
        return lastKnownBalance;
    }

    /** The last balance recorded before this launch, or -1 when there is none. */
    private long balanceBeforeStart = -1;

    public synchronized void setBalanceBeforeStart(long balance) {
        balanceBeforeStart = balance;
    }

    /**
     * The yardstick for offline sales: the balance at the last slot check,
     * carried forward by every receipted sale and every purchase and payout
     * since. Whatever the bank holds beyond that is money from sales the bot
     * never saw a notice for, and only that much may be booked as sold.
     */
    private long auditBalance = -1;
    private long auditAt;
    private long spentSinceAudit;
    private long salesSinceAudit;
    private java.nio.file.Path auditStore;
    private final List<Position> recentlyReturned = new ArrayList<>();

    public synchronized void setAuditBaseline(long balance, long at, long spentSince, long salesSince) {
        auditBalance = balance;
        auditAt = at;
        spentSinceAudit = spentSince;
        salesSinceAudit = salesSince;
    }

    public synchronized void setAuditStore(java.nio.file.Path path) {
        auditStore = path;
    }

    /** Positions the ledger closed as returned to the player lately; the slot check re-adopts any still on the page. */
    public synchronized void setRecentlyReturned(List<Position> positions) {
        recentlyReturned.clear();
        if (positions != null) recentlyReturned.addAll(positions);
    }

    private void rebaseAudit(long now) {
        if (lastKnownBalance < 0) return;
        auditBalance = lastKnownBalance;
        auditAt = now;
        spentSinceAudit = 0;
        salesSinceAudit = 0;
        if (auditStore != null) {
            try {
                java.nio.file.Files.writeString(auditStore, now + "=" + lastKnownBalance);
            } catch (java.io.IOException e) {
                LOGGER.warn("DoughBay could not save the audit baseline: {}", e.toString());
            }
        }
    }

    public synchronized boolean armed() {
        return armed;
    }

    /**
     * Whether the bot is driving its own container screens, for quiet-mode.
     * Armed alone missed a real gap: a recovered session reads the order house
     * (and the server re-opens the auction page) while it is running but before
     * it has re-armed, so those pages flashed on screen even though nobody was
     * playing. Any active (non-paused, non-stopped) session is working its own
     * containers; a genuinely paused or stopped one is left visible to inspect.
     */
    public synchronized boolean drivingOwnContainers() {
        return armed || isRunning();
    }
    /**
     * The one pocket the trading never reaches into.
     *
     * <p>The shelf hands out an ender chest, the chest lands in the pack, and
     * the desk sees a stack of a thing it trades - because it is one: they
     * were selling at a twenty-nine per cent margin the same afternoon. So it
     * did the right thing with it and listed the key to the shelf.
     *
     * <p>Refusing to trade ender chests would fix it and cost a good market.
     * Reserving the slot costs nothing: the desk keeps buying and selling them
     * everywhere else, and simply never looks in here. It is the same trick
     * the hotbar already uses to keep a working slot clear.
     */
    private static int stashPocketSlot() {
        return (int) Math.round(dev.doughbay.fabric.Tuning.get("stash.pocket_slot"));
    }

    /** Whether an inventory slot belongs to the bot's own working kit. */
    private boolean reservedSlot(int index) {
        return index == policy.reservedHotbarSlot()
                || (dev.doughbay.fabric.StashDesk.enabled() && index == stashPocketSlot());
    }

    private static long INTERNAL_PAUSE_RESUME_MILLIS() {
        return dev.doughbay.fabric.Tuning.millis("buy.internal_pause_resume_sec");
    }
    private int otherListedSlots;
    /** Listings the server reported at the last slot audit, and when it said so. */
    private int serverListedSlots;
    private long serverListedAt;
    /** What the ledger held when the server last answered, so movement since can be added to it. */
    private int ledgerAtAudit;
    /** Sales and listings seen since that answer, matched or not; the auction house counts both. */
    private int soldSinceAudit;
    private int listedSinceAudit;
    /**
     * Pull-backs seen since that answer. A cancelled listing frees a slot the
     * same as a sold one, and leaving it out was why the panel stuck at full:
     * the pull-back took nothing off the count and the relist that followed
     * put one on, so every reprice added a slot that did not exist. A few
     * hundred of those an hour and the number pins at the cap, where the
     * clamp hides it and no single sale can bring it back down.
     */
    private int cancelledSinceAudit;
    /** Rows on our own page that no position claimed at the previous slot check. */
    private java.util.Map<String, Integer> unmanagedLastAudit = new java.util.HashMap<>();
    /**
     * The server's own word that the book is full, which beats every count we
     * can assemble.
     *
     * <p>The audit walks the pages and reports eighty-nine of ninety; the
     * server refuses the ninetieth. One of those is a reading and the other is
     * the rule being enforced, so the rule wins. Waiting on a timer instead
     * meant asking again every thirty seconds and being told no every thirty
     * seconds, which is the same loop at a slower speed.
     *
     * <p>It is cleared by the only two things that actually free a slot: a
     * sale, and a listing pulled back. Not by a recount - a recount is the
     * thing that was wrong.
     */
    private boolean bookFull;
    private long bookFullAt;
    /** However wrong everything else goes, the book is not held shut longer than this. */
    private static final long BOOK_FULL_MAX_HOLD = 10 * 60_000L;
    private Position repricing;
    private long repricingNewPrice;
    private long repriceStartedTick;
    /** Wall-clock start of the current pull-back, so a hung one can be abandoned. */
    private long repriceStartedAtMillis;
    /** A pull-back that has not finished in this long is abandoned and the book recounted. */
    private static final long REPRICE_DEADLINE_MILLIS = 90_000L;
    private final java.util.Map<Long, Long> repriceAttemptedAt = new java.util.HashMap<>();
    /** The most the screen-chosen row may cost for the retained candidate. */
    private long buyCeiling;
    /** Markets whose live rows recently failed to clear the ceiling; not re-tried until then. */
    private final java.util.Map<String, Long> marketRestUntil = new java.util.HashMap<>();
    private static final long MARKET_REST_MILLIS = 2 * 60 * 1000;
    /** What the last hunt of each market found, for the Opportunities tab. */
    private final java.util.Map<String, String> huntNotes = new java.util.HashMap<>();
    /** Where market rests are kept across relaunches; null keeps them in memory only. */
    private java.nio.file.Path restStore;

    /**
     * Market rests survive relaunches. Without this every restart sent the
     * session back to the same unaffordable top-ranked markets first.
     */
    public synchronized void setRestStore(java.nio.file.Path path) {
        restStore = path;
        if (path == null || !java.nio.file.Files.exists(path)) return;
        try {
            long now = System.currentTimeMillis();
            for (String line : java.nio.file.Files.readAllLines(path)) {
                int eq = line.lastIndexOf('=');
                if (eq <= 0) continue;
                long until = Long.parseLong(line.substring(eq + 1).trim());
                if (until > now) marketRestUntil.put(line.substring(0, eq).trim(), until);
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("DoughBay could not read market rests: {}", e.toString());
        }
    }

    private void restMarket(String itemId, long untilMillis) {
        marketRestUntil.put(itemId, untilMillis);
        if (restStore == null) return;
        try {
            long now = System.currentTimeMillis();
            List<String> lines = new ArrayList<>();
            for (var entry : marketRestUntil.entrySet()) {
                if (entry.getValue() > now) lines.add(entry.getKey() + "=" + entry.getValue());
            }
            java.nio.file.Files.write(restStore, lines);
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.warn("DoughBay could not save market rests: {}", e.toString());
        }
    }
    private static final long MARKET_LONG_REST_MILLIS = 30 * 60 * 1000;
    /** A market with no row of the wanted stack size on the page at all. */
    private static final long MARKET_ABSENT_REST_MILLIS = 10 * 60 * 1000;
    /** What the verified purchase actually cost, from the driver's terminal event. */
    private long verifiedBuyPrice;
    /** Controller tick at which the driver reported the purchase verified. */
    private long buyVerifiedTick;
    /** How long the bought stack may take to show up in the inventory after the receipt. */
    private static final int ARRIVAL_GRACE_TICKS = 100;
    private volatile RiskConfig riskConfig = RiskConfig.defaults();
    private long cooldownUntilMillis;
    private long stateChangedAtMillis;
    private String detail = "Automation session is stopped";
    private static final Logger LOGGER = LoggerFactory.getLogger("doughbay-automation");
    private String lastLoggedDetail = "";
    private long nextPositionId = Math.max(1, System.currentTimeMillis());
    private long candidateCoverageBoundaryMillis;

    private String firstCoveragePhase = "";
    private ActiveBookEvidence.Analysis firstCoverageBook;
    private long firstCoverageCompletedAt;
    private boolean preListMarketVerified;
    private long preListVerifiedScanStartedAt;
    private long preListValuationCalculatedAt;

    private State pausedFromState = State.STOPPED;
    private boolean pauseResumable;

    private long preparationStartedAtMillis;
    private long controllerTick;
    private int originalSelectedSlot = -1;
    private int sourceInventorySlot = -1;
    /** What the reserved slot held before the stack was swapped in; it lands in the stack's old slot. */
    private ItemStack displacedFromReserved = ItemStack.EMPTY;
    private long swapIssuedAtTick = -1;
    private final PreparedInventoryClose preparedInventoryClose = new PreparedInventoryClose();
    private boolean targetAbsentBeforeBuy;
    private List<ItemStack> inventoryBeforeBuy = List.of();
    private boolean buyTerminalSucceeded;
    private int purchasedInventorySlot = -1;
    private long purchaseStableSinceTick = -1;
    // Piece-out (list.singles): peeling one unit off a stack into a free slot.
    private static final int PEEL_ABORT = 9;   // sentinel stage: return the cursor and give up
    private long singlePeelPositionId = 0;
    private long singlePeelFailedPositionId = 0;
    private int singlePeelStage = -1;          // -1 idle; 0/1/2 clicks; 3 verify; PEEL_ABORT recovering
    private int singlePeelSourceMenuSlot = -1;
    private int singlePeelFreeMenuSlot = -1;
    private int singlePeelFreeInvSlot = -1;
    private int singlePeelRemainder = 0;
    /**
     * The rest of a stack a single was just peeled off. It leaves tracking when
     * the position shrinks to one, and the next inventory sweep adopts it again;
     * without this the sweep took it for a fresh order fill - announced it as
     * "delivered", booked it at the bid price instead of what it cost, and
     * counted its cost against the session spend a second time.
     */
    private String peelLeftItem;
    private int peelLeftCount;
    private long peelLeftUnitCost;
    private long peelLeftAt;
    private long singlePeelClickTick = -1;
    private int singlePeelAbortClicks = 0;      // return-to-source tries before handing to the stow backstop
    private long cursorStowClickTick = -1;      // paces the stray-cursor stow
    private List<ItemStack> inventoryBeforeList = List.of();
    private boolean listTerminalSucceeded;
    private long listStableSinceTick = -1;
    private DonutSaleMessageParser.SaleNotice bufferedSaleNotice;
    private boolean listingInventoryVerified;
    private long listInventoryProofAtMillis;
    private long listingReconciliationStartedAt;
    private long lastActiveListingScanStartedAt;
    private long saleNoticeBaselineScanStartedAt;
    private long saleNoticeObservedAtMillis;
    private String boundListingKey = "";
    private String reconciliationStatus = "Not started";

    public AutomationSessionController(AutomatedExecutionDriver driver) {
        if (driver == null) throw new IllegalArgumentException("driver is required");
        this.driver = driver;
    }

    /** Installs the non-blocking persistence boundary before live automation starts. */
    public synchronized void setPersistence(AutomationPersistencePort persistence) {
        if (isRunning() || sessionOpen || hasFinancialExposure()) {
            throw new IllegalStateException("cannot replace persistence during a live session");
        }
        this.persistence = persistence;
        recoveryApplied = false;
        persistenceStatus = persistence == null
                ? "Automation persistence is unavailable"
                : "Loading durable automation recovery";
    }

    /** Independent opt-in; it cannot widen the driver's own live allowlist. */
    public synchronized void setContinuousAuthorization(boolean enabled,
                                                        List<String> servers) {
        continuousAuthorizationEnabled = enabled;
        continuousAuthorizedServers = normalizeServers(servers);
        if (!enabled && isRunning()) {
            pauseInternal("Continuous automation authorization was disabled");
        }
    }

    /** Sale notices release capital, so their independent server trust must match too. */
    public synchronized void setSaleNotificationTrustedServers(List<String> servers) {
        saleNotificationTrustedServers = normalizeServers(servers);
        if (isRunning()) {
            String blocker = authorizationBlocker(Minecraft.getInstance());
            if (blocker != null) pauseInternal(blocker);
        }
    }

    /** Live sessions cannot assume zero fees unless the exact policy was confirmed. */
    public synchronized void setAuctionFeePolicy(boolean confirmed, FeeConfig fees) {
        boolean valid = validFees(fees);
        auctionFees = valid ? fees : FeeConfig.zero();
        auctionFeesConfirmed = confirmed && valid;
        if (!auctionFeesConfirmed && isRunning()) {
            pauseInternal("Auction fee/tax policy is missing, invalid, or not confirmed");
        }
    }

    /** Pre-buy and pre-list revalidation judge on the same risk rules the detector used. */
    public synchronized void setRiskConfig(RiskConfig risk) {
        riskConfig = risk == null ? RiskConfig.defaults() : risk;
        revalidator = new ContinuousOpportunityRevalidator(riskConfig);
    }

    /** The last hunt result for a market, or "" when it has not been hunted this session. */
    public synchronized String huntNote(String itemId) {
        return huntNotes.getOrDefault(itemId, "");
    }

    /** A candidate synthesised from market statistics rather than an API listing. */
    private static boolean isMarketCandidate(Opportunity opportunity) {
        return opportunity != null && opportunity.listing() != null
                && opportunity.listing().listingKey() != null
                && opportunity.listing().listingKey().startsWith("market:");
    }

    /**
     * A market signal's ceiling re-capped to this session's per-purchase cap
     * and remaining spend; null when the caps leave nothing sensible.
     */
    private Opportunity cappedForSession(Opportunity signal) {
        long affordable = Math.min(effectivePurchaseCap(), effectiveSpendCap() - committedSpend);
        long sell = signal.recommendedSellPrice();
        if (signal.buyPrice() <= affordable) return signal;
        if (affordable <= 0 || affordable < sell * 0.6) return null;
        double net = auctionFees.netSale(sell);
        double profit = net - affordable;
        return new Opportunity(new Listing(signal.listing().listingKey(), signal.listing().observedAt(),
                signal.listing().sellerUuid(), signal.listing().sellerName(),
                signal.listing().itemKey(), signal.listing().itemId(),
                signal.listing().itemCount(), affordable, signal.listing().timeLeftMillis()),
                signal.stats(), affordable, sell, profit, profit / affordable * 100.0,
                signal.estimatedHoldHours(), signal.saleProbability(), signal.confidence(),
                signal.score(), signal.reasons());
    }

    /** Retained for reference; signals now come from the shared market feed. */
    @SuppressWarnings("unused")
    private List<Opportunity> marketCandidates(MarketWatcher.Snapshot snapshot, long now) {
        if (snapshot == null || snapshot.demo()) return List.of();
        double minimumConfidence = Math.max(policy.minimumConfidence(), riskConfig.minimumConfidence());
        List<Opportunity> out = new ArrayList<>();
        for (MarketStats stats : snapshot.markets()) {
            if (stats == null || stats.bucket() == StackBucket.OTHER || !stats.hasPrices()) continue;
            if (stats.itemKey().indexOf('#') >= 0) continue;
            if (stats.sampleCount() < riskConfig.minimumSamples()) continue;
            if (!Double.isFinite(stats.confidence()) || stats.confidence() < minimumConfidence) continue;
            if (!Double.isFinite(stats.robustVolatility())
                    || stats.robustVolatility() > riskConfig.maximumVolatility()) continue;
            if (!Double.isFinite(stats.trend()) || (riskConfig.skipFallingMarkets() && stats.trend() < 0)) continue;
            if (stats.newestSaleAt() <= 0
                    || now - stats.newestSaleAt() > riskConfig.maximumNewestSaleAgeMillis()) continue;
            if (stats.calculatedAt() <= 0 || stats.calculatedAt() > snapshot.updatedAt()) continue;
            double holdHours = stats.salesPerHour() > 0 ? 1.0 / stats.salesPerHour() : Double.MAX_VALUE;
            if (holdHours * 3_600_000.0 > policy.maximumHoldMillis()
                    || holdHours > riskConfig.maximumExpectedHoldHours()) continue;

            // The resale target is the median completed sale, not the quick
            // band: rows under the quick band are gone within a second of
            // appearing, so hunting for them starves the session. Buying at
            // the going rate and relisting at the median is the trade that
            // actually fills here, with parallel listings carrying the wait.
            long sell = (long) Math.floor(stats.weightedMedian());
            if (sell <= 0) continue;
            double net = auctionFees.netSale(sell);
            double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
            double minimumRoi = Math.max(policy.minimumRoiPercent(), riskConfig.minimumRoiPercent()) / 100.0;
            long economic = Math.min((long) Math.floor(net - minimumProfit),
                    (long) Math.floor(net / (1.0 + minimumRoi)));
            long affordable = Math.min(effectivePurchaseCap(), effectiveSpendCap() - committedSpend);
            // When the caps, not the profit rules, set the ceiling, and they
            // sit far below what the market actually trades at, no live row
            // will ever qualify; hunting it only burns the page budget.
            if (economic > affordable && affordable < sell * 0.6) continue;
            long ceiling = Math.min(economic, affordable);
            if (ceiling <= 0 || ceiling >= sell) continue;

            int count = stats.bucket().exactCount();
            String key = "market:" + stats.itemKey() + ":" + stats.bucket().label() + ":" + stats.calculatedAt();
            Listing signal = new Listing(key, stats.calculatedAt(), "", "market",
                    stats.itemKey(), stats.itemKey(), count, ceiling, null);
            double profit = net - ceiling;
            double roi = profit / ceiling * 100.0;
            double score = stats.confidence() * Math.log1p(Math.max(0, stats.salesPerHour()));
            out.add(new Opportunity(signal, stats, ceiling, sell, profit, roi, holdHours,
                    Math.min(1.0, stats.confidence()), stats.confidence(), score,
                    List.of("Market signal: buy any live row at or under the ceiling")));
        }
        return out;
    }

    /** API listings of one market, used as resale competition only. */
    private static List<Listing> marketListings(MarketWatcher.Snapshot snapshot, String itemId) {
        if (snapshot == null || itemId == null) return List.of();
        return snapshot.activeListings().stream()
                .filter(listing -> listing != null && itemId.equals(listing.itemId()))
                .toList();
    }

    /**
     * The most a row may cost on the auction screen and still clear policy
     * at the candidate's resale target: minimum profit, minimum ROI, the
     * per-purchase cap, and what is left of the session spend cap.
     *
     * <p>The candidate's own API price is not a bound. The API listing is a
     * signal that the market is attractive, not the row that will be bought;
     * that row is whichever live one is cheapest under this ceiling.
     */
    private long ceilingFor(Opportunity candidate) {
        double net = auctionFees.netSale(candidate.recommendedSellPrice());
        double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
        double minimumRoi = Math.max(policy.minimumRoiPercent(), riskConfig.minimumRoiPercent()) / 100.0;
        long byProfit = (long) Math.floor(net - minimumProfit);
        long byRoi = (long) Math.floor(net / (1.0 + minimumRoi));
        long remaining = effectiveSpendCap() - committedSpend - bidsOut();
        // A trade this client cannot afford but the hive can: the money is
        // asked for here, and the trade waits for it rather than being
        // dropped. The ceiling only widens once the loan has actually landed.
        remaining += borrowedHeadroom(Math.min(byProfit, byRoi) - remaining);
        long ceiling = Math.min(Math.min(byProfit, byRoi), Math.min(effectivePurchaseCap(), remaining));
        if (!isMirror(candidate) && !isOrderBacked(candidate)
                && rivalCrowded(candidate.listing().itemId(), candidate.listing().itemCount())) {
            ceiling = Math.round(ceiling * (1.0 - CROWDED_CEILING_CUT_PERCENT() / 100.0));
        }
        return ceiling;
    }

    /** The loan this client is waiting on or spending, 0 when none. */
    private volatile long activeLoanId;
    private volatile long activeLoanAmount;
    private long loanAskedAt;

    public long activeLoanId() {
        return activeLoanId;
    }

    /**
     * Money borrowed from another client of the hive, available to spend right
     * now. Asking is cheap and idempotent; the headroom stays zero until a
     * sibling has actually sent the money.
     */
    private long borrowedHeadroom(long shortfall) {
        dev.doughbay.fabric.HiveBank bank = dev.doughbay.fabric.DoughBayClient.hiveBank();
        if (bank == null || shortfall <= 0) return 0;
        // Only a client genuinely out of money may borrow. Running into your
        // own spend cap with thirty million in the bank is not being short of
        // cash, and borrowing there would drain a sibling to get around a
        // limit this client set for itself.
        //
        // The comparison is against the amount that would actually be asked
        // for, not the raw shortfall: a shortfall of twenty million against a
        // three million loan cap let a rich client through the earlier test.
        long ask = Math.min(shortfall, Math.round(dev.doughbay.fabric.Tuning.get("hive.max_loan")));
        if (ask <= 0 || lastKnownBalance < 0 || lastKnownBalance > ask * 2) return 0;
        if (activeLoanId > 0) {
            return bank.funded(activeLoanId) ? activeLoanAmount : 0;
        }
        long now = System.currentTimeMillis();
        if (now - loanAskedAt < 60_000L) return 0;
        loanAskedAt = now;
        long id = bank.request(ask, "short of capital for a trade");
        if (id > 0) {
            activeLoanId = id;
            activeLoanAmount = ask;
            persistence.setLoanId(id);
        }
        return 0;
    }

    /** Immutable request that the client forwards to MarketWatcher off-thread. */
    public synchronized CoverageRequest coverageRequest() {
        if (manualResolutionFirstConfirmedAt > 0
                && manualResolutionCoverageBoundary > 0) {
            String itemId = recoveredExposureItemId();
            long boundary = manualResolutionEvidenceReady
                    ? manualResolutionEvidenceAt : manualResolutionCoverageBoundary;
            if (!manualResolutionEvidenceReady
                    && "MANUAL_RECOVERY".equals(firstCoveragePhase)
                    && firstCoverageCompletedAt > 0) {
                boundary = Math.max(boundary, firstCoverageCompletedAt);
            }
            return itemId.isBlank()
                    ? CoverageRequest.none()
                    : new CoverageRequest(itemId, boundary);
        }
        if (state == State.LISTING && !listCommandStarted
                && trackedPosition != null && listIntentCoverageBoundaryMillis > 0) {
            return new CoverageRequest(
                    baseItemId(trackedPosition.itemKey()), listIntentCoverageBoundaryMillis);
        }
        if (!isRunning()) return CoverageRequest.none();
        String itemId = "";
        long boundary = 0;
        String phase = "";
        if (state == State.LISTING && trackedPosition != null
                && listingInventoryVerified) {
            itemId = baseItemId(trackedPosition.itemKey());
            boundary = bufferedSaleNotice == null
                    ? listInventoryProofAtMillis
                    : Math.max(listInventoryProofAtMillis, saleNoticeObservedAtMillis);
            phase = postListCoveragePhase();
        } else if (state == State.MONITORING && trackedPosition != null
                && bufferedSaleNotice != null) {
            itemId = baseItemId(trackedPosition.itemKey());
            boundary = Math.max(saleNoticeObservedAtMillis,
                    saleNoticeBaselineScanStartedAt);
            phase = postSaleCoveragePhase();
        }
        if (itemId.isBlank() || boundary <= 0) return CoverageRequest.none();
        if (phase.equals(firstCoveragePhase) && firstCoverageCompletedAt > 0) {
            boundary = Math.max(boundary, firstCoverageCompletedAt);
        }
        return new CoverageRequest(itemId, boundary);
    }

    /** Compatibility/accessibility helper for simple client wiring and diagnostics. */
    public synchronized String requestedCoverageItemId() {
        return coverageRequest().itemId();
    }

    /**
     * What a fresh install is missing.
     *
     * <p>Every price this bot trusts comes from completed sales it has
     * collected itself. On a new machine the ledger is empty, so the first
     * minutes are for watching: the collector pulls the sale history through
     * the API while nothing is bought. This reports how far along that is,
     * and the session refuses to start until enough markets have a price
     * with real samples behind them.
     */
    public record WarmUp(boolean ready, int marketsReady, int marketsNeeded, int sales, long watchedMillis, String detail) {
        public double progress() {
            return marketsNeeded <= 0 ? 1.0 : Math.min(1.0, (double) marketsReady / marketsNeeded);
        }
    }

    private volatile long watchingSince;
    private volatile WarmUp warmUp = new WarmUp(false, 0, 0, 0, 0, "waiting for the first market snapshot");
    /** True when the ledger already holds a market history; the warm-up is then nothing to wait for. */
    private volatile boolean hasHistory;

    public WarmUp warmUp() {
        return warmUp;
    }

    /**
     * Read once at startup: an install that has been running has hundreds of
     * thousands of sales behind it and needs no warm-up. Only a fresh ledger
     * has to watch first.
     */
    public void noteLedgerHistory(java.nio.file.Path databasePath) {
        try (dev.doughbay.storage.Database db = new dev.doughbay.storage.Database(databasePath);
             // Counting every row answers a question that stops mattering at
             // five thousand of them, and there are one and a third million.
             var ps = db.connection().prepareStatement(
                     "SELECT COUNT(*) FROM (SELECT 1 FROM transactions LIMIT 5000)");
             var rs = ps.executeQuery()) {
            long rows = rs.next() ? rs.getLong(1) : 0;
            hasHistory = rows >= 5_000;
            if (hasHistory) {
                warmUp = new WarmUp(true, 0, 0, (int) Math.min(Integer.MAX_VALUE, rows), 0,
                        "plenty of sales already collected; no warm-up needed");
            } else {
                LOGGER.info("DoughBay automation: a fresh ledger ({} sales); watching the market before trading", rows);
            }
        } catch (Exception e) {
            hasHistory = false;
        }
    }

    private void refreshWarmUp(MarketWatcher.Snapshot snapshot, long now) {
        if (hasHistory) return;
        if (watchingSince == 0) watchingSince = now;
        int needed = (int) dev.doughbay.fabric.Tuning.get("warmup.markets");
        boolean gate = dev.doughbay.fabric.Tuning.get("warmup.enabled") >= 0.5;
        if (snapshot == null) {
            warmUp = new WarmUp(!gate, 0, needed, 0, now - watchingSince, "waiting for the first market snapshot");
            return;
        }
        int ready = 0;
        int sales = 0;
        for (MarketStats m : snapshot.markets()) {
            sales += m.sampleCount();
            if (m.hasPrices() && m.sampleCount() >= (int) dev.doughbay.fabric.Tuning.get("warmup.samples")
                    && m.confidence() > 0) {
                ready++;
            }
        }
        long watched = now - watchingSince;
        boolean enough = ready >= needed;
        String detail = enough
                ? ready + " markets priced from " + sales + " observed sales"
                : "watching the market: " + ready + " of " + needed + " markets priced from " + sales
                        + " sales, " + (watched / 60_000) + " min in";
        warmUp = new WarmUp(!gate || enough, ready, needed, sales, watched, detail);
    }

    /** Null when the session may start; otherwise why the warm-up is not finished. */
    public String warmUpBlocker() {
        WarmUp w = warmUp;
        if (w.ready()) return null;
        return "Still watching the market before trading: " + w.detail();
    }

    public synchronized ExecutionResult start(ContinuousAutomationPolicy requestedPolicy,
                                              RunMode requestedMode) {
        if (dev.doughbay.fabric.DoughBayClient.collector()) {
            return ExecutionResult.refused(
                    "This client is set to scan only: it records the market but never trades. "
                    + "Turn off \"This client only scans\" in Settings to trade from here");
        }
        String warmUpBlocker = warmUpBlocker();
        if (warmUpBlocker != null) {
            return ExecutionResult.refused(warmUpBlocker);
        }
        if (requestedPolicy == null || requestedMode == null) {
            return ExecutionResult.refused("A complete automation policy and run mode are required");
        }
        if (isRunning()) {
            return ExecutionResult.refused("An automation session is already running");
        }
        String persistenceBlocker = persistenceStartBlocker();
        if (persistenceBlocker != null) {
            return ExecutionResult.refused(persistenceBlocker);
        }
        if (recoveredSession || sessionOpen) {
            return ExecutionResult.refused(
                    "A recovered session must be resumed or deliberately ended; caps cannot reset");
        }
        if (hasUnresolvedPosition()) {
            return ExecutionResult.refused(
                    "The previous session still has an unresolved position; refusing another buy");
        }
        String blocker = authorizationBlocker(Minecraft.getInstance());
        if (blocker != null) return ExecutionResult.refused(blocker);
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) {
            return ExecutionResult.refused("The execution driver is already active");
        }

        policy = requestedPolicy;
        runMode = requestedMode;
        sessionOpen = true;
        armed = true;
        recoveredSession = false;
        sessionStartedAtMillis = System.currentTimeMillis();
        sessionActiveMillis = 0;
        activeSubMillisNanos = 0;
        activeStateStartedNanos = 0;
        lastRuntimeHeartbeatNanos = System.nanoTime();
        recoveredOpenPositions = List.of();
        uncertainExposure = AutomationUncertainExposure.none();
        pendingOpportunity = null;
        positionValuation = null;
        trackedPosition = null;
        attemptedListingKeys.clear();
        awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
        tradesStarted = 0;
        // Listings adopted from an earlier session are still money out; a
        // new session's outstanding-spend cap counts them from the start.
        committedSpend = parkedSpend();
        cooldownUntilMillis = 0;
        bufferedSaleNotice = null;
        boundListingKey = "";
        reconciliationStatus = "Waiting for a post-purchase active-listing snapshot";
        lastActiveListingScanStartedAt = 0;
        saleNoticeBaselineScanStartedAt = 0;
        saleNoticeObservedAtMillis = 0;
        candidateCoverageBoundaryMillis = 0;
        clearCoverageEvidence();
        pausedFromState = State.STOPPED;
        pauseResumable = false;
        clearPreparationState();
        clearPersistenceGenerations();
        AutomationSessionCheckpoint startCheckpoint = checkpoint(
                true, State.SCANNING.name(), null, AutomationUncertainExposure.none(),
                "New authorized automation session opened");
        sessionStartPersistenceGeneration = persistence.submit(startCheckpoint);
        if (sessionStartPersistenceGeneration <= 0) {
            sessionOpen = false;
            sessionStartedAtMillis = 0;
            return ExecutionResult.refused(
                    "Automation persistence rejected the session checkpoint; execution remains locked");
        }
        transition(State.SCANNING, requestedMode == RunMode.SINGLE
                ? "Single trade armed; waiting for durable session checkpoint"
                : "Continuous session armed; waiting for durable session checkpoint");
        return ExecutionResult.awaitingPlayer(detail);
    }

    /**
     * A watch that has not clicked anything has spent nothing. Stopping in
     * that moment must not leave a recovery lock behind.
     */
    private void releaseUnclickedWatchExposure() {
        if (state == State.BUYING && watchBuy && !buyTerminalSucceeded
                && (!buyCommandStarted || driver.watchHasNotClicked())) {
            uncertainExposure = AutomationUncertainExposure.none();
            watchBuy = false;
            watchCandidates = List.of();
            pendingOpportunity = null;
            buyIntentPersistenceGeneration = 0;
        }
    }

    /** Stops this controller and the driver before either can advance again. */
    public synchronized void emergencyStop() {
        pausedByPlayer = true;
        armed = false;
        releaseUnclickedWatchExposure();
        boolean exposure = recoveredExposureRemains();
        driver.emergencyStop();
        restoreSelectedSlot(Minecraft.getInstance());
        if (exposure) {
            pauseFromCurrent("Emergency stop: driver stopped; unresolved exposure retained",
                    state == State.MONITORING);
            queuePauseCheckpoint("Emergency stop retained unresolved exposure");
        } else if (sessionOpen) {
            recoveredSession = true;
            pauseFromCurrent(
                    "Emergency stop: session paused and durable caps retained", false);
            queuePauseCheckpoint("Emergency stop retained the open session and caps");
        } else {
            pendingOpportunity = null;
            bufferedSaleNotice = null;
            clearCoverageEvidence();
            transition(State.STOPPED,
                    "Emergency stop: session and execution driver stopped synchronously");
        }
    }

    public synchronized void stop() {
        pausedByPlayer = true;
        armed = false;
        releaseUnclickedWatchExposure();
        boolean exposure = recoveredExposureRemains();
        driver.emergencyStop();
        restoreSelectedSlot(Minecraft.getInstance());
        if (exposure) {
            pauseFromCurrent("Session stopped with unresolved exposure retained",
                    state == State.MONITORING);
            queuePauseCheckpoint("Explicit stop retained unresolved exposure");
        } else if (sessionOpen) {
            if (!requestSessionEnd(
                    "Session deliberately ended with no unresolved exposure")) {
                // Stop is synchronous even when the persistence queue cannot
                // accept the closing checkpoint. Retain the open session and
                // its caps in a non-resumable recovery lock; never leave the
                // state machine scanning after the player pressed Stop.
                String closeFailure = detail;
                recoveredSession = true;
                pauseFromCurrent(
                        "Session stop could not close durably; caps retained"
                                + (closeFailure == null || closeFailure.isBlank()
                                ? "" : ": " + closeFailure), false);
            }
        } else {
            pendingOpportunity = null;
            bufferedSaleNotice = null;
            clearCoverageEvidence();
            transition(State.STOPPED, "Automation session stopped");
        }
    }

    public synchronized void pause(String reason) {
        String safeReason = reason == null || reason.isBlank()
                ? "Automation paused by request" : reason;
        if (state == State.SCANNING) clearPendingCandidate();
        pauseFromCurrent(safeReason,
                state == State.SCANNING || state == State.MONITORING);
        if (sessionOpen) queuePauseCheckpoint(safeReason);
    }

    /** Only an unambiguous scan or already-listed position can be resumed. */
    public synchronized ExecutionResult resume() {
        if (state != State.PAUSED) {
            return ExecutionResult.refused("Only a paused session can be resumed");
        }
        armed = true;
        if (!pauseResumable) {
            return ExecutionResult.refused(
                    "This pause protects an uncertain operation and cannot be resumed automatically");
        }
        if (recoveredSession) {
            // Resume now unsticks a recovered session rather than refusing: it
            // abandons the stale exposure and scans fresh (held items stay in
            // the inventory and re-list). Pressing Resume is the operator
            // saying "stop waiting, just go".
            return forceReset(Minecraft.getInstance());
        }
        String blocker = authorizationBlocker(Minecraft.getInstance());
        if (blocker != null) return ExecutionResult.refused(blocker);
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) {
            return ExecutionResult.refused("The execution driver is not idle");
        }
        long now = System.currentTimeMillis();
        if (pausedFromState == State.MONITORING
                && trackedPosition != null
                && trackedPosition.status() == PositionStatus.LISTED) {
            if (now - trackedPosition.listedAt() > policy.maximumHoldMillis()) {
                pauseResumable = false;
                return ExecutionResult.refused(
                        "Maximum hold time already elapsed; inspect the position manually");
            }
            if (bufferedSaleNotice != null
                    && (saleNoticeObservedAtMillis <= 0
                    || now - saleNoticeObservedAtMillis > policy.maximumSnapshotAgeMillis())) {
                pauseResumable = false;
                return ExecutionResult.refused(
                        "Buffered sale evidence is too old to reconcile automatically");
            }
            transition(State.MONITORING, "Resumed monitoring the one exact listed position");
            return ExecutionResult.awaitingPlayer(detail);
        }
        if (pausedFromState == State.SCANNING
                && pendingOpportunity == null && !hasUnresolvedPosition()) {
            transition(State.SCANNING, "Resumed scanning fresh signals");
            return ExecutionResult.awaitingPlayer(detail);
        }
        // Nothing clean to resume into (a purchased or uncertain position is in
        // the way): treat Resume as the operator forcing a fresh start rather
        // than leaving it parked.
        return forceReset(Minecraft.getInstance());
    }

    /**
     * Why the session will not resume right now, and what to do about it, in
     * one plain line for the Logs tab. Read-only: it never changes state.
     */
    public synchronized String pauseGuidance() {
        if (state != State.PAUSED) {
            return state == State.STOPPED ? "Stopped — press Start to begin." : "Running.";
        }
        if (!pauseResumable) {
            return "This pause guards an uncertain operation. Use the recovery controls on the "
                    + "Automation tab, or Stop and Start again.";
        }
        if (recoveredSession) {
            return "Recovered session — use 'Resume recovered session' on the Automation tab, not plain Resume.";
        }
        if (dev.doughbay.fabric.Tuning.get("session.auto_resume") < 0.5) {
            return "session.auto_resume is off, so it will not resume by itself — turn it on in Settings.";
        }
        String blocker = authorizationBlocker(Minecraft.getInstance());
        if (blocker != null) return "Waiting: " + blocker;
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) {
            return "The driver is finishing an operation; Resume works once it is idle.";
        }
        if (hasUnresolvedPosition()) {
            return "A purchased or uncertain position must be reconciled first — see the Automation tab.";
        }
        return "Ready to resume — press Resume.";
    }

    /** Continues an open recovered session without resetting its durable caps. */
    public synchronized ExecutionResult resumeRecoveredSession(
            ContinuousAutomationPolicy requestedPolicy) {
        if (!recoveryApplied || !recoveredSession || !sessionOpen
                || state != State.PAUSED) {
            return ExecutionResult.refused("No exposure-free recovered session is available");
        }
        if (requestedPolicy == null) {
            return ExecutionResult.refused("A complete automation policy is required");
        }
        if (recoveredExposureRemains()) {
            Minecraft client = Minecraft.getInstance();
            if (recoverableInHand()) {
                // The bought stack is in the inventory: resuming means listing it.
                policy = requestedPolicy;
                String blocker = authorizationBlocker(client);
                if (blocker != null) return ExecutionResult.refused(blocker);
                if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) {
                    return ExecutionResult.refused("The execution driver is not idle");
                }
                armed = true;
                long now = System.currentTimeMillis();
                if (purchasedStackInHand(client)) {
                    recoveredSession = false;
                    listPurchasedStackInHand(client, now, "Resumed with the bought stack in the inventory");
                } else {
                    stateChangedAtMillis = 0;
                    adoptBuyExposureInHand(client, now);
                }
                return ExecutionResult.awaitingPlayer(detail);
            }
            // Exposure that is not in hand is a phantom - already listed or
            // gone - and used to lock Resume out entirely ("must be reconciled
            // first"), the loop that stranded a recovered session. Resume now
            // force-resets it: abandon the phantom, clear the lock, scan fresh.
            return forceReset(Minecraft.getInstance());
        }
        String persistenceBlocker = persistenceStartBlocker();
        if (persistenceBlocker != null) {
            return ExecutionResult.refused(
                    "Recovered session cannot resume while persistence is pending: "
                            + persistenceBlocker);
        }
        policy = requestedPolicy;
        if (sessionLimitReached()) {
            return ExecutionResult.refused(
                    "Recovered trade/spend caps are already reached; end the recovered session");
        }
        String blocker = authorizationBlocker(Minecraft.getInstance());
        if (blocker != null) return ExecutionResult.refused(blocker);
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) {
            return ExecutionResult.refused("The execution driver is not idle");
        }
        recoveredSession = false;
        armed = true;
        sessionStartPersistenceGeneration = persistence.submit(checkpoint(
                true, State.SCANNING.name(), null, AutomationUncertainExposure.none(),
                "Recovered session resumed without resetting durable caps"));
        if (sessionStartPersistenceGeneration <= 0) {
            recoveredSession = true;
            return ExecutionResult.refused(
                    "Recovered-session resume could not be queued durably");
        }
        transition(State.SCANNING,
                "Recovered caps retained; waiting for durable resume checkpoint");
        return ExecutionResult.awaitingPlayer(detail);
    }

    /**
     * First explicit recovery step. It never clears exposure; it requests new
     * complete order-book evidence after the player says they inspected the
     * inventory and auction externally.
     */
    public synchronized ExecutionResult beginManualResolution(
            ContinuousAutomationPolicy requestedPolicy,
            Minecraft client,
            MarketWatcher.Snapshot snapshot) {
        if (!recoveryApplied || !recoveredSession || state != State.PAUSED
                || !recoveredExposureRemains()) {
            return ExecutionResult.refused("No recovered exposure is available to resolve");
        }
        if (recoveredExposureCount() != 1) {
            return ExecutionResult.refused(
                    "Recovered records do not prove exactly one logical exposure; refusing to guess");
        }
        if (requestedPolicy == null) {
            return ExecutionResult.refused("A complete automation policy is required");
        }
        policy = requestedPolicy;
        String blocker = authorizationBlocker(client);
        if (blocker != null) return ExecutionResult.refused(blocker);
        String itemId = recoveredExposureItemId();
        if (itemId.isBlank()) {
            return ExecutionResult.refused("Recovered exact item identity is unavailable");
        }
        if (inventoryContainsItem(client, itemId)) {
            return ExecutionResult.refused(
                    "Matching inventory exists; exposure is not resolved and cannot be dismissed");
        }
        long now = System.currentTimeMillis();
        if (snapshot == null || snapshot.demo() || snapshot.updatedAt() <= 0) {
            return ExecutionResult.refused(
                    "A real market snapshot is required before recovery inspection");
        }
        manualResolutionFirstConfirmedAt = now;
        manualResolutionCoverageBoundary = now;
        manualResolutionEvidenceAt = 0;
        manualResolutionEvidence = "";
        manualResolutionEvidenceReady = false;
        manualResolutionPersistenceGeneration = 0;
        clearCoverageEvidence();
        detail = "Recovery step 1 recorded; requesting two later complete exact-book scans";
        return ExecutionResult.awaitingPlayer(detail);
    }

    /** Second explicit recovery step; clearing waits for its durable audit ACK. */
    public synchronized ExecutionResult confirmManualResolution(
            Minecraft client, MarketWatcher.Snapshot snapshot) {
        if (manualResolutionFirstConfirmedAt <= 0 || !manualResolutionEvidenceReady
                || manualResolutionPersistenceGeneration > 0) {
            return ExecutionResult.refused(
                    "Recovery evidence is not ready for the second confirmation");
        }
        String itemId = recoveredExposureItemId();
        if (itemId.isBlank() || inventoryContainsItem(client, itemId)) {
            manualResolutionEvidenceReady = false;
            return ExecutionResult.refused(
                    "Matching inventory appeared; exposure remains locked");
        }
        long now = Math.max(System.currentTimeMillis(),
                manualResolutionFirstConfirmedAt + 1);
        String evidenceBlocker = manualResolutionCoverageBlocker(client, snapshot, now);
        if (evidenceBlocker != null) {
            return ExecutionResult.refused(
                    "Waiting for final post-evidence recheck: " + evidenceBlocker);
        }

        List<Position> exposurePositions = exposurePositions();
        Position selected = exposurePositions.size() == 1
                && (!uncertainExposure.active()
                || uncertainMatchesPosition(uncertainExposure, exposurePositions.getFirst()))
                ? exposurePositions.getFirst() : null;
        long positionId = selected == null ? 0 : selected.positionId();
        Position closed = selected == null ? null : selected.closed(
                PositionStatus.CANCELLED, now, 0, Double.NaN);
        AutomationSessionCheckpoint resolvedCheckpoint = checkpoint(
                true, State.PAUSED.name(), closed, AutomationUncertainExposure.none(),
                "Player externally verified no matching inventory or own active listing");
        // The evidence timestamp is the one captured when the scans were
        // accepted. The live snapshot can hold a later placeholder with no
        // timestamp at all, which the audit record rightly refuses.
        long evidenceAt = manualResolutionEvidenceAt > 0
                ? Math.min(manualResolutionEvidenceAt, now) : now;
        AutomationManualResolution resolution = new AutomationManualResolution(
                positionId,
                manualResolutionFirstConfirmedAt,
                now,
                evidenceAt,
                manualResolutionEvidence + "; final later exact-book recheck also clean",
                "Second explicit recovery confirmation; exposure retained until durable ACK");
        manualResolutionPersistenceGeneration = persistence.submitManualResolution(
                resolution, resolvedCheckpoint);
        if (manualResolutionPersistenceGeneration <= 0) {
            return ExecutionResult.refused(
                    "Manual-resolution audit could not be queued; exposure remains locked");
        }
        detail = "Recovery step 2 queued; exposure remains locked until audit is durable";
        return ExecutionResult.awaitingPlayer(detail);
    }

    /** Deliberately closes an exposure-free recovered session; caps reset only afterward. */
    public synchronized ExecutionResult endRecoveredSession() {
        if (!recoveredSession || !sessionOpen || state != State.PAUSED) {
            return ExecutionResult.refused("No recovered open session is available to end");
        }
        if (recoveredExposureRemains()) {
            return ExecutionResult.refused(
                    "Recovered exposure must be resolved before ending the session");
        }
        if (requestSessionEnd("Recovered session deliberately ended after reconciliation")) {
            return ExecutionResult.awaitingPlayer(detail);
        }
        return ExecutionResult.refused(detail);
    }

    /**
     * Operator override for a session wedged past every automatic recovery - a
     * recovered exposure whose exact-book coverage is refused, a checkpoint
     * that never becomes durable, any dead end the gates cannot climb out of.
     * It abandons the tracked exposure, clears the recovery lock and every
     * pending checkpoint, and drops straight back into a clean scanning
     * session, without the evidence the normal resolve/end paths demand.
     *
     * <p>Nothing is bought or sold here, and the held items stay in the
     * inventory - so the next inventory sweep (the List-inventory key)
     * re-adopts and lists them; only the ledger's memory of the abandoned
     * exposure is lost, which is the cost of unwedging by hand. Healthy open
     * listings are left tracked so their sales still reconcile. Loud on
     * purpose. Reached by the auto-reset watchdog and by an operator.</p>
     */
    public synchronized ExecutionResult forceReset(Minecraft client) {
        long now = System.currentTimeMillis();
        List<Position> abandoned = exposurePositions();
        LOGGER.warn("DoughBay automation: FORCE RESET - abandoning {} unresolved exposure record(s), clearing "
                + "the recovery lock and every pending checkpoint, and scanning fresh. Held items stay in the "
                + "inventory and re-list on the next sweep.", abandoned.size());
        // Write the abandoned exposure off to the ledger, best effort. If a
        // stalled persistence write is itself the wedge these never land, and
        // that is fine - the reset does not wait on them.
        for (Position p : abandoned) {
            persistence.submit(checkpoint(sessionOpen, state.name(),
                    p.closed(PositionStatus.CANCELLED, now, 0, Double.NaN),
                    AutomationUncertainExposure.none(), "Force reset by the operator"));
        }
        recoveredOpenPositions = List.of();
        trackedPosition = null;
        repricing = null;
        pendingOpportunity = null;
        uncertainExposure = AutomationUncertainExposure.none();
        recoveredSession = false;
        pausedByPlayer = false;
        pauseResumable = true;
        armed = true;
        wedgedSince = 0;
        clearManualResolution();
        // Abandon every pending persistence generation - one of them may be the
        // stall; the reset proceeds without waiting for it to become durable.
        sessionStartPersistenceGeneration = 0;
        buyIntentPersistenceGeneration = 0;
        purchasePersistenceGeneration = 0;
        listIntentPersistenceGeneration = 0;
        listedPersistenceGeneration = 0;
        settlementPersistenceGeneration = 0;
        endSessionPersistenceGeneration = 0;
        manualResolutionPersistenceGeneration = 0;
        runtimePersistenceGeneration = 0;
        pendingPersistenceSince = 0;
        transition(State.SCANNING, "Force reset: exposure abandoned, scanning fresh");
        detail = "Force reset: recovery cleared, scanning fresh";
        return ExecutionResult.awaitingPlayer(detail);
    }

    /**
     * Self-heal for a session that has sat wedged on an unresolvable recovered
     * exposure. Once it has been PAUSED with locked exposure for
     * recovery.auto_reset_min minutes straight - well past any legitimate
     * recovery, which resolves in seconds - force-reset it so a bot nobody is
     * watching does not sit dead for hours. Off when the setting is 0.
     */
    private void maybeForceResetWedged(Minecraft client, long now) {
        if (pausedByPlayer) {
            wedgedSince = 0;
            return;
        }
        double minutes = dev.doughbay.fabric.Tuning.get("recovery.auto_reset_min");
        // Not gated on PAUSED: a wedged recovery oscillates between PAUSED and
        // SCANNING as it retries, so a PAUSED-only check kept resetting the
        // timer and never fired. A recovered exposure that has not resolved in
        // this many minutes is stuck whatever state the tick lands in.
        boolean wedged = recoveredSession && recoveredExposureRemains();
        if (minutes < 0.5 || !wedged) {
            wedgedSince = 0;
            return;
        }
        if (wedgedSince == 0) {
            wedgedSince = now;
            return;
        }
        if (now - wedgedSince < (long) (minutes * 60_000L)) return;
        LOGGER.warn("DoughBay automation: recovery has been wedged on locked exposure for {} min; "
                + "auto force-reset.", (long) minutes);
        forceReset(client);
    }

    /** Called once per client tick, after the global B-key stop has been drained. */
    public synchronized void tick(Minecraft client, MarketWatcher.Snapshot marketSnapshot) {
        if (marketSnapshot != null && marketSnapshot.accountBalance() > 0) {
            lastKnownBalance = marketSnapshot.accountBalance();
            dev.doughbay.fabric.DoughBayClient.noteBalanceForHive(lastKnownBalance);
        }
        autoReleaseUnclickedRecoveredIntent(client);
        autoResumeInternalPause(client, System.currentTimeMillis());
        autoResumeRecoveredSession(client, marketSnapshot, System.currentTimeMillis());
        autoListRecoveredPurchase(client, System.currentTimeMillis());
        autoAdoptRecoveredListing(client, System.currentTimeMillis());
        adoptBuyExposureInHand(client, System.currentTimeMillis());
        tickInternal(client, marketSnapshot);
        // Every gate the session waits on is otherwise visible only on the
        // Automation tab. Logging each transition, and only transitions, makes
        // a stall diagnosable from the log without a screenshot.
        if (!detail.equals(lastLoggedDetail)) {
            lastLoggedDetail = detail;
            LOGGER.info("DoughBay automation [{}]: {}", state, detail);
            dev.doughbay.fabric.AutomationLog.add(state.name(), detail);
        }
    }

    /** True while the player has the GoNuts screen open; the session waits rather than closing it. */
    private volatile boolean panelOpen;
    private volatile long panelOpenedAt;

    private volatile long panelActivityAt;

    public synchronized void setPanelOpen(boolean open) {
        if (open && !panelOpen) {
            panelOpenedAt = System.currentTimeMillis();
            panelActivityAt = panelOpenedAt;
        }
        panelOpen = open;
    }

    /** A click, a keypress or a scroll on the panel: the player is still working. */
    public void notePanelActivity() {
        panelActivityAt = System.currentTimeMillis();
    }

    public boolean panelOpen() {
        return panelOpen;
    }

    /**
     * Whether the session may start something new. The player having the
     * panel open is a hold, not a pause: whatever is already in flight
     * finishes, and nothing new begins until the screen is closed, so
     * settings can be changed without fighting the bot for the screen.
     */
    private boolean heldByPanel(Minecraft client) {
        if (!panelOpen) return false;
        if (client == null || !(client.gui.screen() instanceof dev.doughbay.fabric.DoughBayScreen)) {
            // The screen went away without telling us; drop the hold.
            panelOpen = false;
            return false;
        }
        long idleFor = System.currentTimeMillis() - panelActivityAt;
        long limit = (long) (dev.doughbay.fabric.Tuning.get("panel.hold_idle_sec") * 1000);
        if (limit > 0 && idleFor > limit) return false;   // left open and walked away
        return driver.operationIntent() == AutomatedExecutionDriver.OperationIntent.NONE
                && trackedPosition == null
                && repricing == null
                && deskStep == null
                && pendingOpportunity == null;
    }

    private void tickInternal(Minecraft client, MarketWatcher.Snapshot marketSnapshot) {
        controllerTick++;
        long now = System.currentTimeMillis();
        synchronizePersistenceRecovery();
        processOutOfBandPersistence();
        checkStuckPersistence(now);
        maybeForceResetWedged(client, now);
        if (manualResolutionFirstConfirmedAt > 0
                && manualResolutionPersistenceGeneration <= 0) {
            updateManualResolutionEvidence(client, marketSnapshot, now);
        }
        if (!isRunning()) return;

        String persistenceFailure = persistenceHealthBlocker();
        if (persistenceFailure != null) {
            pauseInternal(persistenceFailure);
            return;
        }
        if (sessionStartPersistenceGeneration > 0
                && !persistence.status().durable(sessionStartPersistenceGeneration)) {
            detail = "Waiting for the session-open checkpoint to become durable";
            return;
        }
        sessionStartPersistenceGeneration = 0;
        queueRuntimeHeartbeat();

        if (marketSnapshot != null) {
            lastActiveListingScanStartedAt = Math.max(
                    lastActiveListingScanStartedAt,
                    marketSnapshot.activeListingScanStartedAt());
        }

        String blocker = authorizationBlocker(client);
        if (blocker != null) {
            pauseInternal(blocker);
            return;
        }

        refreshQuietMarket(now);
        refreshMarketRegime(now);

        if (heldByPanel(client)) {
            long left = Math.max(0, (long) dev.doughbay.fabric.Tuning.get("panel.hold_idle_sec")
                    - (now - panelActivityAt) / 1000);
            detail = "Holding while you have the screen open; carries on by itself in " + left + " s";
            return;
        }

        // Nothing else happens while the shelf has the player.
        //
        // A run borrows the hand, the view and the ground under the feet for a
        // few seconds. Without this, the trading carried on around it: the run
        // took the chest into the hand and the listing flow, which lists
        // whatever is in the hand, listed it. Three times, at five and a half
        // thousand each, entirely correctly by its own lights.
        //
        // The check for a busy shelf already existed, but it only stopped a
        // second run starting. It never stopped the first one being trampled.
        if (dev.doughbay.fabric.StashDesk.busy()) {
            detail = "The shelf has the player; trading resumes when it is done";
            return;
        }

        switch (state) {
            case SCANNING -> scanAndBuy(client, marketSnapshot, now);
            case REPRICING -> observeRepriceTerminal(client, now);
            case AUDITING_SLOTS -> observeSlotAuditTerminal(client, now);
            case READING_ORDERS -> observeOrdersTerminal(client, now);
            case BID_DESK -> observeDeskTerminal(client, now);
            case BUYING -> observeBuyTerminal(client, marketSnapshot, now);
            case PREPARING_LIST -> prepareAndList(client, marketSnapshot, now);
            case LISTING -> observeListTerminal(client, marketSnapshot, now);
            case MONITORING -> {
                if (listedPersistenceGeneration > 0) {
                    if (!persistence.status().durable(listedPersistenceGeneration)) {
                        detail = "Waiting for durable LISTED position before sale reconciliation";
                        return;
                    }
                    listedPersistenceGeneration = 0;
                    listIntentPersistenceGeneration = 0;
                    uncertainExposure = AutomationUncertainExposure.none();
                }
                if (runMode == RunMode.CONTINUOUS && policy.maxOpenListings() > 1
                        && trackedPosition != null
                        && trackedPosition.status() == PositionStatus.LISTED) {
                    parkListedPosition(now);
                    return;
                }
                if (trackedPosition == null || trackedPosition.status() != PositionStatus.LISTED) {
                    pauseInternal("Monitoring lost its exact listed position");
                } else if (bufferedSaleNotice != null) {
                    reconcileSaleDisappearance(client, marketSnapshot, now);
                } else if (now - trackedPosition.listedAt() > policy.maximumHoldMillis()) {
                    pauseInternal("Maximum hold time elapsed without an exact sale notice");
                }
            }
            case COOLDOWN -> {
                if (settlementPersistenceGeneration > 0) {
                    detail = "Sale reconciled; waiting for durable closed-position checkpoint";
                    return;
                }
                if (endSessionPersistenceGeneration > 0) return;
                if (now >= cooldownUntilMillis) {
                    if (runMode == RunMode.SINGLE) {
                        sessionOpen = false;
                        transition(State.STOPPED, "Single-trade session completed");
                    } else if (sessionLimitReached()) {
                        requestSessionEnd("Continuous session limit reached");
                    } else {
                        pendingOpportunity = null;
                        transition(State.SCANNING, "Cooldown complete; scanning fresh signals");
                    }
                }
            }
            case STOPPED, PAUSED -> {
                // isRunning excludes these states.
            }
        }
    }

    /** Trusted, strictly parsed server notices are routed here by the HUD bridge. */
    public synchronized boolean observeSale(DonutSaleMessageParser.SaleNotice notice) {
        // A sale is a slot freed whether or not the ledger knew about that
        // listing. Counting only the ones it recognised meant the panel sat at
        // ninety of ninety while stock plainly sold: seventeen of those
        // listings had no position behind them, so nothing moved when they
        // went. The auction house does not care which of our records exist.
        soldSinceAudit++;
        noteSlotFreed();
        if (observeTrackedSale(notice)) return true;
        for (Position listed : new ArrayList<>(openListings)) {
            if (saleMatcher.match(listed, notice).matched()) {
                closeOpenListing(listed, notice, System.currentTimeMillis());
                return true;
            }
        }
        return false;
    }

    private boolean observeTrackedSale(DonutSaleMessageParser.SaleNotice notice) {
        if (state == State.LISTING && trackedPosition != null
                && trackedPosition.status() == PositionStatus.PURCHASED) {
            TrackedSaleMatcher.MatchResult earlyMatch = saleMatcher.match(
                    trackedPosition.withStatus(PositionStatus.LISTED), notice);
            if (!earlyMatch.matched()) return false;
            if (bufferedSaleNotice == null || bufferedSaleNotice.equals(notice)) {
                if (bufferedSaleNotice == null) {
                    bufferedSaleNotice = notice;
                    saleNoticeBaselineScanStartedAt = lastActiveListingScanStartedAt;
                    saleNoticeObservedAtMillis = System.currentTimeMillis();
                }
                reconciliationStatus =
                        "Early exact sale notice buffered; LIST proof is still required";
                detail = "Exact sale notice buffered until LIST terminal proof completes";
                return true;
            }
            pauseInternal("Multiple distinct sale notices arrived before listing verification");
            return false;
        }
        if (state != State.MONITORING) return false;
        if (trackedPosition == null || trackedPosition.status() != PositionStatus.LISTED) {
            return false;
        }
        TrackedSaleMatcher.MatchResult match = saleMatcher.match(trackedPosition, notice);
        if (!match.matched()) return false;
        if (bufferedSaleNotice == null) {
            bufferedSaleNotice = notice;
            saleNoticeBaselineScanStartedAt = lastActiveListingScanStartedAt;
            saleNoticeObservedAtMillis = System.currentTimeMillis();
        } else if (!bufferedSaleNotice.equals(notice)) {
            pauseInternal("Multiple distinct exact sale notices are ambiguous");
            return false;
        }
        reconciliationStatus = "Exact sale notice received; awaiting active-listing disappearance";
        detail = reconciliationStatus;
        return true;
    }

    /** One parked listing, for the slot tracker on the screen and the HUD. */
    public record OpenListingView(long positionId, String itemKey, int quantity,
                                  long purchasePrice, long targetPrice, long listedAt) {
    }

    /** The listings this controller has parked and not yet seen sold, oldest first. */
    public synchronized List<OpenListingView> openListingViews() {
        List<OpenListingView> out = new ArrayList<>(openListings.size());
        for (Position p : openListings) {
            out.add(new OpenListingView(p.positionId(), p.itemKey(), p.quantity(),
                    p.purchasePrice(), p.targetPrice(), p.listedAt()));
        }
        return List.copyOf(out);
    }

    /** The per-session cap on parked listings, or 45 when no policy is set. */
    /** The rank's slot count from the live setting; the config's figure is only a floor of one. */
    public synchronized int maxOpenListings() {
        int slots = (int) Math.round(dev.doughbay.fabric.Tuning.get("slots.max"));
        return Math.max(1, Math.min(ContinuousAutomationPolicy.MAX_OPEN_LISTINGS, slots));
    }

    public synchronized SessionSnapshot snapshot() {
        long currentSessionRuntime = currentSessionActiveMillis();
        long currentLifetimeRuntime = currentLifetimeActiveMillis();
        return new SessionSnapshot(state, runMode, detail, trackedPosition,
                pendingOpportunity == null ? "" : pendingOpportunity.listing().listingKey(),
                boundListingKey, reconciliationStatus,
                tradesStarted, committedSpend, attemptedListingKeys.size(),
                cooldownUntilMillis, stateChangedAtMillis,
                recoveredExposureRemains(), pauseResumable, requestedCoverageItemId(),
                persistenceStatus, recoveredSession, recoveredExposureCount(),
                manualResolutionStage(), currentSessionRuntime,
                currentLifetimeRuntime, openListings.size());
    }

    private void synchronizePersistenceRecovery() {
        if (persistence == null) {
            persistenceStatus = "FAILED — automation persistence is not connected";
            return;
        }
        AutomationPersistencePort.Status status = persistence.status();
        persistenceStatus = status.loadState().name() + " — " + status.detail();
        if (!status.ready() || recoveryApplied) return;

        AutomationSessionRecovery recovery = status.recovery();
        recoveryApplied = true;
        sessionActiveMillis = recovery.sessionActiveMillis();
        lifetimeActiveMillis = recovery.lifetimeActiveMillis();
        activeStateStartedNanos = 0;
        activeSubMillisNanos = 0;
        lastRuntimeHeartbeatNanos = System.nanoTime();
        // Listings that are simply up on the auction house are adopted, not
        // reconciled; only what remains after that is exposure to resolve.
        List<Position> remaining = adoptListedPositions(recovery.openPositions());
        long parkedSpend = parkedSpend();
        boolean exposureRemains = !remaining.isEmpty() || recovery.uncertainExposure().active();
        if (!recovery.sessionOpen() && !exposureRemains) {
            persistenceStatus = "READY — no recovered automation exposure; "
                    + openListings.size() + " listing(s) still open";
            return;
        }

        sessionOpen = true;
        recoveredSession = true;
        sessionStartedAtMillis = recovery.sessionStartedAt() > 0
                ? recovery.sessionStartedAt()
                : Math.max(1, recovery.updatedAt() > 0
                ? recovery.updatedAt() : System.currentTimeMillis());
        tradesStarted = recovery.tradesStarted();
        // The recovered figure can be zero after a clean end while the
        // adopted listings are still money out; the larger of the two is
        // what the outstanding-spend cap must count.
        committedSpend = Math.max(recovery.committedSpend(), parkedSpend);
        runMode = parseRunMode(recovery.runMode());
        // The shelf's own stock comes back to it rather than to the listing
        // queue, which would hunt the pack for stacks that are in a chest.
        //
        // Taken out of "remaining", which is the list this method is actually
        // working with; recoveredOpenPositions is not written until the end of
        // it. Reading the field instead of the local meant this looked at the
        // previous session's answer, found nothing, and let the shelf's own
        // stock go round the recovery path as though it were lost.
        recoveredQueue.clear();
        if (!dev.doughbay.fabric.Shelf.waiting().isEmpty()) {
            List<Position> mine = new ArrayList<>();
            List<Position> notMine = new ArrayList<>();
            for (Position p : remaining) {
                if (dev.doughbay.fabric.Shelf.holds(p.positionId())) mine.add(p);
                else notMine.add(p);
            }
            if (!mine.isEmpty()) {
                shelved.addAll(mine);
                for (Position p : mine) {
                    setAsideItems.add(p.itemKey());
                    setAsideItems.add(baseItemId(p.itemKey()));
                }
                remaining = List.copyOf(notMine);
                LOGGER.info("DoughBay shelf: {} position(s) came back under the shelf's care, not the listing queue",
                        mine.size());
            }
        }
        if (remaining.size() > 1 && remaining.stream().allMatch(p -> p.status() == PositionStatus.PURCHASED)) {
            // Several unlisted purchases are not an ambiguity, just a queue:
            // the first is taken now, the rest follow one at a time.
            recoveredQueue.addAll(remaining.subList(1, remaining.size()));
            remaining = List.of(remaining.getFirst());
            LOGGER.info("DoughBay automation: {} recovered purchases; listing them one at a time", recoveredQueue.size() + 1);
        }
        recoveredOpenPositions = remaining;
        uncertainExposure = recovery.uncertainExposure();
        trackedPosition = recoveredOpenPositions.size() == 1
                ? recoveredOpenPositions.getFirst() : null;
        boundListingKey = recovery.boundListingKey();
        nextPositionId = Math.max(nextPositionId,
                recovery.openPositions().stream().mapToLong(Position::positionId)
                        .max().orElse(0) + 1);
        pendingOpportunity = null;
        positionValuation = null;
        pausedFromState = parseState(recovery.controllerState());
        pauseResumable = false;
        state = State.PAUSED;
        stateChangedAtMillis = System.currentTimeMillis();
        reconciliationStatus = recoveredExposureRemains()
                ? "Recovered exposure is locked pending explicit reconciliation"
                : "Recovered session caps retained; choose Resume or End session";
        detail = recoveredExposureRemains()
                ? "PAUSED: recovered " + recoveredExposureCount()
                + " unresolved REAL exposure record(s); no automatic command may run"
                : "PAUSED: recovered open session; spend/trade caps were not reset";
        persistenceStatus = "READY — durable recovery applied fail-closed";
    }

    /**
     * A recovered BUY intent is only a risk if the buy went through after
     * the last frame. Every buy starts from an inventory holding none of the
     * item, so once the player is in the world, an inventory still holding
     * none of it proves nothing was bought. Release it rather than demand
     * the two-scan reconciliation that stalls on the API.
     */
    private void autoReleaseUnclickedRecoveredIntent(Minecraft client) {
        if (state != State.PAUSED || !recoveredSession || !uncertainExposure.active()) return;
        if (!"BUYING".equals(uncertainExposure.phase())) return;
        if (client == null || client.player == null || client.level == null) return;
        // With a screen open the player may be holding the very stack on the
        // cursor; the inventory proves nothing until the screen is closed.
        if (client.gui.screen() != null) return;
        String itemId = uncertainExposure.itemId();
        if (itemId == null || itemId.isBlank() || inventoryContainsItem(client, itemId)) return;
        LOGGER.info("DoughBay automation: recovered BUY intent for {} released: none in the inventory, so nothing was bought",
                itemId);
        uncertainExposure = AutomationUncertainExposure.none();
        persistence.submit(checkpoint(sessionOpen, State.PAUSED.name(), null,
                AutomationUncertainExposure.none(),
                "Recovered BUY intent released: inventory holds none of " + itemId));
        reconciliationStatus = recoveredExposureRemains()
                ? "Recovered exposure is locked pending explicit reconciliation"
                : "Recovered session caps retained; choose Resume or End session";
        detail = recoveredExposureRemains()
                ? "PAUSED: recovered " + recoveredExposureCount()
                + " unresolved REAL exposure record(s); no automatic command may run"
                : "PAUSED: recovered open session; spend/trade caps were not reset";
    }

    /**
     * Releases an orphan uncertain LISTING intent under auto-resume. A stack was
     * mid-list when an emergency stop hit; its Position is no longer open (sold,
     * cancelled, or written off) and the item is not in the inventory, so it is
     * provably nowhere and there is nothing left to list. Without this the pause
     * holds for ever: the adopt path only handles a PURCHASED position, and the
     * recovered session refuses to resume while any exposure remains, so every
     * restart comes straight back to the same locked pause. Only under a
     * standing yes - a watched pause keeps its manual controls exactly as before.
     */
    private void autoReleaseOrphanListingIntent(Minecraft client) {
        if (!autoResumeOn()) return;
        if (state != State.PAUSED || !recoveredSession || !uncertainExposure.active()) return;
        if (!uncertainExposure.phase().toUpperCase(Locale.ROOT).contains("LIST")) return;
        if (client == null || client.player == null || client.level == null) return;
        if (client.gui.screen() != null) return;
        // An uncertain LISTING that still points at an open position is the
        // normal case, and the adopt path resolves it; only a true orphan, with
        // no open position of its own left, is released here.
        for (Position position : exposurePositions()) {
            if (uncertainMatchesPosition(uncertainExposure, position)) return;
        }
        String itemId = uncertainExposure.itemId();
        if (itemId == null || itemId.isBlank() || inventoryContainsItem(client, itemId)) return;
        LOGGER.warn("DoughBay automation: orphan LISTING intent for {} x{} released: no open position and none in the inventory",
                itemId, uncertainExposure.itemCount());
        uncertainExposure = AutomationUncertainExposure.none();
        persistence.submit(checkpoint(sessionOpen, State.PAUSED.name(), null,
                AutomationUncertainExposure.none(),
                "Orphan LISTING intent released: no open position, inventory holds none of " + itemId));
        reconciliationStatus = recoveredExposureRemains()
                ? "Recovered exposure is locked pending explicit reconciliation"
                : "Recovered session caps retained; choose Resume or End session";
        detail = recoveredExposureRemains()
                ? "PAUSED: recovered " + recoveredExposureCount()
                + " unresolved REAL exposure record(s); no automatic command may run"
                : "PAUSED: recovered open session; spend/trade caps were not reset";
    }

    private void processOutOfBandPersistence() {
        if (persistence == null || !persistence.status().ready()) return;
        if (runtimePersistenceGeneration > 0
                && persistence.status().durable(runtimePersistenceGeneration)) {
            runtimePersistenceGeneration = 0;
        }
        if (sessionStartPersistenceGeneration > 0
                && persistence.status().durable(sessionStartPersistenceGeneration)) {
            sessionStartPersistenceGeneration = 0;
            if (state == State.PAUSED) {
                recoveredSession = true;
                detail = "PAUSED: the open-session checkpoint is durable; caps remain retained";
            }
        }
        if (purchasePersistenceGeneration > 0
                && persistence.status().durable(purchasePersistenceGeneration)) {
            purchasePersistenceGeneration = 0;
            buyIntentPersistenceGeneration = 0;
            buyIntentCoverageBoundaryMillis = 0;
            uncertainExposure = AutomationUncertainExposure.none();
            if (state == State.PAUSED) {
                // A stop may arrive after the PURCHASED checkpoint was
                // accepted but before its worker acknowledgement. Apply that
                // acknowledgement here as well as in PREPARING_LIST so the
                // stale BUY intent cannot appear as a second exposure. Never
                // resume or list automatically from this path.
                recoveredSession = true;
                reconciliationStatus =
                        "Verified PURCHASED position is durable; manual reconciliation remains required";
                detail = "PAUSED: durable purchased position retained; no listing command will resume";
            }
        }
        if (listedPersistenceGeneration > 0
                && persistence.status().durable(listedPersistenceGeneration)) {
            listedPersistenceGeneration = 0;
            listIntentPersistenceGeneration = 0;
            listIntentCoverageBoundaryMillis = 0;
            uncertainExposure = AutomationUncertainExposure.none();
            if (state == State.PAUSED) {
                recoveredSession = true;
                reconciliationStatus =
                        "Verified LISTED position is durable; explicit reconciliation remains required";
                detail = "PAUSED: durable listed position retained; no trade will resume automatically";
            }
        }
        if (settlementPersistenceGeneration > 0
                && persistence.status().durable(settlementPersistenceGeneration)) {
            settlementPersistenceGeneration = 0;
            trackedPosition = settlementPendingPosition;
            settlementPendingPosition = null;
            bufferedSaleNotice = null;
            pendingOpportunity = null;
            positionValuation = null;
            saleNoticeObservedAtMillis = 0;
            saleNoticeBaselineScanStartedAt = 0;
            clearCoverageEvidence();
            reconciliationStatus = "Exact notice and disappearance are durably reconciled";
            if (runMode == RunMode.SINGLE) sessionOpen = false;
            if (state == State.PAUSED) {
                if (sessionOpen) {
                    recoveredSession = true;
                    detail = "PAUSED: sale closure is durable; Resume retained caps or End session";
                } else {
                    recoveredSession = false;
                    transition(State.STOPPED,
                            "Sale closure and single-session end are durable");
                }
            }
        }
        if (manualResolutionPersistenceGeneration > 0
                && persistence.status().durable(manualResolutionPersistenceGeneration)) {
            AutomationSessionRecovery recovery = persistence.status().recovery();
            tradesStarted = recovery.tradesStarted();
            committedSpend = recovery.committedSpend();
            recoveredOpenPositions = adoptListedPositions(recovery.openPositions());
            uncertainExposure = recovery.uncertainExposure();
            trackedPosition = recoveredOpenPositions.size() == 1
                    ? recoveredOpenPositions.getFirst() : null;
            manualResolutionPersistenceGeneration = 0;
            buyIntentPersistenceGeneration = 0;
            purchasePersistenceGeneration = 0;
            listIntentPersistenceGeneration = 0;
            listedPersistenceGeneration = 0;
            clearManualResolution();
            reconciliationStatus = recoveredExposureRemains()
                    ? "One audit completed; another recovered exposure remains"
                    : "Manual resolution audit is durable; session caps remain retained";
            detail = recoveredExposureRemains()
                    ? "PAUSED: additional recovered exposure still requires reconciliation"
                    : "PAUSED: exposure resolved; Resume retained caps or End recovered session";
        }
        if (endSessionPersistenceGeneration > 0
                && persistence.status().durable(endSessionPersistenceGeneration)) {
            endSessionPersistenceGeneration = 0;
            sessionOpen = false;
            recoveredSession = false;
            recoveredOpenPositions = List.of();
            uncertainExposure = AutomationUncertainExposure.none();
            trackedPosition = null;
            boundListingKey = "";
            reconciliationStatus = "Session closed durably; a future Start creates new caps";
            transition(State.STOPPED, "Automation session ended durably");
        }
    }

    private void updateManualResolutionEvidence(Minecraft client,
                                                MarketWatcher.Snapshot snapshot,
                                                long now) {
        String itemId = recoveredExposureItemId();
        if (itemId.isBlank()) {
            detail = "Recovery evidence blocked: exact item identity is unavailable";
            return;
        }
        if (inventoryContainsItem(client, itemId)) {
            detail = "Recovery evidence blocked: matching inventory is still present";
            return;
        }
        String snapshotBlocker = liveSnapshotBlocker(snapshot, now);
        if (snapshotBlocker != null) {
            detail = "Recovery evidence blocked by market health: " + snapshotBlocker;
            return;
        }
        CoverageRequest request = coverageRequest();
        String blocker = exactCoverageBlocker(
                snapshot == null ? null : snapshot.exactListingCoverage(), request, now);
        if (blocker != null) {
            detail = "Recovery step 1: waiting for exact active-book coverage: " + blocker;
            return;
        }
        MarketWatcher.ExactListingCoverage coverage = snapshot.exactListingCoverage();
        ActiveBookEvidence.Analysis book = activeBookEvidence.analyze(itemId, coverage.listings());
        if (!book.valid()) {
            detail = "Recovery evidence blocked: " + book.detail();
            return;
        }
        String ownershipBlocker = recoveredOwnershipBlocker(
                client, book.listings(), itemId, recoveredExposureItemCount());
        if (ownershipBlocker != null) {
            detail = "Recovery evidence blocked: " + ownershipBlocker;
            return;
        }
        if (!acceptConsecutiveStableCoverage("MANUAL_RECOVERY", coverage, book, ownRows(client))) {
            detail = "Recovery step 1: first clean full-book scan captured; waiting for second";
            return;
        }
        manualResolutionEvidenceAt = coverage.completedAt();
        manualResolutionEvidence = "No matching inventory; two complete zero-failure exact-book "
                + "scans after first confirmation found no own item/count listing"
                + (coverage.indistinguishableRows() > 0
                ? " (" + coverage.indistinguishableRows()
                + " indistinguishable row(s) collapsed by seller/item/count/price)"
                : "");
        manualResolutionEvidenceReady = true;
        detail = "Recovery evidence ready; use the separate second confirmation button";
    }

    /**
     * Offers the best of what a full client cannot take to the hive.
     *
     * <p>Only when sharing is on, and only a couple at a time so the board is
     * not flooded by a client that scans the same markets every few seconds.
     * The tip carries what the deal looked worth; a sibling that takes it still
     * puts it through every buy gate of its own before spending a coin.
     */
    private void postTipsWhenFull(MarketWatcher.Snapshot snapshot) {
        if (dev.doughbay.fabric.Tuning.get("hive.share_deals") < 0.5) return;
        dev.doughbay.fabric.HiveTips tips = dev.doughbay.fabric.DoughBayClient.hiveTips();
        if (tips == null || snapshot == null) return;
        int posted = 0;
        for (Opportunity o : snapshot.opportunities()) {
            if (posted >= 2) break;
            if (o.listing() == null || o.expectedNetProfit() <= 0) continue;
            if (!dev.doughbay.fabric.Tuning.itemAllowed(o.listing().itemId())) continue;
            tips.post(o.listing().itemId(), o.listing().itemCount(), o.buyPrice(),
                    o.recommendedSellPrice(), (long) o.expectedNetProfit());
            posted++;
        }
    }

    private void scanAndBuy(Minecraft client, MarketWatcher.Snapshot snapshot, long now) {
        if (snapshot == null) {
            detail = "Waiting for a real market snapshot";
            return;
        }
        // Fail-safe first, before the full-book gate below can return: a stack
        // collected from an order that never got listed - because an order still
        // claims it, or the item was blacklisted after the fill - is dead money
        // in the pack, and a full book (exactly when this happens) used to skip
        // the sweep entirely. On a slow timer, or on demand from the key, list
        // everything sellable past those gates.
        double sweepMin = dev.doughbay.fabric.Tuning.get("inventory.sweep_min");
        if (armed && dev.doughbay.fabric.Tuning.get("inventory.sweep_enabled") >= 0.5
                && sweepMin > 0 && now - lastInventoryFailsafeAt > (long) (sweepMin * 60_000L)) {
            lastInventoryFailsafeAt = now;
            inventoryListUntil = now + 60_000L;
        }
        if (pendingOpportunity == null && trackedPosition == null && safeInventoryContext(client)
                && now < inventoryListUntil && forceListNextStack(client, now)) return;
        // Two counts have to be under the cap, not one. The ledger's is what
        // this session believes it put up; the server's is what is actually
        // there, and the two drift - a listing the audit could not match, one
        // made before a restart, one the ledger closed in error. Buying on the
        // ledger's number alone meant buying into a full auction house and
        // getting "You have too many listed items" back, over and over, with
        // stock bought and nowhere to put it.
        int fromServer = serverListedSlots();
        int rankSlots = (int) Math.round(dev.doughbay.fabric.Tuning.get("slots.max"));
        // Plus the slots that are already promised. A pull-back genuinely
        // frees one on the server, and the panel is right to show it free -
        // but it is free for about four seconds and it is already spoken for
        // by the relist that follows. Reading that gap as headroom is how the
        // desk bought into a full book and got "too many listed items" back
        // while the panel honestly read eighty-nine of ninety.
        int spokenFor = slotsSpokenFor();
        if (fromServer >= 0 && fromServer + spokenFor >= rankSlots) {
            // Recount while we wait. The recount used to be triggered by the
            // server refusing a listing - and this gate stops us ever asking,
            // so the refusal never comes and the recount never runs. That
            // matters most exactly here: a full book that will not move is
            // usually full of listings the ledger has lost track of, and the
            // audit is the only thing that takes them back. Blocking the
            // symptom removed the cure.
            long now2 = System.currentTimeMillis();
            if (now2 - lastDriftAuditAt >= 60_000L) {
                lastDriftAuditAt = now2;
                slotAuditPending = true;
            }
            // And run it here, rather than queueing it and dropping through.
            // The recount is consumed further down this method, past the
            // repricing below - and on a full book there is always another
            // listing due a pull-back, so that return fired every time and the
            // count the whole gate depends on was never actually taken.
            if (slotAuditPending && pendingOpportunity == null && startSlotAudit(client)) return;
            // A full book is the one moment when repricing is the only useful
            // thing left, because it is what makes stock sell and a sale is
            // what frees the slot. This gate sat in front of the pull-backs
            // and returned, so the bot answered a jammed auction house by
            // doing nothing at all and waiting for sales it had stopped
            // helping along.
            if (startRepriceIfDue(client, now2)) return;
            detail = "The auction house is full (" + fromServer + " of " + rankSlots
                    + " listed" + (spokenFor > 0 ? ", " + spokenFor + " already promised" : "")
                    + "); recounting and repricing to free a slot";
            return;
        }
        int believed = Math.max(openListings.size(), Math.max(0, fromServer));
        if (believed >= maxOpenListings()) {
            // Full by our own count, but the server's slot count is not known
            // yet - a fresh restart, before the first slot audit - so the
            // branch above that reprices a full book never fired, and this one
            // used only to wait. A recovered book then sat at the cap doing
            // nothing after every restart: no audit to learn the real count,
            // and no pull-backs on the stock that is exactly what a jammed
            // book needs to sell. A ledger that believes it is full must still
            // audit and pull its stock back, whether or not the server count
            // has caught up.
            long nowFull = System.currentTimeMillis();
            if (fromServer < 0 && nowFull - lastDriftAuditAt >= 60_000L) {
                lastDriftAuditAt = nowFull;
                slotAuditPending = true;
            }
            if (slotAuditPending && pendingOpportunity == null && startSlotAudit(client)) return;
            if (startRepriceIfDue(client, nowFull)) return;
            // Full and nothing due yet - so hand the best of what it cannot
            // take to a sibling with a slot to spare. It only ever offers what
            // it is passing up, so the two never chase the same deal.
            postTipsWhenFull(snapshot);
            detail = "Open listings at the cap (" + believed + " of "
                    + maxOpenListings() + "); waiting for a sale before buying again";
            return;
        }
        // MarketWatcher intentionally preserves the last rows when reporting a
        // terminal collector failure. Fresh timestamps alone therefore cannot
        // authorize a buy: only a status written after a complete live scan is
        // eligible.
        if (!healthyFeedStatus(snapshot.status())) {
            detail = "Market feed is not a completed healthy live scan: "
                    + (snapshot.status() == null ? "missing status" : snapshot.status());
            return;
        }
        String snapshotBlocker = liveSnapshotBlocker(snapshot, now);
        if (snapshotBlocker != null) {
            detail = "Waiting for a healthy completed-sale snapshot: " + snapshotBlocker;
            return;
        }

        lastSnapshot = snapshot;
        claimFetchedStock(now);
        reclaimRecoveredPackStock(client, now);
        if (!shelved.isEmpty()) refreshShelfWants(now);
        refreshWarmUp(snapshot, System.currentTimeMillis());
        driver.setContainerPricer(this::priceContainer);
        if (pendingOpportunity == null && slotAuditPending && startSlotAudit(client)) return;
        if (pendingOpportunity == null && trackedPosition == null && !recoveredQueue.isEmpty()
                && !bookFullNow(now)) {
            Position next = recoveredQueue.removeFirst();
            // A recovered purchase whose item is nowhere in the inventory is a
            // ghost from an earlier session - a lost buy, or a denied item that
            // never got a listing attempt to reconcile it, so it sat as open
            // exposure for days. Close it instead of pausing to "list" a stack
            // that does not exist. Preparing-to-list was the only path that ever
            // closed a phantom, and denied items skip it, so ghosts piled up
            // here unchecked; this reconciles them on sight so they cannot.
            if (safeInventoryContext(client)
                    && now - next.purchasedAt() > 30 * 60_000L
                    && !inventoryContainsItem(client, baseItemId(next.itemKey()))) {
                closePhantom(next, "recovered purchase " + baseItemId(next.itemKey()) + " x"
                        + next.quantity() + " has no matching stack in the inventory");
                return;
            }
            recoveredOpenPositions = List.of(next);
            trackedPosition = next;
            positionValuation = null;
            recoveredSession = true;
            pauseInternal("Recovered purchase #" + next.positionId() + " (" + next.itemKey() + " x" + next.quantity()
                    + ") is next; listing it");
            return;
        }
        retryStrandedStacks(now);
        if (pendingOpportunity == null && trackedPosition == null && safeInventoryContext(client)
                && (bookHandCollectedFill(client, now) || bookOwedFromInventory(client, now) || bookStrayStack(client, now))) return;
        // Stock waiting to be put away goes first of all.
        //
        // It was going to ride along on the next wander, three quarters of an
        // hour away, and until then it sits in the pack. That is the worst
        // place for it: the pack is thirty-six slots and it is what you drop
        // when you die, while the chest cannot be stolen from and cannot be
        // lost. Something the bot has already decided it is not selling today
        // should not be carried around waiting for a lift.
        if (pendingOpportunity == null && trackedPosition == null && visitShelfIfDue(client, now)) return;
        // The desk goes first, but not for ever: with a reprice waiting it
        // yields after two turns, or a busy desk keeps stale listings stale.
        boolean deskFirst = !(deskTurnsSinceReprice >= 2 && repriceDue(now) != null);
        if (pendingOpportunity == null && deskFirst && startDeskAction(client, snapshot, now)) {
            deskTurnsSinceReprice++;
            return;
        }
        if (pendingOpportunity == null && startOrdersRead(client, now)) return;
        if (pendingOpportunity == null && startRepriceIfDue(client, now)) {
            if (repricing != null) deskTurnsSinceReprice = 0;
            return;
        }
        if (pendingOpportunity == null && !deskFirst && startDeskAction(client, snapshot, now)) {
            deskTurnsSinceReprice++;
            return;
        }
        if (pendingOpportunity == null && startInHandRelisting(client, now)) return;
        if (pendingOpportunity == null && startOrphanListing(client, snapshot, now)) return;

        if (pendingOpportunity == null) {
            boolean quiet = quietField();
            driver.setPaceScale(quiet ? QUIET_FIELD_PACE() : 1.0);
            String rivalMode = quiet ? "quiet" : "active";
            if (!rivalMode.equals(lastRivalMode)) {
                LOGGER.info("DoughBay automation: rival field is {}{}; watch pace x{}",
                        rivalMode, rivalNote(), quiet ? QUIET_FIELD_PACE() : 1.0);
                lastRivalMode = rivalMode;
            }
            ContinuousAutomationContext context = selectionContext(snapshot, now, false);
            long nowForRest = System.currentTimeMillis();
            List<Opportunity> offered = new ArrayList<>();
            for (Opportunity o : snapshot.opportunities()) {
                if (o.listing() == null
                        || marketRestUntil.getOrDefault(o.listing().itemId(), 0L) > nowForRest
                        || !dev.doughbay.fabric.Tuning.itemAllowed(o.listing().itemId())) {
                    continue;
                }
                // Market signals are shared with the tab and the alerts; the
                // session only re-caps them to what this session may spend.
                Opportunity capped = isMarketCandidate(o) ? cappedForSession(o) : o;
                if (capped != null) offered.add(capped);
            }
            boolean follow = followRivals(now);
            if (follow != lastFollowMode) {
                lastFollowMode = follow;
                LOGGER.info("DoughBay automation: {} (sales {} in 30 min, {} in 6 h; rivals {} on)",
                        follow ? "our sales are slow while rivals are busy; mirroring their markets" : "back to our own markets",
                        salesSince(now - 30 * 60_000L), salesSince(now - 6 * 3_600_000L),
                        rivals() == null ? 0 : rivals().activeNow());
            }
            offered.addAll(underdogCandidates(now, snapshot.updatedAt(), follow));
            if (follow) offered.addAll(mirrorCandidates(snapshot, now));
            offered.addAll(orderCandidates(snapshot, now));
            // Deals a full or broke sibling handed over. A tip is only taken
            // when this client can see the same live listing in its own feed,
            // and it still passes every gate below like any other candidate -
            // the tip only puts it on the table, it never buys on trust.
            dev.doughbay.fabric.HiveTips tips = dev.doughbay.fabric.DoughBayClient.hiveTips();
            if (tips != null) {
                for (dev.doughbay.fabric.HiveTips.Tip tip : tips.open()) {
                    for (Opportunity o : snapshot.opportunities()) {
                        if (o.listing() == null) continue;
                        if (!o.listing().itemId().equals(tip.itemKey())
                                || o.listing().itemCount() != tip.itemCount()) continue;
                        if (dev.doughbay.fabric.Tuning.itemAllowed(o.listing().itemId())
                                && tips.claim(tip.tipId())) {
                            Opportunity capped = isMarketCandidate(o) ? cappedForSession(o) : o;
                            if (capped != null) offered.add(capped);
                        }
                        break;
                    }
                }
            }
            // Every market that clears the live-trade gates is watched at
            // once on the auction house's Recently Listed page. Searching
            // one market at a time meant staring at pages whose cheapest row
            // was far above the ceiling while deals came and went elsewhere.
            java.util.Map<String, Opportunity> byRow = new java.util.LinkedHashMap<>();
            String lastGateDetail = "";
            for (Opportunity o : offered) {
                ContinuousOpportunitySelector.SelectionResult one = selector.select(
                        List.of(o), selectorPolicy(), context);
                if (one.opportunity().isEmpty()) {
                    lastGateDetail = one.detail();
                    if (isUnderdog(o)) {
                        logUnderdog(o, "rejected by the trade gates: "
                                + one.rejectedCandidates().getOrDefault(o.listing().listingKey(), List.of(one.detail())), now);
                    }
                    continue;
                }
                Opportunity ok = one.opportunity().orElseThrow();
                if (listingReconciler.ownershipOf(ok.listing(), localUuid(client), localName(client))
                        == ActiveListingReconciler.Ownership.OWN) continue;
                // Another client of the same hive listed it. Buying it would
                // move stock from one of our accounts to another and pay the
                // auction its fee for the trouble.
                if (dev.doughbay.fabric.DoughBayClient.hiveMember(ok.listing().sellerName())) {
                    lastGateDetail = ok.listing().sellerName() + " is one of ours; not buying our own stock";
                    continue;
                }
                if (liveAwareCeiling(ok.listing().itemId(), ok.listing().itemCount(),
                        ceilingFor(ok), now) <= 0) {
                    if (isUnderdog(ok)) logUnderdog(ok, "skipped: live ceiling is zero", now);
                    continue;
                }
                if (client.player != null && inventoryContainsItem(client, ok.listing().itemId())) {
                    if (isUnderdog(ok)) logUnderdog(ok, "skipped: inventory already holds some", now);
                    continue;
                }
                // Several of the same listing only queue behind each other;
                // spread the slots across markets instead. A market rivals
                // have proven liquid, and are not working right now, may
                // hold one more.
                int perMarketCap = MAX_OPEN_PER_MARKET();
                if (rivalProven(ok.listing().itemId(), ok.listing().itemCount())
                        && !rivalCrowded(ok.listing().itemId(), ok.listing().itemCount())) perMarketCap++;
                if (openOfMarket(ok.listing().itemId(), ok.listing().itemCount()) >= perMarketCap) continue;
                Tier tier = tierOf(ok.buyPrice());
                // The hour's demand: busy markets first, dead hours skipped, and
                // a large ticket only where the buyers are awake right now.
                double demand = demandNow(ok.listing().itemId(), ok.listing().itemCount());
                if (!isUnderdog(ok) && !isMirror(ok) && !isOrderBacked(ok)
                        && demand < dev.doughbay.fabric.Tuning.get("demand.dead_pct") / 100.0) {
                    lastGateDetail = ok.listing().itemId() + " is in a dead hour (demand x"
                            + String.format(java.util.Locale.ROOT, "%.1f", demand) + ")";
                    continue;
                }
                ok = timed(ok, demand);
                // A shadowed market's proof is the rival's flips, not the feed's sale rate.
                if (tier == Tier.LARGE && !isUnderdog(ok)) {
                    // A big ticket needs proven demand at that price band, and
                    // one of each is plenty: three $120K copies of one item
                    // just wait in line behind each other.
                    if (ok.stats() == null || !Double.isFinite(ok.stats().salesPerHour())
                            || ok.stats().salesPerHour() * demand < LARGE_MIN_SALES_PER_HOUR()) {
                        lastGateDetail = "Large ticket " + ok.listing().itemId()
                                + " skipped: fewer than " + (int) LARGE_MIN_SALES_PER_HOUR() + " sale(s) per hour";
                        continue;
                    }
                    if (openOfMarket(ok.listing().itemId(), ok.listing().itemCount()) >= LARGE_MAX_OPEN_PER_MARKET()) continue;
                }
                if (openOfTier(tier) >= tierSlots(tier)) {
                    boolean tooGood = ok.expectedRoiPercent() >= SPILL_MIN_ROI_PERCENT()
                            && ok.confidence() >= SPILL_MIN_CONFIDENCE();
                    if (!tooGood) {
                        lastGateDetail = "Tier pool full (" + tierSummary() + ")";
                        continue;
                    }
                }
                // The slow lane: a long hold may not take a slot kept for quick flips.
                if (!quickCandidate(ok) && openSlow(now) >= slowLaneSlots()) {
                    lastGateDetail = "Slow lane full (" + openSlow(now) + " of " + slowLaneSlots()
                            + " long holds up); only quick flips until something sells";
                    continue;
                }
                String key = ok.listing().itemId() + "|" + ok.listing().itemCount();
                Opportunity previous = byRow.get(key);
                if (previous == null || ceilingFor(ok) > ceilingFor(previous)) byRow.put(key, ok);
            }
            if (byRow.isEmpty()) {
                detail = lastGateDetail.isBlank()
                        ? "No market clears the live-trade gates right now" : lastGateDetail;
                return;
            }
            List<Opportunity> watched = new ArrayList<>(byRow.values());
            // Rank by what a turn of the machine is worth, not by how good a
            // deal it is in the abstract.
            //
            // The scarce thing here is not money and not opportunities - it is
            // cycles. Every buy and every sale is a walk through the auction
            // GUI and the bot manages about two hundred and forty of those an
            // hour, which is a hard ceiling set by how fast a person could
            // plausibly click. Scoring by quality alone spent them evenly: over
            // six hours the trades under fifty thousand took forty percent of
            // the cycles and returned eleven percent of the profit, while the
            // two-hundred-thousand band paid nine times as much for the same
            // one turn.
            //
            // Expected profit times the chance it actually sells is what a
            // cycle is worth. The gates above have already thrown out anything
            // thin, falling or badly priced, so ordering what survives by value
            // does not lower the bar - it just spends the turns on the biggest
            // of the things that already qualified. A high-value market that
            // appears at three in the morning gets taken then, because those
            // markets trade flat around the clock and there is no better hour
            // to wait for.
            if (dev.doughbay.fabric.Tuning.get("buy.reliability_rank") >= 0.5) {
                // Rank by how reliably a whole stack clears at a profit, then keep
                // only the top markets so the book fills with proven movers.
                watched.sort(Comparator.comparingDouble(this::reliabilityScore).reversed());
                trimToTopMarkets(watched, (int) Math.round(
                        dev.doughbay.fabric.Tuning.get("buy.reliability_top_n")));
            } else if (dev.doughbay.fabric.Tuning.get("buy.rank_by_value") >= 0.5) {
                watched.sort(Comparator.comparingDouble(
                        (Opportunity o) -> o.expectedNetProfit() * Math.max(0.05, o.saleProbability())).reversed());
            } else {
                watched.sort(Comparator.comparingDouble(Opportunity::score).reversed());
            }
            int underdogs = 0;
            for (Opportunity o : watched) if (isUnderdog(o)) underdogs++;
            String underdogNote = underdogs > 0 ? " · underdog " + underdogs : "";
            watchCandidates = List.copyOf(watched);
            watchBuy = true;
            pendingOpportunity = watched.get(0);
            pendingIsSlow = !quickCandidate(pendingOpportunity);
            candidateCoverageBoundaryMillis = 0;
            clearCoverageEvidence();
            reconciliationStatus = "Watching recent listings for " + watched.size() + " market(s): "
                    + summarizeWatch(watched) + " [" + tierSummary() + rivalNote() + underdogNote + "]";
            detail = reconciliationStatus;
            return;
        }

        if (watchBuy) {
            beginWatchBuy(client, now);
            return;
        }

        // Nothing below is worth an API call while the player has a screen
        // open: the buy cannot start, and the evidence would be cleared and
        // gathered again on every tick until the screen closes. Checked here
        // rather than only before the click, which cost about six exact
        // scans a second for as long as the Automation tab stayed open.
        if (!safeInventoryContext(client) && !modScreenOpen(client)) {
            detail = "Waiting before buying: " + guiBlockerDescription(client);
            return;
        }

        MarketStats latestStats = exactMarketStats(snapshot, pendingOpportunity);
        if (latestStats == null
                || latestStats.calculatedAt() <= candidateCoverageBoundaryMillis) {
            detail = "Waiting for completed-sale statistics newer than candidate selection";
            return;
        }
        // The row to buy is chosen on the auction screen at buy time. The
        // API book, minutes stale, only supplies resale competition here.
        // A market candidate's resale target comes from completed sales; the
        // API's active rows are ghosts and must not be allowed to undercut it.
        List<Listing> book = isMarketCandidate(pendingOpportunity)
                ? List.of()
                : marketListings(snapshot, pendingOpportunity.listing().itemId());

        ContinuousOpportunityRevalidator.Result revalidated = revalidator.revalidate(
                pendingOpportunity, latestStats, book, policy, auctionFees, now,
                candidateCoverageBoundaryMillis, false);
        if (revalidated.opportunity().isEmpty()) {
            String listingKey = pendingOpportunity.listing().listingKey();
            rememberAttempt(listingKey);
            restMarket(pendingOpportunity.listing().itemId(),
                    System.currentTimeMillis() + MARKET_REST_MILLIS);
            clearPendingCandidate();
            detail = "Candidate invalidated before purchase: " + revalidated.detail();
            return;
        }
        Opportunity rebound = revalidated.opportunity().orElseThrow();
        ActiveListingReconciler.Reconciliation existingOwn = listingReconciler.reconcile(
                book, new ActiveListingReconciler.Target(
                        rebound.listing().itemId(), rebound.listing().itemCount(),
                        rebound.recommendedSellPrice()),
                localUuid(client), localName(client));
        if (existingOwn.ambiguous()) {
            pauseInternal("Pre-buy own-listing state is ambiguous: " + existingOwn.detail());
            return;
        }
        if (!existingOwn.unambiguousZero()) {
            rememberAttempt(rebound.listing().listingKey());
            clearPendingCandidate();
            detail = "Skipped candidate because an own listing already has its resale signature";
            return;
        }
        ContinuousOpportunitySelector.SelectionResult reboundSelection = selector.select(
                List.of(rebound), policy, selectionContext(
                        snapshot, now, false, snapshot.updatedAt()));
        if (reboundSelection.opportunity().isEmpty()) {
            rememberAttempt(rebound.listing().listingKey());
            clearPendingCandidate();
            detail = "Fresh candidate no longer passes live policy: "
                    + reboundSelection.detail();
            return;
        }
        rebound = reboundSelection.opportunity().orElseThrow();
        ActiveListingReconciler.Ownership ownership = listingReconciler.ownershipOf(
                rebound.listing(), localUuid(client), localName(client));
        if (ownership == ActiveListingReconciler.Ownership.UNKNOWN) {
            pauseInternal("Candidate seller ownership is malformed or unavailable");
            return;
        }
        if (ownership == ActiveListingReconciler.Ownership.OWN) {
            rememberAttempt(rebound.listing().listingKey());
            detail = "Skipped the local player's own listing";
            return;
        }
        if (modScreenOpen(client)) {
            // The operator watches this state on the mod's own screen, which
            // counts as an open GUI. Close it rather than wait for them to:
            // the auction house is about to be opened regardless.
            client.gui.setScreen(null);
            detail = "Closing the GoNuts screen to begin buying";
            return;
        }
        if (!safeInventoryContext(client)) {
            detail = "Waiting before buying: " + guiBlockerDescription(client);
            return;
        }
        // The driver's legacy inventory-delta completion alone is not strong
        // enough for background play: picking up the same commodity could
        // mimic a buy. Initial automation therefore requires zero preexisting
        // instances and later requires one unique newly arrived exact stack.
        if (inventoryContainsItem(client, rebound.listing().itemId())) {
            pauseInternal("Clear all " + rebound.listing().itemId()
                    + " stacks before background execution; purchase attribution is ambiguous");
            return;
        }
        Inventory inventory = client.player.getInventory();
        switch (ensureReservedSlotFree(client)) {
            case CLEARING -> { detail = "Moving a stray item out of the reserved listing slot"; return; }
            case STUCK -> { pauseInternal(reservedSlotBlocked(inventory)); return; }
            case EMPTY -> { /* slot clear; carry on */ }
        }
        targetAbsentBeforeBuy = true;
        inventoryBeforeBuy = copyNonEquipmentInventory(inventory);
        buyTerminalSucceeded = false;
        purchasedInventorySlot = -1;
        purchaseStableSinceTick = -1;
        long ceiling = ceilingFor(rebound);
        if (ceiling <= 0) {
            rememberAttempt(rebound.listing().listingKey());
            clearPendingCandidate();
            detail = "No purchase price clears policy at the resale target of "
                    + rebound.recommendedSellPrice();
            return;
        }
        rememberAttempt(rebound.listing().listingKey());
        pendingOpportunity = rebound;
        buyCeiling = ceiling;
        verifiedBuyPrice = 0;
        buyIntentCoverageBoundaryMillis = now;
        candidateCoverageBoundaryMillis = 0;
        clearCoverageEvidence();
        boundListingKey = "";
        reconciliationStatus = "Buying the cheapest live " + rebound.listing().itemId()
                + " x" + rebound.listing().itemCount() + " at or under " + ceiling;
        awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
        uncertainExposure = uncertainBuy(rebound, ceiling, now);
        buyCommandStarted = false;
        buyIntentPersistenceGeneration = persistence.submit(checkpoint(
                true, "BUY_INTENT", null, uncertainExposure,
                "Exact BUY intent queued before any auction command"));
        if (buyIntentPersistenceGeneration <= 0) {
            uncertainExposure = AutomationUncertainExposure.none();
            pauseInternal("BUY intent could not be queued durably; no command was sent");
            return;
        }
        transition(State.BUYING,
                "BUY intent queued; waiting for durable acknowledgement before command");
    }

    /**
     * Slots that are free on the server but already have something on the way
     * into them: a listing pulled back and coming straight back up, a stack
     * bought and not yet listed, and anything queued behind those. None of
     * these show in the server's count, and all of them will.
     */
    private synchronized int slotsSpokenFor() {
        int promised = repricing != null ? 1 : 0;
        if (trackedPosition != null && trackedPosition.status() == PositionStatus.PURCHASED) promised++;
        // The recovered queue is not counted: those are positions still being
        // reconciled, and counting them as promised slots inflated the "spoken
        // for" total until the desk believed the auction house was full and
        // stopped buying - a false deadlock from phantom positions.
        return promised;
    }

    /** Whether the server's last word is still that there is no room. */
    private boolean bookFullNow(long now) {
        if (!bookFull) return false;
        if (now - bookFullAt > BOOK_FULL_MAX_HOLD) {
            bookFull = false;
            return false;
        }
        return true;
    }

    /**
     * A slot has demonstrably freed, so whatever the server said before is
     * out of date. Only a sale and a pull-back get to say this.
     */
    private void noteSlotFreed() {
        bookFull = false;
    }

    /**
     * Puts the stack down and waits for somewhere to put it.
     *
     * <p>The stack is bought, paid for and still ours; the only thing missing
     * is a slot. So it goes back on the queue at its original cost, the book
     * is recounted against the server, and the full-house gate holds the queue
     * until a sale or a pull-back frees a slot. Nothing is written off and no
     * command is sent to a book that has nowhere to put it.
     */
    private void holdStackUntilThereIsRoom(Minecraft client, long now, String why) {
        restoreSelectedSlot(client);
        Position held = trackedPosition;
        trackedPosition = null;
        positionValuation = null;
        boundListingKey = "";
        listRetries = 0;
        listCommandStarted = false;
        clearCoverageEvidence();
        if (held != null) recoveredQueue.add(held);
        lastDriftAuditAt = now;
        slotAuditPending = true;
        // And the book is shut until something frees a slot for real. Without
        // this the queue drains straight back into the same refusal, because
        // the count that let it through the first time has not changed.
        bookFull = true;
        bookFullAt = now;
        LOGGER.info("DoughBay automation: {}; holding {} x{} and recounting the book against the server",
                why, held == null ? "the stack" : baseItemId(held.itemKey()),
                held == null ? 0 : held.quantity());
        transition(State.SCANNING,
                "The auction house is full; the stack is held until a slot frees");
    }

    /**
     * Give up the current listing attempt without pausing the session. The
     * stack is still ours and unlisted, so it goes back on the queue to be
     * listed again later, and the session returns to scanning. Unlike
     * {@link #holdStackUntilThereIsRoom} this does not mark the book full - it
     * is for a listing that could not be confirmed (a sell lost to the command
     * rate limit, or an intent that never acknowledged), not for a full house.
     */
    private void abandonListingForNow(Minecraft client, long now, String why) {
        restoreSelectedSlot(client);
        Position held = trackedPosition;
        trackedPosition = null;
        positionValuation = null;
        boundListingKey = "";
        listRetries = 0;
        listCommandStarted = false;
        listIntentWaitSinceMillis = 0;
        clearCoverageEvidence();
        if (held != null) recoveredQueue.add(held);
        lastDriftAuditAt = now;
        slotAuditPending = true;
        LOGGER.info("DoughBay automation: {}; re-queuing {} x{} and scanning on",
                why, held == null ? "the stack" : baseItemId(held.itemKey()),
                held == null ? 0 : held.quantity());
        transition(State.SCANNING, "Listing deferred (" + why + "); scanning on");
    }

    /** Slots the player's own hand-made listings occupy, as last audited. */
    public synchronized int otherListedSlots() {
        return otherListedSlots;
    }

    /**
     * How many listings the server said we had at the last audit, or -1 when
     * that answer is too old to trust. The panel prefers this over any count
     * the ledger can assemble, because the ledger is what the audit exists to
     * correct.
     */
    public synchronized int serverListedSlots() {
        if (serverListedAt <= 0 || System.currentTimeMillis() - serverListedAt > 10 * 60_000L) return -1;
        // The audit is a photograph, and between two of them stock sells and
        // new stock goes up. Showing the photograph on its own froze the panel
        // at ninety while the real count had already fallen - a full house
        // that was visibly still listing. So the audit is the baseline and the
        // ledger's movement since is added to it: right at the moment it is
        // taken, and right between them.
        // Receipts, not the ledger's own bookkeeping. Every sale frees a slot
        // and every listing takes one, and that is true of listings the ledger
        // has lost track of as much as the ones it holds.
        int cap = (int) Math.round(dev.doughbay.fabric.Tuning.get("slots.max"));
        // If the server has refused a listing and nothing has sold since, the
        // book is full whatever the pages added up to. Showing eighty-nine
        // there is not caution, it is the panel repeating the reading that was
        // just proved wrong.
        if (bookFullNow(System.currentTimeMillis())) return cap;
        int raw = serverListedSlots - soldSinceAudit - cancelledSinceAudit + listedSinceAudit;
        // Outside the cap the arithmetic has disproved itself: the book cannot
        // hold more than the rank allows and cannot hold less than nothing, so
        // whatever the baseline was, it is wrong now. That is worth more than
        // the clamp, which quietly absorbed the error and left the panel
        // reading full while stock sold underneath it. Ask the server.
        if (raw > cap || raw < 0) {
            long since = System.currentTimeMillis();
            if (since - lastDriftAuditAt >= 60_000L) {
                lastDriftAuditAt = since;
                slotAuditPending = true;
                LOGGER.info("DoughBay automation: derived slot count is {} against a cap of {}; the baseline has drifted, recounting",
                        raw, cap);
            }
        }
        return Math.max(0, Math.min(cap, raw));
    }

    /**
     * Close whatever container page is open right now, unconditionally. Used
     * after an operation whose page the driver is done with (its terminal has
     * arrived) but that nothing else will close if the desk then has no work -
     * a rate-limited client that finishes a slot audit and has nothing to buy
     * or list would otherwise sit parked on the /ah page. Unlike
     * closeStrayAuctionPage this does not gate on the driver being idle,
     * because it is called at the exact point the operation has just finished.
     */
    private void closeContainerPage(Minecraft client) {
        if (client == null || client.player == null) return;
        if (client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
        }
    }

    /**
     * The server reopens the auction page on its own a moment after a buy,
     * a collect, or a ghost click. Left alone it blocks every later step,
     * and nobody is at the keyboard to press Esc. While the driver is idle
     * an open auction page is therefore closed by the session.
     */
    private boolean closeStrayAuctionPage(Minecraft client) {
        if (client == null || client.player == null) return false;
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return false;
        Screen open = client.gui.screen();
        if (open instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container) {
            String title = container.getTitle().getString().toLowerCase(java.util.Locale.ROOT);
            if (title.contains("auction") || title.contains("orders") || title.contains("deliver")) {
                client.player.closeContainer();
                detail = "Closing a stray auction page the server reopened";
                return true;
            }
        } else if (open == null && client.player.containerMenu != client.player.inventoryMenu) {
            client.player.closeContainer();
            detail = "Closing the auction container the server left open";
            return true;
        } else if (open instanceof net.minecraft.client.gui.screens.dialog.DialogScreen<?>) {
            // A server-pushed dialog - an event prompt, a vote reminder, a
            // MultiButtonDialog - sits over the game and blocks every
            // screen-gated step, so a listing hangs until someone presses
            // Escape by hand. Dismiss it the same way. The driver is idle here
            // (checked above), so this is never the buy confirmation, which is
            // handled inside the buy while the driver is busy.
            client.gui.setScreen(null);
            detail = "Dismissed a server dialog that was blocking the bot";
            return true;
        }
        return false;
    }

    private dev.doughbay.fabric.OrderBook.Order deliveringOrder;
    private boolean deliverAttempted;

    /** The best open order that pays at least this position's listing target for its whole stack, or null. */
    private dev.doughbay.fabric.OrderBook.Order deliverableOrder(Position p) {
        if (p == null || dev.doughbay.fabric.Tuning.get("orders.deliver") < 0.5) return null;
        if (p.itemKey().indexOf('#') >= 0) return null;
        dev.doughbay.fabric.OrderBook book = dev.doughbay.fabric.DoughBayClient.orderBook();
        if (book == null) return null;
        dev.doughbay.fabric.OrderBook.Order best = null;
        for (dev.doughbay.fabric.OrderBook.Order o : book.orders()) {
            if (!o.itemId().equals(p.itemKey()) || !o.parts().isEmpty() || o.remaining() < p.quantity()) continue;
            if (o.unitPrice() * p.quantity() < p.targetPrice()) continue;
            if (o.unitPrice() * p.quantity() < p.purchasePrice() + Math.max(policy.minimumProfit(), riskConfig.minimumProfit())) continue;
            if (best == null || o.unitPrice() > best.unitPrice()) best = o;
        }
        return best;
    }

    /** The stack went to an order and the server said so: the position is sold at what the order paid. */
    private void settleDelivered(Minecraft client, long now, long salePrice) {
        Position p = trackedPosition;
        double profit = salePrice - p.purchasePrice();
        Position sold = p.closed(PositionStatus.SOLD, now, salePrice, profit);
        salesSinceAudit += salePrice;
        committedSpend = Math.max(0, committedSpend - p.purchasePrice());
        persistence.submit(checkpoint(sessionOpen, state.name(), sold, uncertainExposure,
                "Delivered to an order: " + salePrice));
        recordSaleTime(now, salePrice);
        notifySaleObserver(sold, "order");
        restoreSelectedSlot(client);
        LOGGER.info("DoughBay automation: delivered #{} ({} x{}) to an order for {} (profit {})",
                p.positionId(), p.itemKey(), p.quantity(), salePrice, Math.round(profit));
        trackedPosition = null;
        positionValuation = null;
        boundListingKey = "";
        deliveringOrder = null;
        deliverAttempted = false;
        clearCoverageEvidence();
        cooldownUntilMillis = Math.addExact(now, Math.round(policy.cooldownMillis() * (1.0 + 1.5 * Math.random())));
        transition(State.COOLDOWN, "Delivered to an order for " + salePrice + "; cooldown before the next buy");
    }

    /**
     * Stacks the order house pays more for than the auction asks: each open
     * plain order becomes a watch target whose ceiling is the order's price
     * less the minimum profit and ROI, and whose sale is the delivery.
     */
    private List<Opportunity> orderCandidates(MarketWatcher.Snapshot snapshot, long now) {
        if (dev.doughbay.fabric.Tuning.get("orders.source") < 0.5) return List.of();
        dev.doughbay.fabric.OrderBook book = dev.doughbay.fabric.DoughBayClient.orderBook();
        if (book == null || book.orders().isEmpty() || policy == null) return List.of();
        long stamp = snapshot != null && snapshot.updatedAt() > 0 ? Math.min(now, snapshot.updatedAt()) : now;
        double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
        double minimumRoi = Math.max(policy.minimumRoiPercent(), riskConfig.minimumRoiPercent()) / 100.0;
        int max = (int) dev.doughbay.fabric.Tuning.get("orders.max_targets");
        long affordCap = Math.min(effectivePurchaseCap(), effectiveSpendCap() - committedSpend);
        List<Opportunity> out = new ArrayList<>();
        for (dev.doughbay.fabric.OrderBook.Order o : book.orders()) {
            if (out.size() >= max) break;
            if (!o.parts().isEmpty() || !dev.doughbay.fabric.Tuning.itemAllowed(o.itemId())) continue;
            if (o.itemId().indexOf('#') >= 0) continue;
            // The size to deliver is bounded by what one trade can afford, not
            // only by what the order still wants. A high-unit-price item -
            // ancient debris at over a million each - fits just one at a time
            // inside the per-trade cap, so a forty-thousand-unit wall is worked
            // a single at a time instead of skipped for never fitting a stack.
            int affordUnits = o.unitPrice() > 0 ? (int) (affordCap / o.unitPrice()) : 64;
            int want = (int) Math.min(o.remaining(), (long) affordUnits);
            int count = want >= 64 ? 64 : want >= 16 ? 16 : want >= 1 ? 1 : 0;
            if (count == 0) continue;
            if (openOfMarket(o.itemId(), count) >= MAX_OPEN_PER_MARKET()) continue;
            long sell = o.unitPrice() * count;
            long ceiling = Math.min((long) Math.floor(sell - minimumProfit), (long) Math.floor(sell / (1.0 + minimumRoi)));
            ceiling = Math.min(ceiling, affordCap);
            long buy = (long) Math.floor(ceiling * 0.97);
            if (buy <= 0 || sell - buy < minimumProfit) continue;
            StackBucket bucket = StackBucket.of(count);
            MarketStats stats = null;
            if (snapshot != null) {
                for (MarketStats m : snapshot.markets()) {
                    if (m.itemKey().equals(o.itemId()) && m.bucket() == bucket && m.hasPrices()) stats = m;
                }
            }
            if (stats == null) {
                stats = new MarketStats(o.itemId(), bucket, 24 * 3_600_000L, 10, 0, 1, stamp,
                        sell, sell, sell, sell, sell, 1.0, 0, 0, 0.9, stamp);
            }
            Listing signal = new Listing("order:" + o.itemId() + ":" + count + ":" + o.unitPrice(), stamp, "", "order",
                    o.itemId(), o.itemId(), count, buy, null);
            double profit = sell - buy;
            out.add(new Opportunity(signal, stats, buy, sell, profit, profit / buy * 100.0, 0.1, 0.95, 0.9,
                    1_500 + profit * 100.0 / buy,
                    List.of("Order: pays " + o.unitPrice() + " each for " + o.remaining() + " more")));
        }
        return out;
    }

    private static boolean isOrderBacked(Opportunity o) {
        return o != null && o.listing() != null && o.listing().listingKey().startsWith("order:");
    }

    // ------------------------------------------------------------ the bid desk

    /** A bid's key on Your Orders: item and price each. */
    private record BidKey(String itemId, long unitPrice) {
    }

    private enum DeskAction { READ, COLLECT, CANCEL, PLACE }

    private record DeskStep(DeskAction action, String itemId, int count, long unitPrice, String why, int ordinal) {
        DeskStep(DeskAction action, String itemId, int count, long unitPrice, String why) {
            this(action, itemId, count, unitPrice, why, 0);
        }
    }

    private List<AutomatedExecutionDriver.OwnOrderRow> ownOrders = List.of();
    private long ownOrdersReadAt;
    private boolean deskReadDue;

    /** A fill or completion notice for one of our orders: the desk collects on its next turn, not in ten minutes. */
    public synchronized void observeOrderChat(String text) {
        if (text == null || dev.doughbay.fabric.Tuning.get("orders.bid") < 0.5) return;
        String t = text.strip().toLowerCase(java.util.Locale.ROOT);
        if (t.contains(" delivered you ") || t.endsWith("order is complete!")) {
            if (!deskReadDue) LOGGER.info("DoughBay bid desk: fill notice ({}); collecting on the next turn", text.strip());
            deskReadDue = true;
            int at = t.indexOf(" delivered you ");
            if (at >= 0) {
                String rest = text.strip().substring(at + " delivered you ".length()).strip();
                int space = rest.indexOf(' ');
                if (space > 0) {
                    String name = rest.substring(space + 1).strip();
                    // "64 Empty Maps while you were away" and a closing mark are not part of the name.
                    int away = name.toLowerCase(java.util.Locale.ROOT).indexOf(" while you were away");
                    if (away > 0) name = name.substring(0, away).strip();
                    while (!name.isEmpty() && !Character.isLetterOrDigit(name.charAt(name.length() - 1))) name = name.substring(0, name.length() - 1);
                    fillNoticed.add(name.toLowerCase(java.util.Locale.ROOT));
                    try {
                        int count = Integer.parseInt(rest.substring(0, space).replace(",", ""));
                        pendingFills.add(new Fill(name, count, System.currentTimeMillis()));
                        saveBidsAndFills();
                    } catch (NumberFormatException ignored) {
                        // a notice without a plain count is still a reason to read the page
                    }
                }
            }
        }
    }
    private final java.util.ArrayDeque<DeskStep> deskQueue = new java.util.ArrayDeque<>();
    private DeskStep deskStep;
    private final java.util.Map<BidKey, Long> bidPlacedAt = new java.util.HashMap<>();
    private final java.util.Map<BidKey, Integer> bidCollected = new java.util.HashMap<>();
    /** Collected counts per order key (item|price|requested), kept on disk: completed orders linger for months. */
    private final java.util.Map<String, Integer> collectedByKey = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> collectAttempts = new java.util.HashMap<>();
    private final java.util.Set<String> fillNoticed = new java.util.HashSet<>();
    private java.nio.file.Path collectedStore;

    private static String orderKey(String itemId, long unitPrice, int requested) {
        return itemId + "|" + unitPrice + "|" + requested;
    }

    /** A bid the desk placed: what it pays per item, for booking a fill that reaches the inventory by any route. */
    private record PlacedBid(String itemId, long unitPrice, int count, long placedAt) {}
    /** A fill the server announced ("shade515 delivered you 64 Empty Maps") that has not been booked yet. */
    private record Fill(String itemName, int count, long at) {}
    private final java.util.Map<String, PlacedBid> placedBids = new java.util.LinkedHashMap<>();
    private final List<Fill> pendingFills = new ArrayList<>();
    private final java.util.Map<String, Long> fillNoteAt = new java.util.HashMap<>();

    /**
     * Your Orders as the second record: an order that still shows delivered
     * but uncollected, with an exact stack of that count in the inventory,
     * was taken by hand. It is booked at the order's price and listed, no
     * page visit needed, so a full inventory never blocks its own emptying.
     */
    private boolean bookOwedFromInventory(Minecraft client, long now) {
        if (trackedPosition != null || client == null || client.player == null) return false;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
            String k = orderKey(r.itemId(), r.unitPrice(), r.requested());
            if (!seen.add(k)) continue;
            int pending = deliveredFor(k) - collectedByKey.getOrDefault(k, 0);
            if (pending <= 0) continue;
            if (unlistedPurchaseSlot(client, r.itemId(), pending) < 0) continue;
            LOGGER.info("DoughBay bid desk: your order for {} shows {} delivered and uncollected and the inventory holds that exact stack; booking at {} each",
                    r.itemId(), pending, r.unitPrice());
            collectedByKey.merge(k, pending, Integer::sum);
            saveCollected();
            consumeFills(r.itemId(), pending);
            return bookCollected(client, now, r.itemId(), pending, r.unitPrice());
        }
        return false;
    }

    private void saveBidsAndFills() {
        if (collectedStore == null) return;
        try {
            List<String> bids = new ArrayList<>();
            for (PlacedBid b : placedBids.values()) bids.add(b.itemId() + "|" + b.unitPrice() + "|" + b.count() + "|" + b.placedAt());
            java.nio.file.Files.write(collectedStore.resolveSibling("orders-bids.txt"), bids);
            List<String> fills = new ArrayList<>();
            for (Fill f : pendingFills) fills.add(f.itemName() + "|" + f.count() + "|" + f.at());
            java.nio.file.Files.write(collectedStore.resolveSibling("orders-fills.txt"), fills);
        } catch (Exception e) {
            LOGGER.warn("DoughBay could not save the bids and fills record: {}", e.toString());
        }
    }

    private void loadBidsAndFills() {
        placedBids.clear();
        pendingFills.clear();
        if (collectedStore == null) return;
        try {
            java.nio.file.Path bids = collectedStore.resolveSibling("orders-bids.txt");
            if (java.nio.file.Files.exists(bids)) {
                for (String line : java.nio.file.Files.readAllLines(bids)) {
                    String[] p = line.strip().split("[|]");
                    if (p.length < 4) continue;
                    placedBids.put(p[0], new PlacedBid(p[0], Long.parseLong(p[1]), Integer.parseInt(p[2]), Long.parseLong(p[3])));
                }
            }
            java.nio.file.Path fills = collectedStore.resolveSibling("orders-fills.txt");
            if (java.nio.file.Files.exists(fills)) {
                for (String line : java.nio.file.Files.readAllLines(fills)) {
                    String[] p = line.strip().split("[|]");
                    if (p.length < 3) continue;
                    String name = p[0].strip();
                    int away = name.toLowerCase(java.util.Locale.ROOT).indexOf(" while you were away");
                    if (away > 0) name = name.substring(0, away).strip();
                    pendingFills.add(new Fill(name, Integer.parseInt(p[1]), Long.parseLong(p[2])));
                }
            }
            if (!pendingFills.isEmpty()) LOGGER.info("DoughBay bid desk: {} fill(s) from earlier still to book", pendingFills.size());
        } catch (Exception e) {
            LOGGER.warn("DoughBay could not read the bids and fills record: {}", e.toString());
        }
    }

    /** The bid that a fill notice belongs to, by the item's display name; null when no bid of ours matches. */
    private PlacedBid bidForFill(Fill f) {
        for (PlacedBid b : placedBids.values()) {
            if (noticeNames(f.itemName(), AutomatedExecutionDriver.displayNameFor(b.itemId()))) return b;
        }
        return null;
    }

    /** Fills of an item that reached the inventory by a collect are no longer pending. */
    private void consumeFills(String itemId, int count) {
        String display = AutomatedExecutionDriver.displayNameFor(itemId);
        int left = count;
        for (java.util.Iterator<Fill> it = pendingFills.iterator(); it.hasNext() && left > 0; ) {
            Fill f = it.next();
            if (!noticeNames(f.itemName(), display)) continue;
            if (f.count() <= left) {
                left -= f.count();
                it.remove();
            } else {
                pendingFills.set(pendingFills.indexOf(f), new Fill(f.itemName(), f.count() - left, f.at()));
                left = 0;
            }
        }
        saveBidsAndFills();
    }

    /**
     * A fill the server announced whose order no longer owes it (taken by
     * hand, so the order is gone from the page) and whose stack sits in the
     * inventory: booked at the bid price and listed like any collected fill.
     * One per desk turn; the listing flow takes it from here.
     */
    private boolean bookHandCollectedFill(Minecraft client, long now) {
        if (trackedPosition != null || client == null || client.player == null) return false;
        // Fills are summed per item: five partial deliveries of blackstone
        // arrive as one merged stack, so a stack is booked when its count
        // fits inside what the notices for that item add up to.
        java.util.Map<String, Integer> owed = new java.util.LinkedHashMap<>();
        java.util.Map<String, PlacedBid> bids = new java.util.HashMap<>();
        // A copy: stale entries are dropped inside the loop.
        for (Fill f : new ArrayList<>(pendingFills)) {
            PlacedBid bid = bidForFill(f);
            if (bid == null) {
                // Nothing of ours explains it: somebody delivered to an order
                // we never placed, or the record was lost. Say so once, then
                // let it go, because a fill nobody can account for must not
                // hold the desk's bidding hostage for the rest of the session.
                if (now - f.at() > 30 * 60_000L) {
                    LOGGER.info("DoughBay bid desk: fill of {} x{} matches no bid of ours after 30 min; dropped",
                            f.itemName(), f.count());
                    pendingFills.remove(f);
                    saveBidsAndFills();
                    continue;
                }
                if (now - f.at() > 60_000 && now - fillNoteAt.getOrDefault(f.itemName(), 0L) > 60_000) {
                    fillNoteAt.put(f.itemName(), now);
                    LOGGER.info("DoughBay bid desk: fill of {} x{} matches none of the recorded bids", f.itemName(), f.count());
                }
                continue;
            }
            owed.merge(bid.itemId(), f.count(), Integer::sum);
            bids.put(bid.itemId(), bid);
        }
        List<ItemStack> stacks = client.player.getInventory().getNonEquipmentItems();
        for (var entry : owed.entrySet()) {
            String itemId = entry.getKey();
            int sum = entry.getValue();
            boolean owedOnPage = false;
            for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
                String k = orderKey(r.itemId(), r.unitPrice(), r.requested());
                if (r.itemId().equals(itemId) && deliveredFor(k) - collectedByKey.getOrDefault(k, 0) > 0) owedOnPage = true;
            }
            if (owedOnPage) continue;   // the collect path takes it from the order
            int best = -1;
            int larger = -1;
            for (int i = 0; i < stacks.size(); i++) {
                ItemStack st = stacks.get(i);
                if (st.isEmpty() || reservedSlot(i) || !itemId(st).equals(itemId)) continue;
                if (!AutomatedExecutionDriver.listableWithParts(st)) continue;
                if (st.getCount() > sum) {
                    if (larger < 0 || st.getCount() > stacks.get(larger).getCount()) larger = i;
                    continue;
                }
                if (best < 0 || st.getCount() > stacks.get(best).getCount()) best = i;
            }
            if (best < 0 && larger >= 0) {
                // The stack outgrew the notices (deliveries the record missed):
                // the whole stack is listed, and only the notified part is cost.
                int count = stacks.get(larger).getCount();
                PlacedBid bid = bids.get(itemId);
                LOGGER.info("DoughBay bid desk: {} x{} in the inventory against fills of {}; listing the stack, cost {} for the {} notified",
                        itemId, count, sum, bid.unitPrice() * sum, sum);
                consumeFills(itemId, sum);
                purchasedInventorySlot = larger;
                return bookCollected(client, now, itemId, count, bid.unitPrice(), bid.unitPrice() * (long) sum);
            }
            if (best < 0) {
                boolean none = true;
                for (ItemStack st : stacks) if (!st.isEmpty() && itemId(st).equals(itemId)) none = false;
                long newest = 0;
                for (Fill f : pendingFills) if (bidForFill(f) != null && bids.get(itemId) == bidForFill(f)) newest = Math.max(newest, f.at());
                if (none && now - newest > 30 * 60_000L) {
                    // Nothing of it anywhere for half an hour: the stack went out some other way.
                    LOGGER.info("DoughBay bid desk: fills of {} ({}) with none of it in the inventory for 30 min; dropped", itemId, sum);
                    consumeFills(itemId, sum);
                    continue;
                }
                if (now - fillNoteAt.getOrDefault(itemId, 0L) > 60_000) {
                    fillNoteAt.put(itemId, now);
                    List<String> held = new ArrayList<>();
                    for (ItemStack st : stacks) {
                        if (!st.isEmpty() && itemId(st).equals(itemId)) {
                            List<String> parts = new ArrayList<>();
                            for (var e : st.getComponentsPatch().entrySet()) parts.add(String.valueOf(e.getKey()));
                            held.add("x" + st.getCount() + parts);
                        }
                    }
                    LOGGER.info("DoughBay bid desk: fills of {} add up to {} but no stack of it fits; holding {}", itemId, sum, held);
                }
                continue;
            }
            int count = stacks.get(best).getCount();
            PlacedBid bid = bids.get(itemId);
            LOGGER.info("DoughBay bid desk: {} x{} from fills is in the inventory and no order still owes it; booking at {} each",
                    itemId, count, bid.unitPrice());
            consumeFills(itemId, count);
            purchasedInventorySlot = best;
            return bookCollected(client, now, itemId, count, bid.unitPrice());
        }
        // Last resort: a stack of something the desk bid on in the last day,
        // with no notice on record and no order owing it, is a fill whose
        // notice was missed. It is listed at the bid price rather than left.
        boolean trace = now - fillNoteAt.getOrDefault("#trace", 0L) > 60_000;
        if (trace) fillNoteAt.put("#trace", now);
        List<String> traceLines = new ArrayList<>();
        for (PlacedBid bid : placedBids.values()) {
            if (now - bid.placedAt() > 24 * 3_600_000L) continue;
            int held = 0;
            for (ItemStack st : stacks) if (!st.isEmpty() && itemId(st).equals(bid.itemId())) held += st.getCount();
            if (held == 0) continue;
            boolean owedOnPage = false;
            String owedBy = "";
            for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
                String k = orderKey(r.itemId(), r.unitPrice(), r.requested());
                if (r.itemId().equals(bid.itemId()) && deliveredFor(k) - collectedByKey.getOrDefault(k, 0) > 0) {
                    owedOnPage = true;
                    owedBy = k + " delivered " + deliveredFor(k) + " collected " + collectedByKey.getOrDefault(k, 0);
                }
            }
            if (owedOnPage) {
                traceLines.add(bid.itemId() + ": " + held + " held, skipped because an order still owes it (" + owedBy + ")");
                continue;
            }
            // Ask first whether this is ours to trade at all. bookCollected
            // refuses a denied or set-aside item and answers false, but by then
            // the desk has already announced it is listing it - and, finding the
            // stack still there next pass, announces it again. Five turtle
            // helmets bought into a denied market kept the desk saying "listing
            // it" several times a second for forty minutes while the client sold
            // nothing. The trident taught this once; the desk has its own path
            // to the same mistake.
            if (setAsideItems.contains(bid.itemId())
                    || (!dev.doughbay.fabric.Tuning.itemAllowed(bid.itemId())
                            && !dev.doughbay.fabric.Tuning.liquidating(bid.itemId()))) {
                traceLines.add(bid.itemId() + ": " + held + " held but the desk will not list what it is not "
                        + "allowed to trade; leaving it in the inventory");
                continue;
            }
            int best = -1;
            for (int i = 0; i < stacks.size(); i++) {
                ItemStack st = stacks.get(i);
                if (st.isEmpty() || reservedSlot(i) || !itemId(st).equals(bid.itemId())) continue;
                if (!AutomatedExecutionDriver.listableWithParts(st)) continue;
                if (best < 0 || st.getCount() > stacks.get(best).getCount()) best = i;
            }
            if (best < 0) {
                List<String> why = new ArrayList<>();
                for (ItemStack st : stacks) if (!st.isEmpty() && itemId(st).equals(bid.itemId())) why.add("x" + st.getCount() + AutomatedExecutionDriver.componentKeys(st));
                traceLines.add(bid.itemId() + ": " + held + " held but no listable stack " + why);
                continue;
            }
            int count = stacks.get(best).getCount();
            LOGGER.info("DoughBay bid desk: {} x{} is in the inventory and the desk bid on it at {} each with no notice on record; listing it",
                    bid.itemId(), count, bid.unitPrice());
            purchasedInventorySlot = best;
            return bookCollected(client, now, bid.itemId(), count, bid.unitPrice());
        }
        if (trace && !traceLines.isEmpty()) LOGGER.info("DoughBay bid desk: inventory rule left these: {}", traceLines);
        return false;
    }

    /** Items the bot has bought or collected in the last day, with the unit cost last paid: the catch-all for stray stacks. */
    private final java.util.Map<String, Long> recentTradeUnitCost = new java.util.HashMap<>();
    private long recentTradesLoadedAt;
    private java.nio.file.Path ledgerPath;

    public synchronized void setLedgerPath(java.nio.file.Path path) {
        ledgerPath = path;
    }

    private void refreshRecentTrades(long now) {
        if (ledgerPath == null || now - recentTradesLoadedAt < 30 * 60_000L) return;
        recentTradesLoadedAt = now;
        try (dev.doughbay.storage.Database db = new dev.doughbay.storage.Database(ledgerPath);
             var ps = db.connection().prepareStatement(
                     "SELECT item_key, quantity, purchase_price FROM positions WHERE mode = 'REAL' AND purchased_at > ? "
                             + "AND purchase_price > 0 AND quantity > 0 ORDER BY purchased_at")) {
            ps.setLong(1, now - 24 * 3_600_000L);
            try (var rs = ps.executeQuery()) {
                recentTradeUnitCost.clear();
                while (rs.next()) {
                    String key = rs.getString(1);
                    if (key.indexOf('#') >= 0) continue;
                    recentTradeUnitCost.put(key, Math.max(1, rs.getLong(3) / rs.getInt(2)));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("DoughBay could not read recent trades for the stray-stack rule: {}", e.toString());
        }
    }

    /**
     * The unit cost to book a held stack the bot owns but did not buy in the
     * last day - typically a collected order-fill whose position has aged out of
     * the 24h stray window, so it was never listed and just sat in the pack. An
     * order or bid it placed for the item gives the exact cost; failing that the
     * live market's median stands in. Null only when the item trades no market
     * here at all, so a stack the bot has no business with is left where it is.
     */
    private Long strayUnitCost(String id) {
        Long recent = recentTradeUnitCost.get(id);
        if (recent != null) return recent;
        PlacedBid bid = placedBids.get(id);
        if (bid != null && bid.unitPrice() > 0) return bid.unitPrice();
        for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
            if (r.itemId().equals(id) && r.unitPrice() > 0) return r.unitPrice();
        }
        return marketUnitCost(id);
    }

    /** A held item's per-unit value from the live market, or null if it trades no market here. */
    private Long marketUnitCost(String id) {
        MarketWatcher.Snapshot snap = lastSnapshot;
        if (snap == null) return null;
        for (MarketStats m : snap.markets()) {
            if (m.itemKey().indexOf('#') >= 0) continue;
            if (!baseItemId(m.itemKey()).equals(id)) continue;
            if (m.sampleCount() < 8 || !(m.weightedMedian() > 0)) continue;
            return (long) Math.max(1, Math.floor(m.weightedMedian() / Math.max(1, m.bucket().exactCount())));
        }
        return null;
    }

    private long lastInventoryFailsafeAt;
    /** While {@code now} is under this, the pack sweep lists everything sellable. */
    private long inventoryListUntil;

    /** Sweep the pack now: list every sellable stack, past the usual gates. From the key. */
    public synchronized void requestInventoryList() {
        inventoryListUntil = System.currentTimeMillis() + 5 * 60_000L;
        LOGGER.info("DoughBay automation: inventory list requested; sweeping the pack for unlisted stock");
    }

    /** A player Resume from the key: clears the player pause and resumes the right way. */
    public synchronized ExecutionResult resumeFromPlayer() {
        pausedByPlayer = false;
        if (recoveredSession) {
            return resumeRecoveredSession(dev.doughbay.fabric.DoughBayClient.continuousPolicy());
        }
        return resume();
    }

    /**
     * Lists the next sellable stack in the pack, past the gates {@link #bookStrayStack}
     * respects: it lists an item even if an order still claims to cover it, even
     * if it was blacklisted after it was bought (you own it now, so sell it), and
     * even if a set-aside once gave up on it. It still leaves the reserved slots
     * (tools, the chest) alone and still needs a real price, so nothing lists at a
     * guess. One stack a tick, until the pack is clear or the window closes.
     */
    private boolean forceListNextStack(Minecraft client, long now) {
        if (trackedPosition != null || client == null || client.player == null) return false;
        // The book is already full: a pack sweep here just opens the auction to
        // be told "too many listed items" and loops. Wait for room first.
        if (bookFullNow(now)) return false;
        refreshRecentTrades(now);
        List<ItemStack> stacks = client.player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack st = stacks.get(i);
            if (st.isEmpty() || reservedSlot(i)) continue;
            String id = itemId(st);
            if (!AutomatedExecutionDriver.listableWithParts(st)) continue;
            if (dev.doughbay.fabric.ItemDescriptor.of(st).hasParts()) continue;
            Long unit = strayUnitCost(id);
            if (unit == null) continue;   // no price anywhere; will not guess
            LOGGER.info("DoughBay automation: pack sweep listing {} x{} in slot {} at {} each",
                    id, st.getCount(), i, unit);
            purchasedInventorySlot = i;
            return bookCollected(client, now, id, st.getCount(), unit);
        }
        inventoryListUntil = 0;   // nothing left to list; end the sweep
        return false;
    }

    /**
     * The last line of defence against a stack that never lists: anything in
     * the inventory of an item the bot traded in the last day, that no order
     * owes and no fill notice covers, is booked at the unit cost last paid
     * for it and listed at market. The player's own items, which the bot
     * never traded, are never touched.
     */
    private boolean bookStrayStack(Minecraft client, long now) {
        if (trackedPosition != null || client == null || client.player == null) return false;
        refreshRecentTrades(now);
        List<ItemStack> stacks = client.player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack st = stacks.get(i);
            if (st.isEmpty() || reservedSlot(i)) continue;
            String id = itemId(st);
            // A stack the listing path has already given up on must not be
            // adopted again. Closing the position was not enough on its own:
            // this recovery saw the same trident sitting unaccounted for,
            // wrote a fresh position, the preparation failed, the position was
            // closed, and round it went - nineteen times before anyone noticed.
            if (setAsideItems.contains(id)) continue;
            // The deny list outlives a restart where the set above does not, so
            // an item we have decided not to trade is never adopted off the
            // floor either.
            if (!dev.doughbay.fabric.Tuning.itemAllowed(id)) continue;
            Long unit = strayUnitCost(id);
            if (unit == null || !AutomatedExecutionDriver.listableWithParts(st)) continue;
            if (dev.doughbay.fabric.ItemDescriptor.of(st).hasParts()) continue;
            boolean covered = false;
            String display = AutomatedExecutionDriver.displayNameFor(id);
            for (Fill f : pendingFills) if (noticeNames(f.itemName(), display)) covered = true;
            for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
                String k = orderKey(r.itemId(), r.unitPrice(), r.requested());
                if (r.itemId().equals(id) && deliveredFor(k) - collectedByKey.getOrDefault(k, 0) > 0) covered = true;
            }
            if (covered) continue;
            LOGGER.info("DoughBay automation: stray stack {} x{} in slot {} the bot owns but never listed; booking at {} each and listing",
                    id, st.getCount(), i, unit);
            purchasedInventorySlot = i;
            return bookCollected(client, now, id, st.getCount(), unit, unit * (long) st.getCount());
        }
        return false;
    }

    public synchronized void setCollectedStore(java.nio.file.Path path) {
        collectedStore = path;
        loadBidsAndFills();
        collectedByKey.clear();
        try {
            if (path != null && java.nio.file.Files.exists(path)) {
                for (String line : java.nio.file.Files.readAllLines(path)) {
                    int eq = line.lastIndexOf('=');
                    if (eq <= 0) continue;
                    collectedByKey.put(line.substring(0, eq).strip(), Integer.parseInt(line.substring(eq + 1).strip()));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("DoughBay could not read the collected-orders record: {}", e.toString());
        }
    }

    private void saveCollected() {
        if (collectedStore == null) return;
        List<String> lines = new ArrayList<>();
        for (var e : collectedByKey.entrySet()) lines.add(e.getKey() + "=" + e.getValue());
        try {
            java.nio.file.Files.write(collectedStore, lines);
        } catch (Exception e) {
            LOGGER.warn("DoughBay could not save the collected-orders record: {}", e.toString());
        }
    }

    private static boolean noticeNames(String noticeName, String displayName) {
        String n = noticeName.strip().toLowerCase(java.util.Locale.ROOT);
        String d = displayName.strip().toLowerCase(java.util.Locale.ROOT);
        if (d.isEmpty()) return false;
        if (n.equals(d) || n.equals(d + "s") || n.equals(d + "es")) return true;
        return d.endsWith("y") && n.equals(d.substring(0, d.length() - 1) + "ies");
    }

    /** Money sitting in the player's open bids: out of the bank, so counted against the cap like listings. */
    private long bidsOut() {
        long out = 0;
        for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) out += r.unitPrice() * r.remaining();
        return out;
    }

    public synchronized List<AutomatedExecutionDriver.OwnOrderRow> ownOrderViews() {
        return ownOrders;
    }

    public synchronized long ownOrdersReadAt() {
        return ownOrdersReadAt;
    }

    /** Empty slots in the main inventory and hotbar, the reserved slot not counted. */
    private int freeInventorySlots(Minecraft client) {
        if (client == null || client.player == null) return 0;
        List<ItemStack> stacks = client.player.getInventory().getNonEquipmentItems();
        int free = 0;
        for (int i = 0; i < stacks.size(); i++) {
            if (reservedSlot(i)) continue;
            if (stacks.get(i).isEmpty()) free++;
        }
        return free;
    }

    /** How often each gate turned the desk away, so a starved desk is visible. */
    private final java.util.Map<String, Integer> deskGate = new java.util.LinkedHashMap<>();
    private long deskGateAt;

    private boolean deskBlocked(String gate) {
        bump(deskGate, gate);
        return false;
    }

    /** Says what has been keeping the desk from its queue, at most every two minutes. */
    private void reportDeskGate(long now) {
        if (deskGate.isEmpty() || now - deskGateAt < 120_000L) return;
        deskGateAt = now;
        LOGGER.info("DoughBay bid desk: {} step(s) waiting; turned away by {}", deskQueue.size(), deskGate);
        deskGate.clear();
    }

    private boolean startDeskAction(Minecraft client, MarketWatcher.Snapshot snapshot, long now) {
        reportDeskGate(now);
        // The desk runs when it is bidding, or when it is only collecting filled
        // orders: reading Your Orders and taking what is fully delivered needs no
        // bidding, so a client that never places a bid still sweeps its own slots.
        if (dev.doughbay.fabric.Tuning.get("orders.bid") < 0.5
                && dev.doughbay.fabric.Tuning.get("orders.collect") < 0.5) return false;
        if (deskQueue.isEmpty() && ownOrdersReadAt == 0) deskReadDue = true;   // the first read of a session comes first
        if (deskQueue.isEmpty()) {
            if (!deskReadDue) return false;
            deskReadDue = false;
            deskQueue.addLast(new DeskStep(DeskAction.READ, "", 0, 0, "reading your orders"));
        }
        // The auction lane holds a position from the moment it buys until the
        // stack is listed and gone. If that is nearly always, the desk never
        // gets a turn however many slots and candidates it has.
        if (trackedPosition != null) return deskBlocked("auction lane holds a stack");
        if (repricing != null) return deskBlocked("repricing");
        // Stacks already in the inventory are listed before more is ordered,
        // and nothing is collected or bid for while there is no room to land it.
        DeskStep head = deskQueue.peekFirst();
        boolean ourFillsWaiting = false;
        for (Fill f : pendingFills) if (bidForFill(f) != null) ourFillsWaiting = true;
        if (head != null && head.action() == DeskAction.PLACE && ourFillsWaiting) {
            // Collect and list what has already arrived before ordering more,
            // but do not throw the bids away: they were thrown away here, and
            // since a bid fills within minutes there was nearly always a fill
            // outstanding, so the desk planned eight bids, binned all eight,
            // and read the page again. Three bids stood where ninety could.
            java.util.List<DeskStep> places = new ArrayList<>();
            for (java.util.Iterator<DeskStep> it = deskQueue.iterator(); it.hasNext(); ) {
                DeskStep st = it.next();
                if (st.action() == DeskAction.PLACE) {
                    places.add(st);
                    it.remove();
                }
            }
            deskQueue.addAll(places);   // behind the collects, not deleted
            // Only wait when there is actually something to collect first.
            // Holding the bids whenever any fill was outstanding deadlocked a
            // queue of nothing but bids: moving them to the back leaves a bid
            // at the front again, so the desk refused itself every pass and
            // twelve planned bids produced one placement an hour. A fill that
            // has not become collectable yet gets its COLLECT on the next
            // read; the bids need not stop for it.
            head = deskQueue.peekFirst();
            if (head != null && head.action() == DeskAction.PLACE) {
                bump(deskGate, "bidding past a fill that has no collect yet");
            }
        }
        if (head != null && (head.action() == DeskAction.COLLECT || head.action() == DeskAction.PLACE)) {
            int free = freeInventorySlots(client);
            int need = head.action() == DeskAction.COLLECT ? 2 : 3;
            if (free < need) {
                deskQueue.removeIf(st -> st.action() == DeskAction.COLLECT || st.action() == DeskAction.PLACE);
                if (!("Inventory has " + free + " free slot(s); the bid desk waits for room").equals(detail)) {
                    LOGGER.info("DoughBay bid desk: inventory has {} free slot(s); collects and bids wait for room", free);
                }
                detail = "Inventory has " + free + " free slot(s); the bid desk waits for room";
                return deskBlocked("no inventory room");
            }
        }
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) {
            return deskBlocked("driver busy: " + driver.operationIntent());
        }
        if (dev.doughbay.fabric.ServerStrain.holding()) {
            return deskBlocked(dev.doughbay.fabric.ServerStrain.describe());
        }
        if (closeStrayAuctionPage(client)) return true;
        if (modScreenOpen(client)) {
            client.gui.setScreen(null);
            detail = "Closing the GoNuts screen for the bid desk";
            return true;
        }
        if (!safeInventoryContext(client)) return deskBlocked("not a safe moment");
        DeskStep step = deskQueue.pollFirst();
        awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
        ExecutionResult result = switch (step.action()) {
            case READ -> driver.readOwnOrders();
            case COLLECT -> driver.collectOrder(step.itemId(), step.unitPrice(), step.ordinal());
            case CANCEL -> driver.cancelOrder(step.itemId(), step.unitPrice());
            case PLACE -> driver.placeOrder(step.itemId(), step.count(), step.unitPrice());
        };
        if (!result.ok()) {
            LOGGER.info("DoughBay bid desk: {} could not start: {}", step.action(), result.detail());
            detail = "Bid desk: " + step.action() + " could not start (" + result.detail() + ")";
            return false;
        }
        deskStep = step;
        transition(State.BID_DESK, "Bid desk: " + step.why());
        return true;
    }

    private void observeDeskTerminal(Minecraft client, long now) {
        DeskStep step = deskStep;
        if (step == null) {
            transition(State.SCANNING, "Bid desk idle; scanning");
            return;
        }
        AutomatedExecutionDriver.OperationIntent wanted = switch (step.action()) {
            case READ -> AutomatedExecutionDriver.OperationIntent.OWN_ORDERS;
            case COLLECT -> AutomatedExecutionDriver.OperationIntent.COLLECT_ORDER;
            case CANCEL -> AutomatedExecutionDriver.OperationIntent.CANCEL_ORDER;
            case PLACE -> AutomatedExecutionDriver.OperationIntent.PLACE_ORDER;
        };
        AutomatedExecutionDriver.TerminalEvent event = nextTerminal(wanted);
        if (event == null) {
            if (now - stateChangedAtMillis > 45_000) {
                LOGGER.info("DoughBay bid desk: {} on {} made no progress for 45 s; abandoning it", step.action(), step.itemId());
                driver.abandon("Bid desk " + step.action() + " made no progress for 45 s");
            }
            return;
        }
        deskStep = null;
        boolean ok = event.intent() == wanted && event.outcome() == AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED;
        if (!ok) {
            LOGGER.info("DoughBay bid desk: {} did not complete: {}", step.action(), event.detail());
            if (step.action() == DeskAction.READ) deskQueue.clear();
            // One failed placement means the page is not what the desk
            // expects; the rest wait for the next read rather than failing in turn.
            if (step.action() == DeskAction.PLACE) {
                // Only this item. Binning every queued bid was right when the
                // cause was always a page in the wrong state, but an order for
                // an enchantable item opens a "choose enchantments" dialog the
                // desk cannot answer, and that one item used to delete a queue
                // of thirty-eight good bids with it. Three in a row still means
                // the page itself is wrong, and then the batch goes.
                deskQueue.removeIf(q -> q.action() == DeskAction.PLACE && q.itemId().equals(step.itemId()));
                deskBadItems.add(step.itemId());
                if (++deskPlaceFailures >= 3) {
                    deskQueue.removeIf(q -> q.action() == DeskAction.PLACE);
                    deskPlaceFailures = 0;
                    LOGGER.info("DoughBay bid desk: three placements failed in a row; the page is wrong, dropping the batch");
                } else {
                    LOGGER.info("DoughBay bid desk: {} will not take an order ({}); skipping it and keeping {} other bid(s)",
                            step.itemId(), event.detail(),
                            deskQueue.stream().filter(q -> q.action() == DeskAction.PLACE).count());
                }
            }
            if (step.action() == DeskAction.CANCEL && event.detail() != null
                    && event.detail().contains(AutomatedExecutionDriver.FILL_WAITING)) {
                // Not a failure: the page told us the order is owed to us. Go
                // and take it instead, and do not count this against the
                // cancel attempts - the cancel was simply the wrong question.
                LOGGER.info("DoughBay bid desk: {} at {} each has a delivery waiting; collecting instead of cancelling",
                        step.itemId(), step.unitPrice());
                deskQueue.addFirst(new DeskStep(DeskAction.COLLECT, step.itemId(),
                        step.count(), step.unitPrice(), "a delivery was waiting"));
                transition(State.SCANNING, "Bid desk: an order had a delivery waiting; collecting it");
                return;
            }
            if (step.action() == DeskAction.CANCEL) {
                String key = orderKey(step.itemId(), step.unitPrice(), step.count());
                int tries = cancelAttempts.merge(key, 1, Integer::sum);
                if (tries >= 3) {
                    deskUncancellable.add(key);
                    LOGGER.warn("DoughBay bid desk: the order for {} at {} each will not cancel after {} tries ({}); "
                            + "leaving it alone. It expires on the server by itself",
                            step.itemId(), step.unitPrice(), tries, event.detail());
                }
            }
            if (step.action() == DeskAction.COLLECT && event.detail() != null && event.detail().contains("Nothing arrived")) {
                collectedNothing(client, now, step);
                if (trackedPosition != null) return;   // a hand-collected stack was booked and is being listed
            } else if (step.action() == DeskAction.COLLECT) {
                String k = orderKey(step.itemId(), step.unitPrice(), step.count());
                if (event.detail() != null && event.detail().contains("not on Your Orders")) {
                    // The order left the page between the read that queued this
                    // collect and the collect itself - taken, completed, or
                    // expired. There is nothing to collect, so stop chasing it
                    // rather than walking back to an empty page three more times.
                    collectAttempts.put(k, 4);
                    LOGGER.info("DoughBay bid desk: order for {} at {} is no longer on Your Orders; not chasing it again",
                            step.itemId(), step.unitPrice());
                } else {
                    collectAttempts.merge(k, 1, Integer::sum);
                }
            }
            transition(State.SCANNING, "Bid desk: " + step.action() + " did not complete; scanning");
            return;
        }
        switch (step.action()) {
            case READ -> {
                ownOrders = List.copyOf(driver.lastOwnOrders());
                ownOrdersReadAt = now;
                planDesk(lastSnapshot, now);
                LOGGER.info("DoughBay bid desk: {} bid(s) open holding {}; {} step(s) queued",
                        ownOrders.size(), bidsOut(), deskQueue.size());
                if (bookOwedFromInventory(client, now) || bookHandCollectedFill(client, now)) return;   // listing it now; the queue waits
            }
            case PLACE -> {
                deskPlaceFailures = 0;   // a placement that worked ends the run of failures
                bidPlacedAt.put(new BidKey(step.itemId(), step.unitPrice()), now);
                placedBids.put(step.itemId(), new PlacedBid(step.itemId(), step.unitPrice(), step.count(), now));
                saveBidsAndFills();
                LOGGER.info("DoughBay bid desk: bid placed for {} x{} at {} each", step.itemId(), step.count(), step.unitPrice());
            }
            case CANCEL -> {
                bidPlacedAt.remove(new BidKey(step.itemId(), step.unitPrice()));
                PlacedBid placed = placedBids.get(step.itemId());
                if (placed != null && placed.unitPrice() == step.unitPrice()) {
                    placedBids.remove(step.itemId());
                    saveBidsAndFills();
                }
                LOGGER.info("DoughBay bid desk: bid cancelled: {} at {} each ({})", step.itemId(), step.unitPrice(), step.why());
            }
            case COLLECT -> {
                int count = event.itemCount();
                BidKey key = new BidKey(step.itemId(), step.unitPrice());
                bidCollected.merge(key, count, Integer::sum);
                collectedByKey.merge(orderKey(step.itemId(), step.unitPrice(), step.count()), count, Integer::sum);
                saveCollected();
                if (count > 0) consumeFills(step.itemId(), count);
                if (count > 0) bookCollected(client, now, step.itemId(), count, step.unitPrice());
                return;   // bookCollected moves the session on
            }
        }
        transition(State.SCANNING, "Bid desk: " + step.action() + " done; scanning");
    }

    /**
     * What the desk does after a read: collect what arrived, pull bids the
     * market moved away from or that sat too long, then place new ones on
     * the busiest markets while slots and money allow.
     */
    /**
     * The order-house floor probe: posts a ladder of buy orders at the prices
     * in {@code probe.json}, then cancels whatever did not fill once its TTL is
     * up. Idempotent, so it survives {@link #planDesk} clearing the queue every
     * cycle - each pass re-adds only the rungs not yet on the page. Off unless
     * the file is present and armed for this client. See {@link Probe}.
     */
    private void enqueueProbe(long now) {
        dev.doughbay.fabric.Probe pr = dev.doughbay.fabric.Probe.get();
        if (pr == null) return;
        pr.reload();
        if (!pr.armedFor(dev.doughbay.fabric.DoughBayClient.account())) return;
        String item = pr.item();
        if (!pr.placed()) {
            pr.markPlaced(now);   // start the TTL clock the first armed cycle
            LOGGER.info("DoughBay probe: arming {} rung(s) on {} qty {} at {}",
                    pr.prices().size(), item, pr.quantity(), pr.prices());
        }
        if (!pr.ttlExpired(now)) {
            // Placement: re-add only rungs that are not already on the page.
            for (long price : pr.prices()) {
                boolean live = false;
                for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
                    if (r.itemId().equals(item) && r.unitPrice() == price) { live = true; break; }
                }
                if (!live) deskQueue.addLast(new DeskStep(DeskAction.PLACE, item, pr.quantity(), price, "PROBE $" + price));
            }
        } else {
            // TTL up: cancel every rung that is still live and never got a fill.
            boolean anyLeft = false;
            for (long price : pr.prices()) {
                for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
                    if (r.itemId().equals(item) && r.unitPrice() == price
                            && r.remaining() > 0 && r.delivered() == 0) {
                        deskQueue.addLast(new DeskStep(DeskAction.CANCEL, item, 0, price, "PROBE cancel"));
                        anyLeft = true;
                        break;
                    }
                }
            }
            if (!anyLeft) {
                pr.markDone();
                LOGGER.info("DoughBay probe: run complete; unfilled rungs cancelled, filled rungs kept");
            }
        }
    }

    private void planDesk(MarketWatcher.Snapshot snapshot, long now) {
        deskQueue.clear();
        boolean bidding = dev.doughbay.fabric.Tuning.get("orders.bid") >= 0.5;
        if (bidding) enqueueProbe(now);
        double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
        // The desk's own margin floor sits over the global ROI: an order fills
        // as the price drops into it, so it needs more room than an auction buy
        // to not relist at breakeven. bid_min_margin_pct at 0 leaves it as the
        // global ROI alone.
        double minimumRoi = Math.max(
                Math.max(policy.minimumRoiPercent(), riskConfig.minimumRoiPercent()),
                dev.doughbay.fabric.Tuning.get("orders.bid_min_margin_pct")) / 100.0;
        long maxAge = (long) (dev.doughbay.fabric.Tuning.get("orders.bid_max_hours") * 3_600_000L);
        java.util.Set<String> bidItems = new java.util.HashSet<>();
        // Identical orders share a key: a collected completed order lingers on
        // the page for months next to a fresh one, so delivered counts are
        // summed per key and measured against what was collected under it.
        java.util.Map<String, Integer> deliveredByKey = new java.util.LinkedHashMap<>();
        java.util.Map<String, Boolean> completeByKey = new java.util.HashMap<>();
        for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
            String k = orderKey(r.itemId(), r.unitPrice(), r.requested());
            deliveredByKey.merge(k, r.delivered(), Integer::sum);
            if (r.remaining() == 0) completeByKey.put(k, true);
        }
        java.util.Set<String> collectQueued = new java.util.HashSet<>();
        java.util.Map<String, Integer> fillSum = new java.util.HashMap<>();
        for (Fill f : pendingFills) {
            PlacedBid b = bidForFill(f);
            if (b != null) {
                bidItems.add(b.itemId());
                fillSum.merge(b.itemId(), f.count(), Integer::sum);
            }
        }
        // The collected record is per key, and a new order can share a key
        // with an old, collected one that has since left the page. The page
        // and the fill notices outrank the record: a count above what the
        // page delivers is clamped, and a fill still pending for the item
        // means the current order has not been emptied.
        boolean recordChanged = false;
        for (var e : deliveredByKey.entrySet()) {
            String k = e.getKey();
            int delivered = e.getValue();
            int collected = collectedByKey.getOrDefault(k, 0);
            String item = k.substring(0, k.indexOf('|'));
            int owed = fillSum.getOrDefault(item, 0);
            int corrected = Math.min(collected, delivered);
            if (owed > 0 && corrected >= delivered) corrected = Math.max(0, delivered - owed);
            if (corrected != collected) {
                LOGGER.info("DoughBay bid desk: collected record for {} corrected from {} to {} ({} delivered on the page, {} in fill notices)",
                        k, collected, corrected, delivered, owed);
                collectedByKey.put(k, corrected);
                recordChanged = true;
            }
        }
        if (recordChanged) saveCollected();
        for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
            if (r.remaining() > 0) bidItems.add(r.itemId());
            String pendingKey = orderKey(r.itemId(), r.unitPrice(), r.requested());
            if (deliveredByKey.getOrDefault(pendingKey, 0) - collectedByKey.getOrDefault(pendingKey, 0) > 0) bidItems.add(r.itemId());
            String k = orderKey(r.itemId(), r.unitPrice(), r.requested());
            int collected = collectedByKey.getOrDefault(k, 0);
            int pending = deliveredByKey.getOrDefault(k, 0) - collected;
            boolean listablePart = pending >= 16 || completeByKey.getOrDefault(k, false);
            if (pending > 0 && listablePart && collectQueued.add(k) && collectAttempts.getOrDefault(k, 0) < 4) {
                deskQueue.addLast(new DeskStep(DeskAction.COLLECT, r.itemId(), r.requested(), r.unitPrice(),
                        "collecting " + pending + " " + r.itemId(), collectAttempts.getOrDefault(k, 0)));
            }
            if (r.remaining() == 0) continue;   // completed orders cannot be cancelled; they age off the page
            BidKey key = new BidKey(r.itemId(), r.unitPrice());
            long placedAt = bidPlacedAt.getOrDefault(key, now - Math.max(0, 7 * 24 * 3_600_000L - Math.max(0, r.expiresInMillis())));
            long ceilingUnit = bidCeilingUnit(snapshot, r.itemId(), r.requested(), minimumProfit, minimumRoi);
            // An item taken off the allow list (or added to the deny list) after
            // its order went out kept its bid open until it filled or aged off,
            // so a freshly blacklisted item like a trident still traded. Pull it.
            boolean denied = !dev.doughbay.fabric.Tuning.itemAllowed(r.itemId());
            String why = denied ? "no longer allowed (blacklisted)"
                    : r.remaining() == 0 ? "filled"
                    : now - placedAt > maxAge ? "unfilled for " + ((now - placedAt) / 3_600_000L) + " h"
                    : ceilingUnit > 0 && r.unitPrice() > ceilingUnit ? "market moved under the bid (" + ceilingUnit + " each now)"
                    : null;
            // The server has already closed this one and is only holding the
            // goods. There is nothing to cancel and the page will not offer it;
            // what it offers is Collect, and what we are owed is the delivered
            // part. Trying to cancel it was the whole Edit Order hang.
            if (r.alreadyCancelled()) {
                if (r.delivered() > 0) {
                    deskQueue.addLast(new DeskStep(DeskAction.COLLECT, r.itemId(),
                            r.delivered(), r.unitPrice(), "the order was cancelled with goods still on it"));
                }
                continue;
            }
            // A blacklisted item is pulled even in collect-only mode; everything
            // else is only cancelled while the desk is actively bidding.
            if ((bidding || denied) && why != null && pending <= 0
                    && !deskUncancellable.contains(orderKey(r.itemId(), r.unitPrice(), 0))) {
                deskQueue.addLast(new DeskStep(DeskAction.CANCEL, r.itemId(), 0, r.unitPrice(), why));
            }
        }
        // Collect-only: the fills to collect and any goods from a cancelled
        // order are queued above; everything past here places new bids, which a
        // desk that is not bidding must not do.
        if (!bidding) return;
        int slots = (int) dev.doughbay.fabric.Tuning.get("orders.bid_slots");
        int stacks = (int) dev.doughbay.fabric.Tuning.get("orders.bid_stacks");
        if (quietMarket) {
            // Orders pay a price we set, so a slow auction is the desk's hour.
            double boost = Math.max(1.0, dev.doughbay.fabric.Tuning.get("quiet.bid_boost"));
            slots = (int) Math.min(90, Math.round(slots * boost));
        }
        double minDemand = dev.doughbay.fabric.Tuning.get("orders.bid_min_sales_per_hour");
        long room = effectiveSpendCap() - committedSpend - bidsOut();
        // The desk buys at a price we set, so it is the better use of borrowed
        // money than the auction is. A client out of room asks the hive here
        // too, and the room only widens once a sibling has actually paid.
        if (room < dev.doughbay.fabric.Tuning.get("orders.bid_min_room")) {
            room += borrowedHeadroom(Math.round(dev.doughbay.fabric.Tuning.get("orders.bid_min_room")) - room);
        }
        int active = 0;
        for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) if (r.remaining() > 0) active++;
        int free = slots - active;
        if (snapshot == null || free <= 0) return;
        // A desk with ninety free slots that places two bids in twenty minutes
        // is being stopped by something, and until the reasons are counted the
        // only honest answer to "why so few orders" is a guess. Every gate
        // below keeps a tally, printed once per run when nothing went out.
        List<MarketStats> candidates = new ArrayList<>();
        java.util.Map<String, Integer> why = new java.util.LinkedHashMap<>();
        for (MarketStats m : snapshot.markets()) {
            if (!m.hasPrices() || m.itemKey().indexOf('#') >= 0) { bump(why, "no prices"); continue; }
            if (bidItems.contains(m.itemKey())) { bump(why, "bid already out"); continue; }
            if (deskBadItems.contains(m.itemKey())) { bump(why, "server refuses an order for it"); continue; }
            if (!dev.doughbay.fabric.Tuning.itemAllowed(m.itemKey())) { bump(why, "not allowed"); continue; }
            // Single-item statistics on cheap goods are polluted by "sales" that
            // are really money transfers (one dirt for $10K); bids price off
            // stack markets, and off singles only where a single is the unit.
            if (m.bucket() != StackBucket.X64 && m.bucket() != StackBucket.X16
                    && !(m.bucket() == StackBucket.X1 && m.weightedMedian() >= 20_000)) {
                bump(why, "cheap single"); continue;
            }
            // A stack's quick-sale value is roughly what the order would tie up.
            // Below the floor, the fill drifts to breakeven and dusts the book;
            // above the cap, one order can sink a large sum the way the high
            // piglin-head bid did. Both off (0) leave the desk as it was.
            double stackValue = m.quickSalePrice();
            double bidMinValue = dev.doughbay.fabric.Tuning.get("orders.bid_min_value");
            double bidMaxValue = dev.doughbay.fabric.Tuning.get("orders.bid_max_value");
            if (bidMinValue > 0 && stackValue < bidMinValue) { bump(why, "below bid value floor"); continue; }
            if (bidMaxValue > 0 && stackValue > bidMaxValue) { bump(why, "above bid value cap"); continue; }
            if (!(m.salesPerHour() >= minDemand)) { bump(why, "too slow"); continue; }
            // 0.4 and 30 were guesses made before there was any history to
            // check them against. Seven days of it says markets under 0.4
            // confidence sold 3,111 stacks for $28.6m at a better return and
            // a shorter hold than the ones above it, so the numbers belong in
            // the settings where the ledger can keep arguing with them.
            if (m.confidence() < dev.doughbay.fabric.Tuning.get("orders.bid_min_confidence")) {
                bump(why, "low confidence"); continue;
            }
            if (m.sampleCount() < dev.doughbay.fabric.Tuning.get("orders.bid_min_samples")) {
                bump(why, "too few samples"); continue;
            }
            if (Double.isFinite(m.trend()) && m.trend() < -0.05) { bump(why, "falling"); continue; }
            candidates.add(m);
        }
        // Sellers deliver to the highest bid first, so a bid only goes out where
        // ours would be the top bid; the widest gap between that top bid and
        // the auction's ask is the biggest margin, so those markets come first.
        java.util.Map<String, Long> topBid = new java.util.HashMap<>();
        dev.doughbay.fabric.OrderBook book = dev.doughbay.fabric.DoughBayClient.orderBook();
        if (book != null) {
            for (dev.doughbay.fabric.OrderBook.Order o : book.orders()) {
                if (o.parts().isEmpty() && o.remaining() > 0) topBid.merge(o.itemId(), o.unitPrice(), Math::max);
            }
        }
        candidates.sort((a, b) -> {
            double gapA = 1.0 - topBid.getOrDefault(a.itemKey(), 0L) / Math.max(1.0, a.quickSalePrice() / Math.max(1, a.bucket().exactCount()));
            double gapB = 1.0 - topBid.getOrDefault(b.itemKey(), 0L) / Math.max(1.0, b.quickSalePrice() / Math.max(1, b.bucket().exactCount()));
            return Double.compare(gapB * b.salesPerHour(), gapA * a.salesPerHour());
        });
        // The underdog's own slots. Our statistics say nothing about a market
        // we have never traded, so it can never pass the gates above; the
        // rival's completed flips are the evidence instead. These bids go to
        // markets a rival works profitably and we are absent from, which is
        // how the book widens rather than deepening what it already holds.
        int underdogSlots = (int) dev.doughbay.fabric.Tuning.get("underdog.order_slots");
        int oldGround = 0;
        java.util.Set<String> chosen = new java.util.HashSet<>();
        if (underdogSlots > 0 && dev.doughbay.fabric.Tuning.get("underdog.enabled") >= 0.5) {
            dev.doughbay.fabric.RivalIntel.Snapshot rs = rivals();
            if (rs != null) {
                double undercut = dev.doughbay.fabric.Tuning.get("underdog.undercut_pct") / 100.0;
                for (dev.doughbay.fabric.RivalIntel.ShadowMarket m : rs.shadows()) {
                    if (free <= 0 || underdogSlots <= 0) break;
                    if (!dev.doughbay.fabric.Tuning.itemAllowed(m.itemId())) continue;
                    if (bidItems.contains(m.itemId()) || !chosen.add(m.itemId())) continue;
                    if (deskBadItems.contains(m.itemId())) continue;
                    // No second bid and no doubled exposure in the same item
                    // and stack size, whatever the rule below says.
                    if (openOfMarket(m.itemId(), m.count()) > 0) continue;
                    // Old ground. Held to the letter this excluded every market
                    // we had sold in for a day - 94 of them - which left the
                    // underdog five markets to work. A buy order in a market we
                    // already flip is not the same trade twice: it is the same
                    // item bought cheaper, through a channel the auction page
                    // does not reach.
                    if (dev.doughbay.fabric.Tuning.get("underdog.new_markets_only") >= 0.5
                            && weTradeThisAlready(m.itemId())) {
                        oldGround++;
                        continue;
                    }
                    long standing = topBid.getOrDefault(m.itemId(), 0L);
                    // Pay under what the rival pays, and stay ahead of the book.
                    long unit = Math.round(m.medianBuy() * (1.0 - undercut) / Math.max(1, m.count()));
                    if (unit <= 0) continue;
                    // A rival's flip is evidence of a price, not of a stack
                    // size the game allows; an order for more than a stack
                    // cannot be delivered.
                    int askFor = Math.min(m.count(), fullStack(m.itemId()));
                    if (askFor <= 0) continue;
                    if (standing > 0 && unit < standing * 1.01) continue;
                    if (standing > 0 && unit > standing * 5) continue;
                    long cost = unit * askFor;
                    if (cost > effectivePurchaseCap() || cost > room) continue;
                    room -= cost;
                    free--;
                    underdogSlots--;
                    LOGGER.info("DoughBay underdog: bidding into {} x{}, a market {} works at {} -> {} and we do not",
                            m.itemId(), m.count(), m.rival(), m.medianBuy(), m.medianSale());
                    deskQueue.addLast(new DeskStep(DeskAction.PLACE, m.itemId(), askFor, unit,
                            "underdog bid " + unit + " each for " + askFor + " " + m.itemId()
                                    + " (" + m.rival() + " sells at " + m.medianSale() + ")"));
                }
                if (oldGround > 0) {
                    LOGGER.info("DoughBay underdog: {} shadowed market(s) skipped as old ground", oldGround);
                }
            }
        }
        int placed = 0;
        for (MarketStats m : candidates) {
            if (free <= 0) break;
            if (!chosen.add(m.itemKey())) continue;
            int per = m.bucket() == StackBucket.X64 ? 64 : m.bucket() == StackBucket.X16 ? 16 : 1;
            int full = fullStack(m.itemKey());
            // The bucket our statistics happened to sample is not the size the
            // market trades in. Order a whole stack where the auction prices
            // one, and never order more than a stack of anything.
            if (full > per && bidCeilingUnit(snapshot, m.itemKey(), full, minimumProfit, minimumRoi) > 0) {
                per = full;
            }
            per = Math.min(per, full);
            long unit = bidCeilingUnit(snapshot, m.itemKey(), per, minimumProfit, minimumRoi);
            if (unit <= 0) { bump(why, "no resale price"); continue; }
            // A stack of a million-a-unit item never fits the per-trade cap, so
            // the desk skips it for ever. Where even one stack is too dear, bid
            // a single at a time: the same market worked one unit per order,
            // which is the whole point for ancient debris and netherite ingots.
            // The fill is then listed on the auction like any other collected
            // stock, one at a time, at the single's resale price.
            if (per > 1 && unit * (long) per > effectivePurchaseCap()) {
                long single = bidCeilingUnit(snapshot, m.itemKey(), 1, minimumProfit, minimumRoi);
                if (single <= 0 || single > effectivePurchaseCap()) { bump(why, "over the buy cap"); continue; }
                per = 1;
                unit = single;
            }
            long standing = topBid.getOrDefault(m.itemKey(), 0L);
            if (standing > 0 && unit < standing * 1.01) { bump(why, "outbid already"); continue; }
            // Far above every standing bid means the statistic is wrong, not
            // that the market is generous: a polluted single-item median.
            if (standing > 0 && unit > standing * 5) { bump(why, "price looks wrong"); continue; }
            if (standing > 0) unit = Math.min(unit, Math.max((long) (standing * 1.05), unit * 3 / 4));
            // The auction gate holds a market to a few open listings, but a
            // filled bid landed as three stacks of the same thing on top of
            // whatever was already up - which is how twenty-six white banners
            // came to queue behind each other at cost. The same cap applies
            // here: no bid into a market already at it, and a bid only for
            // the stacks the cap still leaves room for.
            int roomInMarket = MAX_OPEN_PER_MARKET() - openOfMarket(m.itemKey(), per);
            if (roomInMarket <= 0) { bump(why, "market already at its listing cap"); continue; }
            int count = per * Math.min(stacks, roomInMarket);
            // Ask for what this market can actually absorb. A flat three
            // stacks is a hundred and ninety-two units of everything, and
            // volume falls about four hundredfold from the cheap end of the
            // economy to the expensive end: sixteen minutes of demand in one
            // market and five days of it in another, from this same line. The
            // five-day end is where the losses came from - a stack of
            // templates is twenty hours of single-unit demand bought at once.
            double sharePct = dev.doughbay.fabric.Tuning.get("orders.depth_share_pct");
            dev.doughbay.fabric.StackProfile profile = dev.doughbay.fabric.DoughBayClient.stackProfile();
            dev.doughbay.fabric.StackProfile.Shape shape =
                    profile == null ? null : profile.shapeOf(m.itemKey());
            if (shape != null && sharePct < 100) {
                int allowed = shape.maxUnits(sharePct);
                if (allowed < count) {
                    if (allowed < per) { bump(why, "thinner than one stack"); continue; }
                    count = (allowed / per) * per;
                }
            }
            long cost = unit * count;
            if (cost > effectivePurchaseCap()) { bump(why, "over the buy cap"); continue; }
            if (cost > room) { bump(why, "no room left"); continue; }
            room -= cost;
            free--;
            placed++;
            deskQueue.addLast(new DeskStep(DeskAction.PLACE, m.itemKey(), count, unit,
                    "bidding " + unit + " each for " + count + " " + m.itemKey() + " (quick sale " + Math.round(m.quickSalePrice()) + ")"));
        }
        if (placed == 0 && free > 0 && !why.isEmpty() && now - deskWhyAt > 5 * 60_000L) {
            deskWhyAt = now;
            LOGGER.info("DoughBay bid desk: {} free slot(s), no bid placed; {} market(s) considered, stopped by {}",
                    free, snapshot.markets().size(), why);
        }
    }

    /**
     * Closes a position whose stack no longer exists, so it is never recovered
     * and prepared again.
     *
     * <p>Nothing is claimed to have sold: the money went out when it was bought
     * and no sale was seen, so it is recorded as cancelled at zero. What matters
     * is that the ledger stops offering a stack the world does not have.
     */
    private void closePhantom(Position stuck, String why) {
        Position closed = stuck.closed(PositionStatus.CANCELLED, System.currentTimeMillis(), 0, Double.NaN);
        committedSpend = Math.max(0, committedSpend - stuck.purchasePrice());
        openListings.removeIf(p -> p.positionId() == stuck.positionId());
        recoveredQueue.removeIf(p -> p.positionId() == stuck.positionId());
        persistence.submit(checkpoint(sessionOpen, state.name(), closed, uncertainExposure,
                "Closed a position with no stack behind it: " + why));
        LOGGER.warn("DoughBay automation: closing #{} ({} x{}) for good - {}",
                stuck.positionId(), stuck.itemKey(), stuck.quantity(), why);
    }

    /** When a refusal to book an item was last logged, so it is said once rather than every sweep. */
    private final java.util.Map<String, Long> bookRefusedAt = new java.util.HashMap<>();

    /** Failures to reach the reserved slot, counted per item so a recreated position cannot reset it. */
    private final java.util.Map<String, Integer> unpreparableItems = new java.util.HashMap<>();
    /** Items given up on this session; the stack stays in the inventory for a person. */
    private final java.util.Set<String> setAsideItems = new java.util.HashSet<>();
    /**
     * When an item was set aside because its stack would not prepare for listing,
     * and how many times. A prepare failure is often transient - a slow render
     * tick, a flaky reserved-slot swap - so stranding the stock for good means a
     * full pack of unlisted goods and empty auction slots. After a cooldown the
     * item is released for another listing attempt; only after several rounds is
     * it left set aside for good, so a genuinely unpreparable item still cannot
     * loop the way the trident did.
     */
    private final java.util.Map<String, Long> preparationSetAsideAt = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> preparationSetAsideRounds = new java.util.HashMap<>();
    private static final long SETASIDE_RETRY_MILLIS = 5 * 60_000L;
    private static final int SETASIDE_MAX_ROUNDS = 5;

    /**
     * Releases prepare-failure set-asides back to the stray-stack lister once
     * their cooldown is up, so transient failures do not strand good stock. Only
     * touches items set aside for a prepare failure (tracked in
     * {@link #preparationSetAsideAt}); shelf and deny-list set-asides are left
     * alone. Bounded to {@link #SETASIDE_MAX_ROUNDS} rounds per item.
     */
    private void retryStrandedStacks(long now) {
        if (preparationSetAsideAt.isEmpty()) return;
        for (var it = preparationSetAsideAt.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            String key = e.getKey();
            if (now - e.getValue() < SETASIDE_RETRY_MILLIS) continue;
            it.remove();
            if (preparationSetAsideRounds.getOrDefault(key, 0) >= SETASIDE_MAX_ROUNDS) continue;
            setAsideItems.remove(key);
            setAsideItems.remove(baseItemId(key));
            unpreparableItems.remove(key);
            LOGGER.info("DoughBay automation: releasing set-aside {} for another listing attempt after cooldown (round {} of {})",
                    key, preparationSetAsideRounds.getOrDefault(key, 0), SETASIDE_MAX_ROUNDS);
        }
    }
    /**
     * Stock taken off the auction house rather than sold under what it cost.
     *
     * <p>The ladder has a last rung: past a certain age a listing may be
     * priced at a share of cost, deliberately, because a slot held for ever is
     * worth less than the loss. That is the right call when the only two
     * choices are sell cheap or hold a slot - and it stops being the right
     * call the moment there is somewhere else to put the stack. A shelf costs
     * no slot, no fee and no decay, so waiting is free, and free beats a
     * booked loss even if the price never comes back.
     *
     * <p>These positions keep their cost and their identity. They are not
     * sold, not written off, and not listed; they are simply somewhere else
     * until the market clears what they cost.
     */
    private final List<Position> shelved = new ArrayList<>();
    /** The last time a shelf trip actually started, so trips can never come in a rapid stream. */
    private long lastShelfRunAt;
    /** What shelved held at the last trip, to tell a trip that made progress from one that did not. */
    private int shelvedAtLastRun = -1;
    /** How many trips in a row changed nothing; a stuck shelf backs off hard rather than knocking forever. */
    private int shelfNoProgress;
    /** Until when the shelf is held off after a run of fruitless trips. */
    private long shelfHeldOffUntil;

    /** How many times a position has failed to reach the reserved slot, across pauses. */
    private final java.util.Map<Long, Integer> unpreparable = new java.util.HashMap<>();
    /** Positions given up on: the stack stays in the inventory for a person to deal with. */
    private final java.util.Set<Long> setAside = new java.util.HashSet<>();

    /** When the book was last recounted against the server, so a refusal does not start an audit every second. */
    private long lastDriftAuditAt;

    /**
     * The server refusing a listing is the truth about how many are up.
     *
     * <p>The count drifts one way all session: a listing that succeeds on the
     * server but whose confirmation the client never sees is never recorded,
     * so the ledger sits below reality and the desk keeps offering stock into
     * a book that is already full. Seventy of ninety showed on the panel while
     * the server had all ninety. The audit already reads the real list; it
     * simply only ran at startup. Now the disagreement triggers it.
     */
    public void observeListingRefused(String line) {
        if (line == null || !line.toLowerCase(java.util.Locale.ROOT).contains("too many listed items")) return;
        long now = System.currentTimeMillis();
        if (now - lastDriftAuditAt < 60_000L) return;
        lastDriftAuditAt = now;
        slotAuditPending = true;
        LOGGER.info("DoughBay: the server says the book is full while we count {}; recounting against the server",
                openListings.size());
    }

    /** Until when the server is restarting, so nothing is attempted against a world that is not there. */
    private volatile long limboUntil;

    /** Whether the server has said it is restarting and has not yet said otherwise. */
    public boolean serverRestarting() {
        return System.currentTimeMillis() < limboUntil;
    }

    /**
     * The world answered, so whatever the server said about restarting is over.
     *
     * <p>The hold is a fixed ten minutes because the server announces its
     * restart and never announces the end of one. Waiting the full ten when it
     * came back in three is its own kind of waste, so any container the server
     * actually opens is taken as the end of it: nothing can open a page on a
     * world that is not there.
     */
    public void observeServerAnswered() {
        if (limboUntil == 0) return;
        long held = limboUntil;
        limboUntil = 0;
        if (System.currentTimeMillis() < held) {
            LOGGER.info("DoughBay: the server is answering again; the restart hold is lifted");
        }
    }

    /**
     * Watches for the server announcing its own restart.
     *
     * <p>Donut puts everyone in "proxy limbo" while it updates, and says
     * plainly not to teleport or the location is lost. The session used to
     * carry on opening an auction that was not there, and an escape firing in
     * that window would have sent a /home into exactly the warning the server
     * had just given. Nothing is attempted until the world is back.
     */
    public void observeServerRestart(String line) {
        if (line == null) return;
        String l = line.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("proxy limbo") || l.contains("server is restarting")
                || (l.contains("servers are updating") && l.contains("do not teleport"))) {
            boolean fresh = !serverRestarting();
            limboUntil = System.currentTimeMillis() + 10 * 60_000L;
            if (fresh) {
                LOGGER.info("DoughBay: the server says it is restarting; holding everything until it is back");
                pauseInternal("The server is restarting; waiting for it to come back");
            }
        }
    }

    /** When the box market was last swept, so the sweep comes round on its own clock. */
    private long lastBoxSweepAt;
    /** The sweep's remaining markets; every one is read before the clock starts again. */
    private final java.util.ArrayDeque<String> boxSweepQueue = new java.util.ArrayDeque<>();

    /** When the desk last explained itself, so the reason line is not printed every pass. */
    private long deskWhyAt;

    /** Items the server would not take a plain order for, so the desk stops offering them. */
    private final java.util.Set<String> deskBadItems = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Orders the desk has tried and failed to cancel, and how often.
     *
     * <p>PLACE gives up on an item after three refusals and COLLECT counts its
     * attempts; CANCEL had neither, so an order the Edit Order page will not
     * cancel came back on every order-house read and was tried again for ever.
     * One seven-day-old bid did that nine times in four minutes.
     */
    private final java.util.Map<String, Integer> cancelAttempts = new java.util.HashMap<>();
    private final java.util.Set<String> deskUncancellable = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private int deskPlaceFailures;

    /**
     * A full stack of this item, as the game defines it.
     *
     * <p>Not everything stacks to 64. An egg or an ender pearl stops at 16, and
     * an elytra or a mace never stacks at all, so "order 64" is wrong as often
     * as it is right. The registry is asked rather than a table of our own
     * kept here, which would be wrong the first time Mojang changed one.
     */
    private static int fullStack(String itemId) {
        try {
            String id = itemId;
            int hash = id.indexOf('#');
            if (hash >= 0) id = id.substring(0, hash);
            net.minecraft.resources.Identifier ident = net.minecraft.resources.Identifier.tryParse(id);
            if (ident == null) return 1;
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(ident);
            if (item == null) return 1;
            return Math.max(1, item.getDefaultMaxStackSize());
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static void bump(java.util.Map<String, Integer> counts, String reason) {
        counts.merge(reason, 1, Integer::sum);
    }

    /**
     * Whether this market is already ours: sold in it in the last day, or
     * holding some now. Only consulted while "new markets only" is on.
     */
    private boolean weTradeThisAlready(String itemId) {
        for (Position p : openListings) if (p.itemKey().startsWith(itemId)) return true;
        for (String key : recentlySoldMarkets) if (key.startsWith(itemId)) return true;
        return false;
    }

    /** Markets this client has sold in lately, so the underdog can tell new ground from old. */
    private final java.util.Set<String> recentlySoldMarkets = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Seeds the recent markets from the ledger, so a restart does not make every market look untouched. */
    public synchronized void seedRecentMarkets(java.util.Collection<String> keys) {
        recentlySoldMarkets.addAll(keys);
        LOGGER.info("DoughBay underdog: {} market(s) we already trade are excluded as old ground", recentlySoldMarkets.size());
    }

    /** The most per item a bid may pay so a fill sold at the quick-sale price still clears the minimums; 0 when unknown. */
    private long bidCeilingUnit(MarketWatcher.Snapshot snapshot, String itemId, int count, double minimumProfit, double minimumRoi) {
        if (snapshot == null) return 0;
        StackBucket bucket = StackBucket.of(count);
        for (MarketStats m : snapshot.markets()) {
            if (!m.itemKey().equals(itemId) || m.bucket() != bucket || !m.hasPrices() || !(m.quickSalePrice() > 0)) continue;
            double net = auctionFees.netSale(m.quickSalePrice());
            double perStack = Math.min(net - minimumProfit, net / (1.0 + minimumRoi)) * 0.97;
            return (long) Math.floor(perStack / Math.max(1, count));
        }
        return 0;
    }

    /** Collected fills are a purchase at the bid price; they go through the ordinary listing path. */
    /**
     * Collect opened the order and nothing came out. Either the stack was
     * taken by hand and is already in the inventory, in which case it is
     * booked and listed like any collected fill, or the order is empty and
     * counts as collected so the desk stops opening it; a filled, collected
     * order is then cancelled off Your Orders to free the slot.
     */
    private void collectedNothing(Minecraft client, long now, DeskStep step) {
        String k = orderKey(step.itemId(), step.unitPrice(), step.count());
        int attempts = collectAttempts.merge(k, 1, Integer::sum);
        int rows = 0;
        for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
            if (r.itemId().equals(step.itemId()) && r.unitPrice() == step.unitPrice() && r.requested() == step.count()) rows++;
        }
        if (step.ordinal() + 1 < rows) {
            // The same order twice on the page: the first was collected long ago; the next row is the live one.
            deskQueue.addFirst(new DeskStep(DeskAction.COLLECT, step.itemId(), step.count(), step.unitPrice(),
                    step.why() + " (row " + (step.ordinal() + 2) + ")", step.ordinal() + 1));
            return;
        }
        int wanted = deliveredFor(k) - collectedByKey.getOrDefault(k, 0);
        String display = AutomatedExecutionDriver.displayNameFor(step.itemId());
        boolean noticed = fillNoticed.stream().anyMatch(n -> noticeNames(n, display));
        int have = AutomatedExecutionDriver.inventoryCount(client, step.itemId());
        if ((noticed || unlistedPurchaseSlot(client, step.itemId(), wanted) >= 0) && wanted > 0 && have >= wanted && trackedPosition == null) {
            LOGGER.info("DoughBay bid desk: {} x{} from the order is already in the inventory (collected by hand); booking it",
                    step.itemId(), wanted);
            collectedByKey.merge(k, wanted, Integer::sum);
            saveCollected();
            consumeFills(step.itemId(), wanted);
            bookCollected(client, now, step.itemId(), wanted, step.unitPrice());
            return;
        }
        LOGGER.info("DoughBay bid desk: nothing came out of the order for {} at {} (attempt {} of 4); {} still to collect",
                step.itemId(), step.unitPrice(), attempts, Math.max(0, wanted));
        if (attempts >= 4) {
            detail = "Bid desk: could not collect " + step.itemId() + " from your order; check Your Orders by hand";
        }
    }

    private int deliveredFor(String key) {
        int sum = 0;
        for (AutomatedExecutionDriver.OwnOrderRow r : ownOrders) {
            if (orderKey(r.itemId(), r.unitPrice(), r.requested()).equals(key)) sum += r.delivered();
        }
        return sum;
    }

    private boolean bookCollected(Minecraft client, long now, String itemId, int count, long unitPrice) {
        return bookCollected(client, now, itemId, count, unitPrice, unitPrice * (long) count);
    }

    /**
     * A position's quantity is part of its stored identity, so a purchase
     * that now sits as a different-sized stack is closed and re-opened as a
     * fresh position of that size, carrying the same cost.
     */
    private Position supersedeQuantity(Position position, int quantity, long now) {
        Position closed = position.closed(PositionStatus.CANCELLED, now, 0, Double.NaN);
        persistence.submit(checkpoint(sessionOpen, state.name(), closed, uncertainExposure,
                "Superseded by a stack of " + quantity));
        Position fresh = withQuantity(position, quantity);
        // The same purchase, re-sized: it has held its slot since it was bought.
        return new Position(nextPositionId++, fresh.mode(), fresh.itemKey(), fresh.bucket(), fresh.quantity(),
                fresh.purchasePrice(), fresh.targetPrice(), fresh.purchasedAt() > 0 ? fresh.purchasedAt() : now,
                0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
    }

    private static Position withQuantity(Position position, int quantity) {
        long target = position.quantity() > 0 ? position.targetPrice() * quantity / position.quantity() : position.targetPrice();
        // The cost moves with the quantity too. It did not: a half stack
        // reopened after a split carried the whole stack's purchase price, so
        // a 32-wheat sold for 22k against a 64-wheat's 35k cost booked a loss
        // that never happened, and the book showed asks at a tenth of "cost"
        // that were nothing of the kind. Per-unit cost is the honest figure
        // for a like stack in either direction.
        long cost = position.quantity() > 0 ? position.purchasePrice() * quantity / position.quantity() : position.purchasePrice();
        return new Position(position.positionId(), position.mode(), position.itemKey(),
                StackBucket.of(quantity), quantity, cost, target,
                position.purchasedAt(), position.listedAt(), position.closedAt(),
                position.salePrice(), position.realizedProfit(), position.status());
    }

    private boolean singlesListingEnabled() {
        return dev.doughbay.fabric.Tuning.get("list.singles") >= 0.5;
    }

    /**
     * The single-market shape for a position's item when the flag is on and the
     * sales history proves the market pays more per unit one at a time, else
     * null. Plain commodities only: a boxed or hashed key is never pieced out.
     */
    private dev.doughbay.fabric.StackProfile.Shape singleMarketShape(Position position) {
        if (position == null || !singlesListingEnabled()) return null;
        if (position.itemKey().indexOf('#') >= 0) return null;
        dev.doughbay.fabric.StackProfile profile = dev.doughbay.fabric.DoughBayClient.stackProfile();
        if (profile == null) return null;
        dev.doughbay.fabric.StackProfile.Shape shape = profile.shapeOf(baseItemId(position.itemKey()));
        return shape != null && shape.prefersSingles() ? shape : null;
    }

    /**
     * What a single of this position's item should list at: its measured single
     * price, never below cost plus the minimum profit. Zero when the market is
     * not one that prefers singles, the flag is off, or the position is not a
     * single.
     */
    private long singleMarketTarget(Position position) {
        dev.doughbay.fabric.StackProfile.Shape shape = singleMarketShape(position);
        if (shape == null || position.quantity() != 1) return 0;
        long floor = position.purchasePrice()
                + Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
        return Math.max(floor, shape.singlePrice());
    }

    private int firstEmptyNonEquipmentSlot(Minecraft client) {
        List<ItemStack> items = client.player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty()) return i;
        }
        return -1;
    }

    /**
     * Peels a single unit off the bought stack into a free inventory slot and
     * re-sizes the tracked position to that one unit, so the rest of {@link
     * #prepareAndList} prices and lists a single. Returns true while it is still
     * working (the caller returns and re-enters next tick); false when there is
     * nothing to peel - the flag is off, the market does not prefer singles, the
     * stack is already a single, or the inventory is full - or when the peel is
     * done and the position is now a single.
     *
     * <p>The move is the three clicks a hand makes to take one off a stack, one
     * a tick: pick the stack up, right-click one down into an empty slot, put
     * the rest back. The leftover stays in the inventory and is adopted and
     * listed again on the next pass - one auction slot per single, which is why
     * it leans on spare slot capacity. A click that will not land, or a peel
     * that does not verify, gives up and lists the stack whole rather than
     * guessing.
     */
    /** How many singles (x1) of this item are already listed on our own book. */
    private int openSinglesOf(String itemKey) {
        String base = baseItemId(itemKey);
        int n = 0;
        for (Position p : openListings) {
            if (p.quantity() == 1 && baseItemId(p.itemKey()).equals(base)) n++;
        }
        return n;
    }

    private boolean advanceSinglePeel(Minecraft client, long now) {
        if (client == null || client.player == null) return false;
        if (trackedPosition == null) return false;
        boolean cursorHeld = !client.player.inventoryMenu.getCarried().isEmpty();

        // Not mid-peel: decide whether to start one. Only ever start with an
        // empty cursor and a safe context - once a peel is under way (stage >=
        // 0) the cursor is *meant* to hold the stack, so the safe-context gate
        // below must not fire mid-peel, which is what stranded a stack on the
        // cursor and hung the desk the first time this ran.
        if (singlePeelStage < 0) {
            if (cursorHeld) return false;   // not our cursor; the normal path's own gate handles it
            if (singleMarketShape(trackedPosition) == null) return false;
            if (singlePeelFailedPositionId == trackedPosition.positionId()) return false;   // gave up on this one
            if (trackedPosition.quantity() <= 1) return false;   // already a single; the price path handles it
            // Cap the singles of one item that may be up at once. Without it a
            // single 64-stack pieced itself out into dozens of listings that
            // undercut each other down a long ladder and filled the book; past
            // the cap the rest of the stack is listed whole instead.
            int singlesCap = (int) Math.round(dev.doughbay.fabric.Tuning.get("list.singles_max"));
            if (openSinglesOf(trackedPosition.itemKey()) >= singlesCap) return false;
            if (!safeInventoryContext(client)) {
                Screen open = client.gui.screen();
                if (open instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                    client.player.closeContainer();
                } else if (open instanceof DoughBayScreen) {
                    client.gui.setScreen(null);
                } else if (open == null && client.player.containerMenu != client.player.inventoryMenu) {
                    client.player.closeContainer();
                }
                detail = "Closing pages before peeling a single";
                return true;
            }
            Inventory inventory = client.player.getInventory();
            int src = largestStackSlot(client, trackedPosition.itemKey());
            if (src < 0) return false;   // nothing to peel; let the normal path handle it
            int count = inventory.getNonEquipmentItems().get(src).getCount();
            if (count <= 1) return false;
            int free = firstEmptyNonEquipmentSlot(client);
            if (free < 0) {
                LOGGER.info("DoughBay singles: inventory full; listing {} as a stack instead",
                        baseItemId(trackedPosition.itemKey()));
                return false;   // graceful fallback to the stack listing
            }
            int srcMenu = inventoryMenuSlot(client, src);
            int freeMenu = inventoryMenuSlot(client, free);
            if (srcMenu < 0 || freeMenu < 0) return false;
            singlePeelSourceMenuSlot = srcMenu;
            singlePeelFreeMenuSlot = freeMenu;
            singlePeelFreeInvSlot = free;
            singlePeelRemainder = count - 1;
            singlePeelPositionId = trackedPosition.positionId();
            singlePeelStage = 0;
            singlePeelClickTick = -1;
            singlePeelAbortClicks = 0;
        }

        // One click every couple of ticks so the server settles each one.
        if (singlePeelClickTick >= 0 && controllerTick - singlePeelClickTick < 2) return true;
        int menuId = client.player.inventoryMenu.containerId;

        // Aborting: something went wrong. Never leave the stack on the cursor -
        // put whatever is carried back onto the source slot (same item, it
        // restacks), then give up and let the normal path list the stack whole.
        if (singlePeelStage == PEEL_ABORT) {
            if (!cursorHeld) {
                singlePeelStage = -1;
                singlePeelAbortClicks = 0;
                singlePeelFailedPositionId = trackedPosition.positionId();
                LOGGER.warn("DoughBay singles: peel aborted, cursor returned; listing {} as a stack",
                        baseItemId(trackedPosition.itemKey()));
                return false;
            }
            if (singlePeelAbortClicks >= 5) {
                // The source-slot restack will not land - the slot may have
                // filled or shifted. Give up the peel and let stowStrayCursor
                // set the cursor into a fresh empty slot instead of clicking
                // the same full slot forever. The item is never left dangling.
                LOGGER.warn("DoughBay singles: peel abort could not restack after {} tries; "
                        + "handing the cursor to the stow backstop", singlePeelAbortClicks);
                singlePeelStage = -1;
                singlePeelAbortClicks = 0;
                singlePeelFailedPositionId = trackedPosition.positionId();
                return false;
            }
            clickPeelSlot(client, menuId, singlePeelSourceMenuSlot, 0);   // dump the cursor back onto the source
            singlePeelAbortClicks++;
            detail = "Peel aborted; returning the stack to its slot";
            return true;
        }

        // Clicks 0/1/2: pick the stack up, right-click one into the free slot,
        // put the rest back. Before each, the cursor must be in the expected
        // state (empty before the first click, holding after it); if it is not,
        // abort - which returns the cursor rather than stranding it.
        if (singlePeelStage <= 2) {
            boolean shouldHold = singlePeelStage != 0;
            if (cursorHeld != shouldHold) {
                LOGGER.warn("DoughBay singles: unexpected cursor at peel stage {} (held {}); aborting",
                        singlePeelStage, cursorHeld);
                singlePeelStage = PEEL_ABORT;
                return true;
            }
            int slot = singlePeelStage == 1 ? singlePeelFreeMenuSlot : singlePeelSourceMenuSlot;
            int button = singlePeelStage == 1 ? 1 : 0;   // right-click drops one; left picks up / puts the rest back
            if (!clickPeelSlot(client, menuId, slot, button)) {
                singlePeelStage = PEEL_ABORT;
                return true;
            }
            singlePeelStage++;
            detail = "Peeling a single (" + singlePeelStage + " of 3)";
            return true;
        }

        // Verify: one unit in the free slot, cursor clear. Anything else aborts.
        List<ItemStack> held = client.player.getInventory().getNonEquipmentItems();
        ItemStack one = singlePeelFreeInvSlot >= 0 && singlePeelFreeInvSlot < held.size()
                ? held.get(singlePeelFreeInvSlot) : ItemStack.EMPTY;
        boolean oneExact = exactPlainStack(one, trackedPosition.itemKey(), 1);
        if (cursorHeld || !oneExact) {
            LOGGER.warn("DoughBay singles: peel did not verify (cursor held {}, one-slot exact {}); aborting",
                    cursorHeld, oneExact);
            singlePeelStage = PEEL_ABORT;
            return true;
        }
        purchasedInventorySlot = singlePeelFreeInvSlot;
        peelLeftItem = baseItemId(trackedPosition.itemKey());
        peelLeftCount = singlePeelRemainder;
        peelLeftUnitCost = trackedPosition.quantity() > 0
                ? trackedPosition.purchasePrice() / trackedPosition.quantity() : 0;
        peelLeftAt = now;
        trackedPosition = supersedeQuantity(trackedPosition, 1, now);
        singlePeelPositionId = trackedPosition.positionId();
        singlePeelStage = -1;
        // Value and probe the single fresh, not off the stack it came from.
        preListMarketVerified = false;
        preListVerifiedScanStartedAt = 0;
        probePending = UNDERCUT_ENABLED;
        probeStarted = false;
        LOGGER.info("DoughBay singles: peeled one {} off a stack; {} left to piece out",
                baseItemId(trackedPosition.itemKey()), singlePeelRemainder);
        return false;   // continue prepareAndList on the single
    }

    /** One paced container click for the peel; records the tick, false if it threw. */
    private boolean clickPeelSlot(Minecraft client, int menuId, int menuSlot, int button) {
        try {
            client.gameMode.handleContainerInput(menuId, menuSlot, button,
                    ContainerInput.PICKUP, client.player);
            singlePeelClickTick = controllerTick;
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("DoughBay singles: peel click failed ({})", e.toString());
            return false;
        }
    }

    /**
     * A stack must never linger on the cursor. While it is on the cursor it is
     * in no inventory slot, so if the client disconnects or a container closes
     * before it is set down, the server ejects it onto the ground rather than
     * saving it - real stock lost. The peel picks a stack up for a few ticks by
     * design, but a peel that could not set it back down (or any interrupted
     * move) leaves it stranded, and the desk then loops on "an item is held on
     * the cursor" for as long as it takes something to knock the client off.
     *
     * <p>This is the backstop: whenever the cursor holds an item and no peel is
     * mid-flight, put it into the first empty inventory slot and confirm it
     * landed. Returns true while it is still working - the caller must wait and
     * not proceed - and false once the cursor is clear. It only acts with no
     * screen or foreign container in the way (so it never deposits into a chest
     * or fights a buy dialog) and while the driver is idle; the screen-closing
     * steps that run first clear those.</p>
     */
    private boolean stowStrayCursor(Minecraft client) {
        if (client == null || client.player == null || client.gameMode == null) return false;
        if (singlePeelStage >= 0) return false;   // a peel owns the cursor on purpose
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return false;
        ItemStack carried = client.player.inventoryMenu.getCarried();
        if (carried.isEmpty()) return false;      // nothing stranded
        if (client.gui.screen() != null
                || client.player.containerMenu != client.player.inventoryMenu) {
            // A screen is over the cursor item. With the driver idle nothing
            // else reliably closes it - the mod panel is deliberately left open
            // now (modScreenOpen is false), and a stray screen here need not be
            // one closeStrayAuctionPage recognises - so it would wait on the
            // cursor for ever (the ~1h freeze this guard was meant to prevent).
            // Close it ourselves, the same thing a press of Escape does: a
            // foreign container closes and returns the carried item; any other
            // screen just clears, and the empty-cursor path below sets the item
            // down next tick. Paced so a screen the game is about to close on
            // its own gets a moment first.
            if (cursorStowClickTick >= 0 && controllerTick - cursorStowClickTick < 2) return true;
            cursorStowClickTick = controllerTick;
            if (client.player.containerMenu != client.player.inventoryMenu) {
                client.player.closeContainer();
            } else {
                client.gui.setScreen(null);
            }
            detail = "Closing a screen so a stray cursor item can be set down";
            return true;
        }
        if (cursorStowClickTick >= 0 && controllerTick - cursorStowClickTick < 2) return true;
        int free = firstEmptyNonEquipmentSlot(client);
        if (free < 0) {
            // Nowhere to set it down. Do not drop it and do not loop silently -
            // surface it so a slot can be freed; the item stays on the cursor,
            // which is why the pack should never be run completely full.
            detail = "An item is stuck on the cursor and the inventory is full; free a slot";
            return true;
        }
        int freeMenu = inventoryMenuSlot(client, free);
        if (freeMenu < 0) { detail = "Putting a stray cursor item down"; return true; }
        try {
            client.gameMode.handleContainerInput(client.player.inventoryMenu.containerId,
                    freeMenu, 0, ContainerInput.PICKUP, client.player);
            cursorStowClickTick = controllerTick;
            LOGGER.warn("DoughBay: a stray item was on the cursor; setting it into inventory slot {}", free);
        } catch (RuntimeException e) {
            LOGGER.warn("DoughBay: could not set a stray cursor item down ({})", e.toString());
        }
        detail = "Setting a stray cursor item back into the inventory";
        return true;
    }

    /** The largest listable stack of an item in the inventory, or -1. */
    private int largestStackSlot(Minecraft client, String itemId) {
        if (client == null || client.player == null) return -1;
        List<ItemStack> stacks = client.player.getInventory().getNonEquipmentItems();
        int best = -1;
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack st = stacks.get(i);
            if (st.isEmpty() || !itemId(st).equals(baseItemId(itemId))) continue;
            List<String> parts = new ArrayList<>();
            for (var e : st.getComponentsPatch().entrySet()) parts.add(String.valueOf(e.getKey()));
            boolean listable = AutomatedExecutionDriver.listableWithParts(st);
            seen.add("slot" + i + " x" + st.getCount() + parts + (listable ? "" : " NOT LISTABLE"));
            if (!listable) continue;
            if (best < 0 || st.getCount() > stacks.get(best).getCount()) best = i;
        }
        if (!seen.isEmpty()) LOGGER.info("DoughBay automation: stacks of {} in the inventory: {}; taking {}", itemId, seen, best < 0 ? "none" : "slot" + best);
        return best;
    }

    private boolean bookCollected(Minecraft client, long now, String itemId, int count, long unitPrice, long cost) {
        // Every route that adopts a stack from the inventory ends here - the
        // bid desk's own reconciliation, the owed-order sweep, the stray-stack
        // rescue - so this is the one place worth guarding. Without it, giving
        // up on a stack meant nothing: the next sweep found the same trident
        // unaccounted for, booked it again, and the preparation failed again.
        if (setAsideItems.contains(itemId)
                || (!dev.doughbay.fabric.Tuning.itemAllowed(itemId)
                        && !dev.doughbay.fabric.Tuning.liquidating(itemId))) {
            if (bookRefusedAt.getOrDefault(itemId, 0L) + 300_000L < now) {
                bookRefusedAt.put(itemId, now);
                LOGGER.info("DoughBay automation: not booking {} x{} - it has been set aside or denied; "
                        + "it stays in the inventory untouched", itemId, count);
            }
            return false;
        }
        // The rest of a peeled stack is not a fill: it was paid for once, at
        // its own cost, and is adopted back at that cost without an alert.
        boolean peelLeftover = peelLeftItem != null && peelLeftUnitCost > 0
                && peelLeftItem.equals(baseItemId(itemId)) && count == peelLeftCount
                && now - peelLeftAt < 30 * 60_000L;
        if (peelLeftover) {
            peelLeftItem = null;
            unitPrice = peelLeftUnitCost;
            cost = peelLeftUnitCost * (long) count;
            LOGGER.info("DoughBay singles: re-adopting the rest of a peeled {} x{} at its own cost of {} each",
                    itemId, count, unitPrice);
        } else {
            // Every route lands here, so this is where the fill is announced. A
            // bid sitting on the order book for an hour and then filling is the
            // one thing that happens with no click of ours behind it, which made
            // it the easiest thing in the whole system to miss.
            dev.doughbay.fabric.DoughBayClient.alertOrderFilled(
                    baseItemId(itemId).replace("minecraft:", "").replace('_', ' '), count, unitPrice);
        }
        // Collected items merge into a stack already held: the stack that
        // results is what gets listed, at the cost of what was collected.
        if (unlistedPurchaseSlot(client, itemId, count) < 0) {
            int slot = largestStackSlot(client, itemId);
            if (slot >= 0) {
                int merged = client.player.getInventory().getNonEquipmentItems().get(slot).getCount();
                if (merged != count) {
                    long share = merged < count ? cost * merged / count : cost;
                    LOGGER.info("DoughBay bid desk: {} x{} collected sits as a stack of {}; listing that stack at a cost of {} (of {})",
                            itemId, count, merged, share, cost);
                    if (merged < count) {
                        // The rest is still in the inventory as other stacks; they
                        // are booked by the bid record when their turn comes.
                        cost = share;
                    }
                    count = merged;
                }
            }
        }
        long target = 0;
        MarketWatcher.Snapshot snapshot = lastSnapshot;
        if (snapshot != null) {
            StackBucket bucket = StackBucket.of(count);
            double perUnit = 0;
            int perUnitFrom = 0;
            for (MarketStats m : snapshot.markets()) {
                if (!m.itemKey().equals(itemId) || !m.hasPrices() || !(m.quickSalePrice() > 0)) continue;
                if (m.bucket() == bucket && m.bucket().exactCount() == count) {
                    target = (long) Math.floor(m.quickSalePrice());
                }
                // An odd count (61 from a partly filled order) takes the
                // per-item price of the largest stack size the market knows.
                int size = Math.max(1, m.bucket().exactCount());
                if (size >= perUnitFrom) {
                    perUnitFrom = size;
                    perUnit = m.quickSalePrice() / size;
                }
            }
            if (target <= 0 && perUnit > 0) target = (long) Math.floor(perUnit * count);
        }
        double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
        long floor = (long) Math.ceil(cost + minimumProfit);
        for (int i = 0; i < 200 && auctionFees.netSale(floor) < cost + minimumProfit; i++) floor = (long) Math.ceil(floor * 1.01);
        target = Math.max(target, floor);
        if (!peelLeftover) {
            tradesStarted++;
            committedSpend = Math.addExact(committedSpend, cost);
        }
        trackedPosition = new Position(nextPositionId++, "REAL", itemId, StackBucket.of(count), count,
                cost, target, now, 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
        LOGGER.info("DoughBay bid desk: collected {} x{} at {} each ({} total); listing it", itemId, count, unitPrice, cost);
        listPurchasedStackInHand(client, now, peelLeftover ? "Rest of a peeled stack" : "Collected from your bid");
        return true;
    }

    /** The order house is read on a timer, between trades, never in the middle of one. */
    private long nextOrdersReadAt = System.currentTimeMillis() + 45_000;
    /** The first read after a launch is the full sweep; then every "full sweep" interval. */
    private long nextFullSweepAt = 0;
    private boolean ordersReadWasFull;
    private boolean ordersScheduleSeeded;
    /** When the current order-house read began; for the time estimate. */
    private long ordersReadStartedAt;
    /** When the order-house read last advanced a page; 0 when not reading. */
    private long ordersReadProgressAt;
    /** Pages the read had turned at {@link #ordersReadProgressAt}. */
    private int ordersReadPagesSeen;
    /**
     * A read whose page count has not moved for this long is stalled on a
     * dropped navigation click - the driver's own retries can push its internal
     * timers out to half a minute, so this guarantees recovery without a hand on
     * Escape. Measured from the last page turn, not the start, so a full sweep
     * of many pages (minutes of legitimate work) is never cut off while it is
     * still making progress.
     */
    private static final long ORDERS_READ_STALL_MILLIS = 20_000L;

    /** A relaunch keeps the last session's schedule: no fresh sweep while the book is still fresh. */
    private void seedOrdersSchedule(long now) {
        if (ordersScheduleSeeded) return;
        ordersScheduleSeeded = true;
        dev.doughbay.fabric.OrderBook book = dev.doughbay.fabric.DoughBayClient.orderBook();
        if (book == null) return;
        if (book.lastFullAt() > 0) {
            nextFullSweepAt = book.lastFullAt() + dev.doughbay.fabric.Tuning.millis("orders.full_sweep_min");
        }
        if (book.readAt() > 0) {
            nextOrdersReadAt = Math.max(nextOrdersReadAt, book.readAt() + dev.doughbay.fabric.Tuning.millis("orders.scan_min"));
            LOGGER.info("DoughBay automation: order book read {} min ago; next read in {} min, next full sweep in {} min",
                    (now - book.readAt()) / 60_000, Math.max(0, nextOrdersReadAt - now) / 60_000, Math.max(0, nextFullSweepAt - now) / 60_000);
        }
    }

    private boolean startOrdersRead(Minecraft client, long now) {
        if (dev.doughbay.fabric.Tuning.get("orders.enabled") < 0.5) return false;
        seedOrdersSchedule(now);
        if (now < nextOrdersReadAt || trackedPosition != null || repricing != null) return false;
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return false;
        if (closeStrayAuctionPage(client)) return true;
        if (modScreenOpen(client)) {
            client.gui.setScreen(null);
            detail = "Closing the GoNuts screen to read the order house";
            return true;
        }
        if (!safeInventoryContext(client)) return false;
        awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
        boolean full = now >= nextFullSweepAt;
        int pages = full ? 0 : (int) dev.doughbay.fabric.Tuning.get("orders.pages");
        ordersReadWasFull = full;
        ExecutionResult result = driver.scanOrders(pages);
        if (!result.ok()) {
            nextOrdersReadAt = now + 60_000;
            detail = "Order house read could not start: " + result.detail();
            return false;
        }
        ordersReadStartedAt = now;
        ordersReadProgressAt = now;
        ordersReadPagesSeen = 0;
        transition(State.READING_ORDERS, "Reading the order house");
        return true;
    }

    private void observeOrdersTerminal(Minecraft client, long now) {
        AutomatedExecutionDriver.TerminalEvent event = nextTerminal(
                AutomatedExecutionDriver.OperationIntent.ORDERS);
        if (event == null) {
            // Self-heal instead of waiting for a hand on Escape - but only when
            // the read has genuinely stalled, not merely gone slow. A rising
            // page count is progress (a big sweep is minutes of real work); the
            // clock resets on every page turn and only fires once the count has
            // sat still past the stall window. Then abandon: the driver goes
            // idle, the next read closes the stray page and starts clean.
            int pages = driver.ordersPagesRead();
            if (pages > ordersReadPagesSeen) {
                ordersReadPagesSeen = pages;
                ordersReadProgressAt = now;
            }
            // Show the live page count and a time estimate so a long full sweep
            // reads as progress, not a stuck "Reading the order house". The
            // number climbing is the proof it is working; a number that stops is
            // the stall below. The estimate is the measured pace so far applied
            // to the pages left - honest, and it settles after a page or two.
            int total = driver.ordersPagesWanted();
            if (pages > 0) {
                String time = "";
                long elapsed = now - ordersReadStartedAt;
                if (pages >= 2 && total > pages && elapsed > 0) {
                    long secs = ((elapsed / pages) * (total - pages) + 999) / 1000;
                    long rem = secs % 60;
                    time = "  Time: " + (secs / 60) + ":" + (rem < 10 ? "0" : "") + rem;
                }
                detail = "Reading orders  Page: " + pages + "/" + total + time;
            }
            if (ordersReadProgressAt > 0 && now - ordersReadProgressAt > ORDERS_READ_STALL_MILLIS) {
                ordersReadProgressAt = 0;
                LOGGER.info("DoughBay automation: order-house read made no page progress for {}s"
                                + " (stuck at {} page(s)); abandoning to recover",
                        ORDERS_READ_STALL_MILLIS / 1000, pages);
                driver.abandon("Order-house read stalled (navigation click dropped)");
            }
            return;
        }
        ordersReadProgressAt = 0;
        long interval = dev.doughbay.fabric.Tuning.millis("orders.scan_min");
        dev.doughbay.fabric.OrderBook book = dev.doughbay.fabric.DoughBayClient.orderBook();
        if (event.intent() != AutomatedExecutionDriver.OperationIntent.ORDERS
                || event.outcome() != AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED) {
            nextOrdersReadAt = now + Math.min(interval, 3 * 60_000L);
            if (book != null) book.noteFailure(event.detail());
            LOGGER.info("DoughBay automation: order house read did not complete: {}", event.detail());
            transition(State.SCANNING, "Order house read skipped (" + event.detail() + "); scanning");
            return;
        }
        List<dev.doughbay.fabric.OrderBook.Order> orders = new ArrayList<>();
        for (AutomatedExecutionDriver.OrderRow row : driver.lastOrders()) {
            orders.add(new dev.doughbay.fabric.OrderBook.Order(now, row.itemId(), row.itemKey(), row.descriptorJson(),
                    row.parts(), row.unitPrice(), row.delivered(), row.total(), row.page()));
        }
        int pages = orders.stream().mapToInt(dev.doughbay.fabric.OrderBook.Order::page).max().orElse(0);
        // "Full" is what the sweep set out to be; complete is whether it got
        // there. Only a sweep that reached the end of the book may retire the
        // orders it did not see - a two-page read that gave up is a partial.
        boolean complete = ordersReadWasFull && driver.lastOrdersSweepComplete();
        if (ordersReadWasFull && !complete) {
            LOGGER.info("DoughBay automation: the full sweep stopped at page {}; treated as a partial read", pages);
        }
        if (book != null) book.record(orders, pages, now, complete);
        // The same sweep goes home: the order house is a page in the game and
        // nowhere else, so this is the only way the API ever learns of it.
        dev.doughbay.fabric.FeedUploader uploader = dev.doughbay.fabric.DoughBayClient.feedUploader();
        if (uploader != null) uploader.ordersSwept(driver.lastOrders(), pages, complete, now);
        // Not bidding and the order house came back empty: reading it every few
        // minutes just walks an empty page for no reason. Back off to a slow
        // check that still notices a manually placed order within the half hour.
        long readInterval = interval;
        if (dev.doughbay.fabric.Tuning.get("orders.bid") < 0.5 && orders.isEmpty()) {
            readInterval = Math.max(interval, 30 * 60_000L);
        }
        nextOrdersReadAt = now + readInterval;
        if (ordersReadWasFull) nextFullSweepAt = now + dev.doughbay.fabric.Tuning.millis("orders.full_sweep_min");
        deskReadDue = dev.doughbay.fabric.Tuning.get("orders.bid") >= 0.5
                || dev.doughbay.fabric.Tuning.get("orders.collect") >= 0.5;
        LOGGER.info("DoughBay automation: order house {}: {} order(s) on {} page(s)",
                ordersReadWasFull ? "swept" : "read", orders.size(), pages);
        transition(State.SCANNING, "Order house read: " + orders.size() + " order(s); scanning");
    }

    private boolean startSlotAudit(Minecraft client) {
        if (closeStrayAuctionPage(client)) return true;
        if (!safeInventoryContext(client) && !modScreenOpen(client)) {
            detail = "Waiting before checking auction slots: " + guiBlockerDescription(client);
            return true;
        }
        if (modScreenOpen(client)) {
            client.gui.setScreen(null);
            detail = "Closing the GoNuts screen to check auction slots";
            return true;
        }
        if (!safeInventoryContext(client)) {
            detail = "Waiting before checking auction slots: " + guiBlockerDescription(client);
            return true;
        }
        awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
        ExecutionResult result = driver.auditOwnListings();
        if (!result.ok()) {
            slotAuditPending = false;
            detail = "Auction slot check could not start: " + result.detail();
            return false;
        }
        transition(State.AUDITING_SLOTS, "Checking your auction slots for sales that happened while offline");
        return true;
    }

    private void observeSlotAuditTerminal(Minecraft client, long now) {
        AutomatedExecutionDriver.TerminalEvent event = nextTerminal(
                AutomatedExecutionDriver.OperationIntent.AUDIT);
        if (event == null) return;
        slotAuditPending = false;
        // The audit's read of the /ah listings page is done the moment its
        // terminal arrives, so close the page now rather than leaving it for
        // the next step to knock shut. On a client with nothing to do after
        // the audit - a rate-limited feed offering no buys - no next step
        // comes, and the desk was seen sitting parked on /ah until a human
        // pressed Esc. Reconciliation below reads the driver's cached rows,
        // not the live screen, and the next buy or listing reopens /ah.
        closeContainerPage(client);
        if (event.intent() != AutomatedExecutionDriver.OperationIntent.AUDIT
                || event.outcome() != AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED) {
            LOGGER.info("DoughBay automation: auction slot check did not complete: {}", event.detail());
            transition(State.SCANNING, "Slot check skipped (" + event.detail() + "); scanning");
            return;
        }
        List<AutomatedExecutionDriver.OwnListingRow> rows =
                new ArrayList<>(driver.lastOwnListings());
        // What the server itself just said, before reconciliation starts
        // taking rows out of this list. This is the only count that is simply
        // true: the panel used to add the ledger's listings to whatever rows
        // were left unmatched, and those two sets overlap, so it could show
        // ninety-four slots used out of a maximum of ninety.
        // Plus the rows the page showed and the tooltip would not price. Those
        // are slots too: leaving them out is why the audit read eighty-nine
        // where the server counted ninety, and why the desk kept offering a
        // ninetieth listing into a book that had no room for it.
        int couldNotPrice = driver.lastOwnListingsUnreadable();
        serverListedSlots = rows.size() + couldNotPrice;
        if (couldNotPrice > 0) {
            LOGGER.info("DoughBay automation: {} listing(s) of yours could not be priced from the page; "
                    + "counted as slots but nothing can reprice or pull them back", couldNotPrice);
        }
        serverListedAt = now;
        ledgerAtAudit = openListings.size();
        soldSinceAudit = 0;
        listedSinceAudit = 0;
        cancelledSinceAudit = 0;
        int settled = 0;
        List<Position> missing = new ArrayList<>();
        for (Position listed : new ArrayList<>(openListings)) {
            String itemId = baseItemId(listed.itemKey());
            int match = -1;
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                if (rowMatches(row, listed) && row.covers(listed.targetPrice())) {
                    match = i;
                    break;
                }
            }
            if (match >= 0) {
                rows.remove(match);
                continue;
            }
            // Same item and stack at another price: relisted by hand. Adopt
            // the price shown so its sale can still be matched.
            //
            // Not just any row of the kind: two blast-furnace stacks bought at
            // 224k and 372k each took the other's row here, because the first
            // position adopted the first row it saw, which was the second
            // position's exact ask. The ladder then dutifully "corrected" both -
            // one lifted to a cost it never had, the other cut to a cost it
            // never had. A row that is exactly another open listing's ask
            // belongs to that listing; among the rest, the nearest price is
            // the one that was relisted by hand.
            int repriced = -1;
            long nearestGap = Long.MAX_VALUE;
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                if (!rowMatches(row, listed)) continue;
                boolean someoneElses = false;
                for (Position other : openListings) {
                    if (other.positionId() != listed.positionId() && rowMatches(row, other)
                            && row.covers(other.targetPrice())) {
                        someoneElses = true;
                        break;
                    }
                }
                if (someoneElses) continue;
                long gap = Math.abs(row.displayedPrice() - listed.targetPrice());
                if (gap < nearestGap) {
                    nearestGap = gap;
                    repriced = i;
                }
            }
            if (repriced >= 0) {
                var row = rows.remove(repriced);
                Position adopted = withTargetPrice(listed, row.displayedPrice());
                openListings.replaceAll(p -> p.positionId() == listed.positionId() ? adopted : p);
                persistence.submit(checkpoint(sessionOpen, state.name(), adopted,
                        uncertainExposure, "Adopted hand-relisted price " + row.displayedPrice()));
                LOGGER.info("DoughBay automation: parked listing #{} ({} x{}) is up at {} (was {}); adopted",
                        listed.positionId(), listed.itemKey(), listed.quantity(),
                        row.displayedPrice(), listed.targetPrice());
                continue;
            }
            // Same item, different size. Both passes above insist the row hold
            // exactly as many as the record says, and a record can be wrong
            // about that: a purchase that merged into a stack already held, a
            // partial fill, a re-key. When it is wrong, the position cannot
            // match its own row - so the position is written off as missing
            // and the row is left claimed by nobody. One stack becomes two
            // ghosts, on both sides of the same fact.
            //
            // The page is the authority on how many are up there. Take its
            // number and the price with it, the way a merged stack is already
            // taken at the size it really is.
            // Near the recorded size, not merely nearest. Nearest alone let a
            // stack of sixteen claim a listing of one and call it a resize:
            // that is not the same stock in a different quantity, it is a
            // different listing entirely, and taking it left the real row
            // unmatched and fourteen positions written off as missing. A
            // count can drift by a partial sale or a merge; it cannot drift
            // sixteenfold, so the match has to be close enough that no other
            // reading is plausible.
            int resized = -1;
            int nearest = Integer.MAX_VALUE;
            int floor = Math.max(1, listed.quantity() / 2);
            int ceiling = Math.max(2, listed.quantity() * 2);
            for (int i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                if (!row.itemId().equals(itemId)) continue;
                if (listed.itemKey().indexOf('#') >= 0 && !row.itemKey().equals(listed.itemKey())) continue;
                if (row.count() < floor || row.count() > ceiling) continue;
                int apart = Math.abs(row.count() - listed.quantity());
                if (apart < nearest) {
                    nearest = apart;
                    resized = i;
                }
            }
            if (resized >= 0) {
                var row = rows.remove(resized);
                Position was = listed;
                // A new position, not the same one wearing a different size.
                // Quantity is part of a position's stored identity, so writing
                // a different one back under the same id is refused - and it
                // is refused by failing the whole persistence layer, which
                // pauses the session. The way to change a position's size is
                // to close it and open its replacement, which is what the
                // purchase path already does when a stack merges.
                Position closed = was.closed(PositionStatus.CANCELLED, now, 0, Double.NaN);
                persistence.submit(checkpoint(sessionOpen, state.name(), closed, uncertainExposure,
                        "Superseded: the page shows x" + row.count() + " at " + row.displayedPrice()));
                Position fixed = new Position(nextPositionId++, was.mode(), was.itemKey(),
                        StackBucket.of(row.count()), row.count(), was.purchasePrice(), row.displayedPrice(),
                        was.purchasedAt(), was.listedAt() > 0 ? was.listedAt() : now, 0, 0,
                        Double.NaN, PositionStatus.LISTED);
                openListings.removeIf(p -> p.positionId() == was.positionId());
                openListings.add(fixed);
                persistence.submit(checkpoint(sessionOpen, state.name(), fixed, uncertainExposure,
                        "Resized to the " + row.count() + " actually on the page at " + row.displayedPrice()));
                LOGGER.info("DoughBay automation: parked listing #{} recorded as {} x{} at {} is really x{} at {}; "
                                + "reopened as #{} rather than losing both sides of it",
                        was.positionId(), was.itemKey(), was.quantity(), was.targetPrice(),
                        row.count(), row.displayedPrice(), fixed.positionId());
                continue;
            }
            if (uniqueExactPlainSlot(client, listed.itemKey(), listed.quantity()) >= 0) {
                // Not on the page and in the inventory: pulled back, never relisted.
                inHandCorroborated = true;
                LOGGER.info("DoughBay automation: parked listing #{} ({} x{}) is missing from the page but in the inventory; will relist",
                        listed.positionId(), listed.itemKey(), listed.quantity());
                continue;
            }
            if (listed.itemKey().indexOf('#') >= 0) {
                // A box that is neither on the page under its key nor in the
                // inventory under its key: when exactly one box of the kind sits
                // in the inventory that no other position or page row claims,
                // that is this position under a key an earlier mix-up recorded
                // wrongly. Re-key it to what is really in hand and relist it.
                int slot = unclaimedContainerSlot(client, listed, rows);
                if (slot >= 0) {
                    ItemStack box = client.player.getInventory().getNonEquipmentItems().get(slot);
                    String actual = itemId + "#" + dev.doughbay.fabric.ItemDescriptor.of(box).hash();
                    Position rekeyed = withItemKey(listed, actual);
                    openListings.replaceAll(p -> p.positionId() == listed.positionId() ? rekeyed : p);
                    persistence.submit(checkpoint(sessionOpen, state.name(), rekeyed,
                            uncertainExposure, "Re-keyed to the box in the inventory " + actual));
                    inHandCorroborated = true;
                    LOGGER.info("DoughBay automation: parked listing #{} ({} x{}) is missing from the page; the only unclaimed box in the inventory is {}; re-keyed, will relist",
                            listed.positionId(), listed.itemKey(), listed.quantity(), actual);
                    continue;
                }
            }
            missing.add(listed);
        }
        // A listing missing from the page either sold while the mod was not
        // listening or was pulled back by hand. Sales put money in the bank;
        // pull-backs do not. The balance rise since the last launch is the
        // room for booking sales, cheapest first; what it cannot cover was
        // returned to the player, never sold. Nine hand-pulled boxes booked as
        // $2.7M of profit on 2026-09-03 paid a payroll share on nothing.
        int returned = 0;
        // Unmanaged rows that match a position lately called "returned": the
        // ledger was wrong, the listing is still up. Take it back.
        int readopted = 0;
        for (java.util.Iterator<Position> it = recentlyReturned.iterator(); it.hasNext(); ) {
            Position gone = it.next();
            int hit = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rowMatches(rows.get(i), gone)) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) continue;
            AutomatedExecutionDriver.OwnListingRow row = rows.remove(hit);
            Position back = new Position(gone.positionId(), gone.mode(), gone.itemKey(), gone.bucket(), gone.quantity(),
                    gone.purchasePrice(), row.displayedPrice(), gone.purchasedAt(), gone.listedAt() > 0 ? gone.listedAt() : now,
                    0, 0, Double.NaN, PositionStatus.LISTED);
            openListings.add(back);
            committedSpend = Math.addExact(committedSpend, back.purchasePrice());
            persistence.submit(checkpoint(sessionOpen, state.name(), back, uncertainExposure,
                    "Re-adopted: still on the page after being booked as returned"));
            LOGGER.info("DoughBay automation: #{} ({} x{}) was booked as returned but is still up at {}; re-adopted",
                    back.positionId(), back.itemKey(), back.quantity(), row.displayedPrice());
            it.remove();
            readopted++;
        }
        // The tolerance is for drift in the balance arithmetic - a fee, a
        // payroll payment, a spend that landed between reads - and it was a
        // twentieth of the balance. That was a few tens of thousands when the
        // account held a million, and it is nine million now. A single audit
        // settles a handful of listings worth a few hundred thousand between
        // them, so the allowance had grown twenty times larger than the thing
        // it was allowing for, and every listing missing from the page was
        // booked as sold whether the money had arrived or not. Twenty-three
        // per cent of the profit on record was decided by this line.
        //
        // It scales with the balance far more slowly now, and stops. A million
        // of unexplained movement is already a generous thing to wave through;
        // beyond that the honest answer is that we do not know the listing
        // sold, and a listing we cannot account for is booked as returned
        // rather than as money.
        long slack = Math.max(50_000, Math.min(1_000_000, lastKnownBalance / 100));
        long room;
        if (auditBalance >= 0 && lastKnownBalance >= 0) {
            room = lastKnownBalance - (auditBalance - spentSinceAudit + salesSinceAudit) + slack;
        } else if (balanceBeforeStart >= 0 && lastKnownBalance >= 0) {
            room = lastKnownBalance - balanceBeforeStart + slack;
        } else {
            room = Long.MAX_VALUE / 4;
        }
        missing.sort(Comparator.comparingLong(Position::targetPrice));
        if (!missing.isEmpty()) {
            LOGGER.info("DoughBay automation: {} listing(s) missing from the page; {} of unexplained balance to "
                            + "account for them with (tolerance {})",
                    missing.size(), room == Long.MAX_VALUE / 4 ? "no measurement" : String.valueOf(room), slack);
        }
        for (Position listed : missing) {
            long net = Math.round(auctionFees.netSale(listed.targetPrice()));
            if (net <= room) {
                room -= net;
                closeOpenListingSoldOffline(listed, now);
                settled++;
            } else {
                closeOpenListingReturned(client, listed, now);
                returned++;
            }
        }
        // Rows still unmatched are listings that are up with no open position
        // behind them. That is not a player's own listing, which is what this
        // used to assume - it is a stack this bot bought and then lost the
        // record of, because the slot audit read one page of a two-page book
        // for days and closed everything it could not see. The listings stayed
        // live. Thirty of ninety slots ended up holding stock nothing would
        // reprice or pull back, and the auction house sat full.
        //
        // The record is what went missing, and the record is what says what
        // the stack cost, so the cancelled position is reopened rather than a
        // new one invented at today's price. Only cancellations are eligible -
        // a sold position has money against it and reopening one would conjure
        // stock that is gone - and each candidate is used once.
        if (!rows.isEmpty()) {
            long since = now - 3 * 24 * 3600_000L;
            List<Position> candidates = persistence.cancelledSnapshot();
            java.util.Set<Long> alreadyOpen = new HashSet<>();
            for (Position open : openListings) alreadyOpen.add(open.positionId());
            int reopened = 0;
            for (java.util.Iterator<AutomatedExecutionDriver.OwnListingRow> it = rows.iterator();
                 it.hasNext() && reopened < 20; ) {
                AutomatedExecutionDriver.OwnListingRow row = it.next();
                Position best = null;
                double bestDrift = Double.MAX_VALUE;
                for (Position gone : candidates) {
                    if (gone.closedAt() < since || alreadyOpen.contains(gone.positionId())) continue;
                    if (!rowMatches(row, gone)) continue;
                    // Item and count alone are too loose when the same stack
                    // size trades all day, so the price has to agree too - but
                    // not to the currency. Demanding it exactly meant nothing
                    // ever qualified: a stack repriced after it was cancelled
                    // is up at a number the closed record never held, and the
                    // pull-backs move prices every few minutes. Within a fifth
                    // is close enough to say "this is that stack" while still
                    // refusing a different one at a different price.
                    //
                    // Ties break on the nearest price rather than the newest
                    // record, so two cancelled stacks of the same item attach
                    // to the listing each was actually asking for.
                    long asked = row.displayedPrice();
                    long remembered = gone.targetPrice();
                    if (remembered <= 0 || asked <= 0) continue;
                    double drift = Math.abs(asked - remembered) / (double) Math.max(asked, remembered);
                    if (drift > 0.20) continue;
                    if (best == null || drift < bestDrift) {
                        best = gone;
                        bestDrift = drift;
                    }
                    continue;
                }
                if (best == null) continue;
                Position back = new Position(best.positionId(), best.mode(), best.itemKey(), best.bucket(),
                        best.quantity(), best.purchasePrice(), row.displayedPrice(), best.purchasedAt(),
                        best.listedAt() > 0 ? best.listedAt() : now, 0, 0, Double.NaN, PositionStatus.LISTED);
                openListings.add(back);
                alreadyOpen.add(back.positionId());
                committedSpend = Math.addExact(committedSpend, back.purchasePrice());
                persistence.submit(checkpoint(sessionOpen, state.name(), back, uncertainExposure,
                        "Reopened: still on the auction house after being cancelled in the books"));
                LOGGER.info("DoughBay automation: #{} ({} x{} at {}) is still up but was cancelled in the books; reopened",
                        back.positionId(), back.itemKey(), back.quantity(), row.displayedPrice());
                it.remove();
                reopened++;
            }
            if (reopened > 0) {
                LOGGER.info("DoughBay automation: reopened {} listing(s) the ledger had lost; {} still unmatched",
                        reopened, rows.size());
            } else if (!rows.isEmpty()) {
                // Say why nothing was taken back, because "it silently did
                // nothing" has been the hardest part of this to chase. Name
                // the first row that failed and the nearest thing to it.
                AutomatedExecutionDriver.OwnListingRow first = rows.get(0);
                Position nearest = null;
                double nearestDrift = Double.MAX_VALUE;
                int sameItem = 0;
                for (Position gone : candidates) {
                    if (!rowMatches(first, gone)) continue;
                    sameItem++;
                    long remembered = gone.targetPrice();
                    if (remembered <= 0) continue;
                    double d = Math.abs(first.displayedPrice() - remembered)
                            / (double) Math.max(first.displayedPrice(), remembered);
                    if (d < nearestDrift) {
                        nearestDrift = d;
                        nearest = gone;
                    }
                }
                LOGGER.info("DoughBay automation: {} unmatched listing(s), {} cancelled candidate(s) to draw on; "
                                + "first is {} x{} at {} - {} candidate(s) of that item, nearest asked {} ({}% away)",
                        rows.size(), candidates.size(), first.itemId(), first.count(), first.displayedPrice(),
                        sameItem, nearest == null ? "none" : String.valueOf(nearest.targetPrice()),
                        nearest == null ? "n/a" : Math.round(nearestDrift * 100));
            }
        }
        // Rows on our own page that no position claims, twice running.
        //
        // These are listings the server really made and the ledger never
        // recorded. The stack left the inventory, the auction house took it,
        // and the checkpoint that should have parked a position never
        // happened - the "inventory changed ambiguously during LIST" pause,
        // every time it fired. Nothing wrote them off, because nothing knew
        // they existed: seventeen of them here, and not one CANCELLED record
        // among them.
        //
        // The stock is real and the slot is spent, but with no position behind
        // it nothing ever reprices it and nothing ever pulls it back, so it
        // sits there until somebody happens to buy it. Seventeen of those and
        // a ninety-slot book trades like a seventy-three-slot one.
        //
        // Twice running, because one page-read hiccup must not mint a second
        // position for a listing that already has one.
        java.util.Map<String, Integer> unmanagedNow = new java.util.HashMap<>();
        for (AutomatedExecutionDriver.OwnListingRow row : rows) {
            unmanagedNow.merge(row.itemKey() + "|" + row.count() + "|" + row.displayedPrice(), 1, Integer::sum);
        }
        int adoptedOrphans = 0;
        if (dev.doughbay.fabric.Tuning.get("slots.adopt_orphans") >= 0.5) {
            for (java.util.Iterator<AutomatedExecutionDriver.OwnListingRow> it = rows.iterator(); it.hasNext(); ) {
                AutomatedExecutionDriver.OwnListingRow row = it.next();
                String signature = row.itemKey() + "|" + row.count() + "|" + row.displayedPrice();
                int seenLastTime = unmanagedLastAudit.getOrDefault(signature, 0);
                if (seenLastTime <= 0) continue;
                unmanagedLastAudit.put(signature, seenLastTime - 1);
                // What it cost is genuinely unknown, so it is booked at
                // break-even: the sale pays back exactly what this says it
                // cost, and no profit is invented from stock whose price
                // nobody recorded. The point of adopting is to get the slot
                // working again, not to decide after the fact what it was
                // worth.
                long breakEven = Math.max(1, Math.round(auctionFees.netSale(row.displayedPrice())));
                // Listed long ago, not now. When it actually went up is
                // unknowable, but it has been up long enough for the ledger to
                // lose it, so it is the stalest stock in the book by
                // definition. Stamping it with the current time would restart
                // its clock and make the one thing most in need of a pull-back
                // wait longest for one.
                long longAgo = now - 6 * 3600_000L;
                Position adopted = new Position(nextPositionId++, "REAL", row.itemKey(),
                        StackBucket.of(row.count()), row.count(), breakEven, row.displayedPrice(),
                        longAgo, longAgo, 0, 0, Double.NaN, PositionStatus.LISTED);
                openListings.add(adopted);
                persistence.submit(checkpoint(sessionOpen, state.name(), adopted, uncertainExposure,
                        "Adopted a listing found on your own page that the ledger never recorded"));
                LOGGER.info("DoughBay automation: adopted an unrecorded listing of yours: {} x{} at {} "
                                + "(what it cost is unknown, so it is booked at break-even {})",
                        row.itemKey(), row.count(), row.displayedPrice(), breakEven);
                it.remove();
                adoptedOrphans++;
            }
        }
        unmanagedLastAudit = unmanagedNow;
        if (adoptedOrphans > 0) {
            LOGGER.info("DoughBay automation: {} listing(s) the ledger had lost are managed again; "
                    + "they can now be repriced and pulled back like any other", adoptedOrphans);
        }
        // Always ask, whether or not anything was left over this time: the
        // answer arrives off this thread and is read on the pass after it, so
        // a snapshot that is only requested when rows are unmatched is always
        // one audit too late to use.
        persistence.requestCancelledSnapshot(now - 3 * 24 * 3600_000L);
        otherListedSlots = rows.size();
        rebaseAudit(now);
        if (readopted > 0) LOGGER.info("DoughBay automation: slot check re-adopted {} listing(s)", readopted);
        // Listings on the page the ledger does not know: named, so a lost
        // checkpoint or a hand listing can be told apart and put right.
        for (AutomatedExecutionDriver.OwnListingRow row : rows) {
            LOGGER.info("DoughBay automation: unmanaged listing on the page: {} x{} at {}",
                    row.itemKey(), row.count(), row.displayedPrice());
        }
        reconciliationStatus = settled + " listing(s) sold while offline; "
                + (returned > 0 ? returned + " returned to you unsold; " : "")
                + openListings.size() + " still up; " + rows.size() + " other listing(s) of yours";
        LOGGER.info("DoughBay automation: slot check: {}", reconciliationStatus);
        transition(State.SCANNING, reconciliationStatus);
    }

    /**
     * A listing missing from the page whose sale the balance cannot account
     * for: pulled back by hand and kept. Closed with no sale and no profit;
     * the item is the player's again.
     */
    private void closeOpenListingReturned(Minecraft client, Position listed, long now) {
        // "Returned to you" means the stack came back to the inventory, and
        // that is exactly where it was found sitting: three stacks in the
        // hotbar that nothing would ever list again. Closing the position was
        // the whole problem - the item is real, the cost is known, and the
        // only thing missing was anybody intending to sell it.
        //
        // So look before writing it off. If the stack is actually in hand it
        // goes back on the queue as a purchase waiting to be listed, keeping
        // its original cost, and the normal listing flow picks it up. Only a
        // stack that is genuinely nowhere is booked as returned.
        if (client != null && client.player != null
                && unlistedPurchaseSlot(client, listed.itemKey(), listed.quantity()) >= 0) {
            Position again = new Position(listed.positionId(), listed.mode(), listed.itemKey(),
                    listed.bucket(), listed.quantity(), listed.purchasePrice(), listed.targetPrice(),
                    listed.purchasedAt(), 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
            openListings.removeIf(p -> p.positionId() == listed.positionId());
            recoveredQueue.add(again);
            persistence.submit(checkpoint(sessionOpen, state.name(), again, uncertainExposure,
                    "Returned to the inventory and queued to be listed again"));
            LOGGER.info("DoughBay automation: parked listing #{} ({} x{}) came back to the inventory; queued to list again rather than written off",
                    listed.positionId(), listed.itemKey(), listed.quantity());
            return;
        }
        Position returned = listed.closed(PositionStatus.CANCELLED, now, 0, Double.NaN);
        recentlyReturned.add(returned);
        openListings.removeIf(p -> p.positionId() == listed.positionId());
        committedSpend = Math.max(0, committedSpend - listed.purchasePrice());
        persistence.submit(checkpoint(sessionOpen, state.name(), returned,
                uncertainExposure, "Parked listing missing and unpaid: returned to the player"));
        LOGGER.info("DoughBay automation: parked listing #{} ({} x{}) is missing from the page but the balance shows no sale of {}; booked as returned to you, not sold",
                listed.positionId(), listed.itemKey(), listed.quantity(), listed.targetPrice());
    }

    /**
     * A parked listing missing from the own-listings page sold while the
     * mod was not listening; the money is already in. Booked at its
     * listing price, the only price it could have sold at.
     */
    private void closeOpenListingSoldOffline(Position listed, long now) {
        long salePrice = listed.targetPrice();
        double profit = auctionFees.netSale(salePrice) - listed.purchasePrice();
        Position sold = listed.closed(PositionStatus.SOLD, now, salePrice, profit);
        openListings.removeIf(p -> p.positionId() == listed.positionId());
        committedSpend = Math.max(0, committedSpend - listed.purchasePrice());
        persistence.submit(checkpoint(sessionOpen, state.name(), sold,
                uncertainExposure, "Parked listing sold while offline: " + salePrice));
        LOGGER.info("DoughBay automation: parked listing #{} ({} x{}) sold while offline for {} (profit {})",
                listed.positionId(), listed.itemKey(), listed.quantity(), salePrice, Math.round(profit));
    }

    /**
     * A purchase the server confirmed but that never got listed, such as
     * one whose receipt arrived after the driver gave up, sits in the
     * inventory earning nothing. Every receipt is remembered for fifteen
     * minutes; a stack still in the inventory that matches one is put
     * through the normal listing flow with the receipt's price as its cost.
     * Only receipted stacks qualify, so the player's own items are never
     * touched.
     */
    /** Set by the slot check when it saw a listing missing from the page but present in the inventory. */
    private boolean inHandCorroborated;

    /**
     * A listed position whose exact stack is in the inventory was pulled
     * back, by the bot or by hand, and never relisted. Mid-run this needs
     * the server's "You collected your item" line within the last ten
     * minutes as corroboration, so the player's own items are never taken
     * for a pulled-back listing; after the startup slot check no such
     * proof is needed, because the auction page itself showed it missing.
     */
    private boolean startInHandRelisting(Minecraft client, long now) {
        if (trackedPosition != null || client == null || client.player == null || openListings.isEmpty()) return false;
        boolean corroborated = inHandCorroborated || now - driver.lastCollectedAt() < 10 * 60_000L;
        if (!corroborated) return false;
        for (Position listed : openListings) {
            int slot = uniqueExactPlainSlot(client, listed.itemKey(), listed.quantity());
            if (slot < 0) continue;
            if (closeStrayAuctionPage(client)) return true;
            if (!safeInventoryContext(client)) {
                detail = "Waiting before relisting a pulled-back stack: " + guiBlockerDescription(client);
                return true;
            }
            openListings.removeIf(p -> p.positionId() == listed.positionId());
            // A fresh id for the relist, not the listing's own, via the same
            // supersede pattern used everywhere else this can happen. An item
            // whose identity is not stable across the round trip - a shulker
            // box carries its contents, enchanted gear its enchants - comes
            // back under a different fingerprint than it was listed with, and
            // rewriting the old id with that new identity is exactly what the
            // persistence guard refuses, pausing the whole session for an hour
            // until a restart. Closing the old listing and opening a fresh id
            // cannot collide, so the pull-back relist can no longer stall it.
            Position closedListing = listed.closed(PositionStatus.CANCELLED, now, 0, Double.NaN);
            persistence.submit(checkpoint(sessionOpen, state.name(), closedListing, uncertainExposure,
                    "Pulled-back listing superseded by the stack in hand"));
            trackedPosition = new Position(nextPositionId++, listed.mode(), listed.itemKey(), listed.bucket(),
                    listed.quantity(), listed.purchasePrice(), listed.targetPrice(), listed.purchasedAt(),
                    0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
            LOGGER.info("DoughBay automation: listed position #{} ({} x{}) is in the inventory; superseding it as #{} and relisting at {}",
                    listed.positionId(), listed.itemKey(), listed.quantity(), trackedPosition.positionId(), listed.targetPrice());
            listPurchasedStackInHand(client, now, "Pulled-back stack found in the inventory");
            return true;
        }
        inHandCorroborated = false;
        return false;
    }

    private boolean startOrphanListing(Minecraft client, MarketWatcher.Snapshot snapshot, long now) {
        if (trackedPosition != null || client == null || client.player == null) return false;
        List<AutomatedExecutionDriver.PurchaseReceipt> receipts = driver.recentPurchaseReceipts();
        if (receipts.isEmpty()) return false;
        List<ItemStack> items = client.player.getInventory().getNonEquipmentItems();
        for (AutomatedExecutionDriver.PurchaseReceipt receipt : receipts) {
            int slot = -1;
            String itemId = "";
            int matches = 0;
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                if (stack.isEmpty() || stack.getCount() != receipt.count()
                        || !AutomatedExecutionDriver.plainForListing(stack)) continue;
                String id = itemId(stack);
                String name = AutomatedExecutionDriver.displayNameFor(id);
                if (name.isEmpty() || !receiptNames(name, receipt.itemName())) continue;
                matches++;
                slot = i;
                itemId = id;
            }
            if (matches != 1) continue;
            final String foundId = itemId;
            // A stack already booked as a position is not an orphan.
            boolean booked = openListings.stream().anyMatch(p -> baseItemId(p.itemKey()).equals(foundId)
                    && p.quantity() == receipt.count() && p.listedAt() > receipt.atMillis());
            if (booked) {
                driver.consumeReceipt(receipt);
                continue;
            }
            if (closeStrayAuctionPage(client)) return true;
            if (!safeInventoryContext(client)) {
                detail = "Waiting before listing an unlisted purchase: " + guiBlockerDescription(client);
                return true;
            }
            long target = 0;
            StackBucket bucket = StackBucket.of(receipt.count());
            for (MarketStats stats : snapshot.markets()) {
                if (stats != null && stats.hasPrices() && stats.itemKey().equals(foundId)
                        && stats.bucket() == bucket) {
                    target = (long) Math.floor(stats.weightedMedian());
                    break;
                }
            }
            double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
            long floor = receipt.price() + (long) Math.ceil(Math.max(minimumProfit, receipt.price() * 0.10));
            target = Math.max(target, floor);
            driver.consumeReceipt(receipt);
            tradesStarted++;
            committedSpend = Math.addExact(committedSpend, receipt.price());
            trackedPosition = new Position(nextPositionId++, "REAL", foundId, bucket, receipt.count(),
                    receipt.price(), target, receipt.atMillis(), 0, 0, 0, Double.NaN,
                    PositionStatus.PURCHASED);
            purchasedInventorySlot = slot;
            Listing own = new Listing("own:" + trackedPosition.positionId(), now, "", "market",
                    foundId, foundId, receipt.count(), receipt.price(), null);
            MarketStats placeholder = dev.doughbay.fabric.TestBuyPicker.placeholderStats(own, now);
            double net = auctionFees.netSale(target);
            positionValuation = new Opportunity(own, placeholder, receipt.price(), target,
                    net - receipt.price(), (net - receipt.price()) / Math.max(1, receipt.price()) * 100.0,
                    0, 1.0, 1.0, 0, List.of("Unlisted purchase found in the inventory"));
            pendingOpportunity = null;
            purchasePersistenceGeneration = persistence.submit(checkpoint(
                    true, State.PREPARING_LIST.name(), trackedPosition,
                    AutomationUncertainExposure.none(),
                    "Unlisted purchase listed from receipt: " + receipt.itemName()));
            if (purchasePersistenceGeneration <= 0) {
                trackedPosition = null;
                pauseInternal("Unlisted purchase could not be queued for persistence");
                return true;
            }
            clearCoverageEvidence();
            preListMarketVerified = true;
            preListVerifiedScanStartedAt = now;
            preListValuationCalculatedAt = now;
            preparationStartedAtMillis = now;
            originalSelectedSlot = -1;
            sourceInventorySlot = -1;
            swapIssuedAtTick = -1;
            probePending = false;
            prepareNotBeforeMillis = now + 500;
            listNotBeforeMillis = now + 1000;
            LOGGER.info("DoughBay automation: unlisted purchase {} x{} (receipt {}) found in the inventory; listing at {}",
                    foundId, receipt.count(), receipt.price(), target);
            transition(State.PREPARING_LIST, "Listing an unlisted purchase: " + baseItemId(foundId)
                    + " x" + receipt.count() + " bought at " + receipt.price());
            return true;
        }
        return false;
    }

    private static boolean receiptNames(String display, String receipt) {
        String d = display.strip().toLowerCase(java.util.Locale.ROOT);
        String r = receipt.strip().toLowerCase(java.util.Locale.ROOT);
        return d.equals(r) || (d + "s").equals(r) || (d + "es").equals(r)
                || (d.endsWith("y") && (d.substring(0, d.length() - 1) + "ies").equals(r));
    }

    /**
     * The lowest price at which the sale still clears the minimum profit.
     * A large ticket that has sat past its stop-loss window only has to
     * break even: the slot and the cash are worth more elsewhere.
     */
    /**
     * How long this stack has held a slot: since it was bought, not since it
     * was last relisted. Every pull-back is a new listing with a fresh
     * listedAt, so a clock read from there restarted at each step down and
     * the stop-loss and loss floor never came due for exactly the stock that
     * was being repriced most - banners relisted at cost every few minutes
     * showed "up for 24 min" after holding a slot all afternoon.
     */
    private static long heldMillis(Position listed, long now) {
        long since = listed.purchasedAt() > 0 ? listed.purchasedAt() : listed.listedAt();
        return since > 0 ? Math.max(0, now - since) : 0;
    }

    private long repriceFloor(Position listed, long now) {
        // Clearing out a market: cost stops mattering, so the only floor left is
        // a safety one at half of cost, there to stop a misread market dumping
        // the stock for almost nothing. Where the price actually lands - at the
        // market, below it, in a dead hour - is repricedTarget's job; this only
        // says how far down it is ever allowed to go.
        if (dev.doughbay.fabric.Tuning.liquidating(baseItemId(listed.itemKey()))) {
            return Math.max(1, (long) Math.floor(listed.purchasePrice() * 0.5));
        }
        double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
        long age = heldMillis(listed, now);
        if (tierOf(listed.purchasePrice()) == Tier.LARGE && listed.listedAt() > 0
                && age > LARGE_STOP_LOSS_AFTER_MILLIS()) {
            minimumProfit = 0;
        }
        // The floor is what strands a listing. Once the price has stepped down
        // to cost plus the minimum profit it can go no lower, so the listing is
        // skipped for ever: five of them sat for twenty-one hours against a
        // two-minute median. A slot is worth more than the last few percent of
        // a margin, so the longer one sits the less profit it may insist on.
        long giveUpProfit = (long) (dev.doughbay.fabric.Tuning.get("reprice.stop_loss_min") * 60_000L);
        if (giveUpProfit > 0 && age > giveUpProfit) minimumProfit = 0;
        long floor = listed.purchasePrice() + (long) Math.ceil(minimumProfit);
        // And past the second mark it may take a loss, because a stack that
        // will not sell at cost is not going to sell at cost tomorrow either.
        long below = (long) (dev.doughbay.fabric.Tuning.get("reprice.below_cost_min") * 60_000L);
        if (below > 0 && age > below) {
            double keep = dev.doughbay.fabric.Tuning.get("reprice.below_cost_floor_pct") / 100.0;
            return Math.max(1, (long) Math.floor(listed.purchasePrice() * keep));
        }
        for (int i = 0; i < 400 && auctionFees.netSale(floor) < listed.purchasePrice() + minimumProfit; i++) {
            floor = (long) Math.ceil(floor * 1.005) + 1;
        }
        return floor;
    }

    /**
     * What the market is paying for this exact item at this exact stack size,
     * or null when it cannot be read.
     *
     * <p>Bucketed, and that is the point: a name tag on its own and a stack of
     * sixty-four are two different markets, and only one of them was ever
     * going to take our stock.
     */
    private Long marketStackPrice(Position listed) {
        MarketWatcher.Snapshot snap = lastSnapshot;
        if (snap == null) return null;
        String itemId = baseItemId(listed.itemKey());
        StackBucket bucket = StackBucket.of(listed.quantity());
        for (MarketStats m : snap.markets()) {
            if (!m.itemKey().equals(itemId) || m.bucket() != bucket) continue;
            if (!m.hasPrices() || m.sampleCount() < 8 || !(m.quickSalePrice() > 0)) continue;
            return (long) Math.floor(m.quickSalePrice());
        }
        return null;
    }

    /**
     * The next ask for a listing that has sat too long.
     *
     * <p>The ladder steps down from our own last ask on a clock and never asks
     * what the market is doing, which is right exactly once: when we are above
     * the market and one cut puts us in the queue. When the market has moved
     * away instead, the same ladder chases it down a rung at a time and pays a
     * percentage on every one.
     *
     * <p>Name tags on 2026-09-06 are the case that argues for this. We were
     * asking about twenty thousand each against a market median near thirty -
     * already a third under the market and still not selling, because the
     * market was for single tags and we held stacks of sixty-four. Eight more
     * cuts followed and every one of them was margin given away against a
     * problem that was never the price. Thirty million of capital came back
     * three and a half million lighter.
     *
     * <p>So when this is on, the market decides instead of the clock: already
     * at or under it, the price is not why the stack is sitting and nothing is
     * cut; above it, the gap closes in one move rather than eight. The floor
     * is untouched either way, so the minimum profit still holds until the
     * stop-loss mark and cost still holds until the second one - this changes
     * where the price lands, never what it is allowed to cost.
     *
     * <p>Not cutting is not the same as holding for ever: past the stop-loss
     * mark the ladder comes back, because a slot that earns nothing is its own
     * expense and freeing it eventually is the whole point of the pull-back.
     */
    private long repricedTarget(Position listed, long now) {
        // Clearing out a market overrides the whole ladder below. A liquidated
        // item is being sold off, so it ignores the profit floor and the
        // dead-hour hold that keep an ordinary listing patient: it undercuts the
        // market at once, or - when the market cannot be read - cuts a tenth off
        // its own ask each pass, and only ever moves down, never below the
        // half-of-cost safety floor.
        if (dev.doughbay.fabric.Tuning.liquidating(baseItemId(listed.itemKey()))) {
            long floorNow = repriceFloor(listed, now);
            Long market = marketStackPrice(listed);
            long aim = market != null
                    ? (long) Math.floor(market * (1.0 - UNDERCUT_PERCENT / 100.0))
                    : (long) Math.floor(listed.targetPrice() * 0.90);
            aim = Math.max(floorNow, aim);
            return Math.min(listed.targetPrice(), aim);
        }
        long floor = repriceFloor(listed, now);
        // Under the floor: the only honest move is up to it. Nothing below
        // - dead hours, market reads, the ladder - applies to an ask that
        // should never have been this low.
        if (listed.targetPrice() < floor) return floor;
        // A price cut only does anything if somebody is there to take it. The
        // buy side already refuses a market in its dead hours; the repricer
        // never asked, so it walked prices down against buyers who were asleep
        // and had already given away a third of the margin by the time they
        // arrived. The stack was not mispriced - it was the wrong hour, and the
        // ladder cannot tell the difference.
        //
        // Below the dead-hour mark the ask is left where it is and the clock
        // starts again when the market wakes up. This never blocks the floor
        // from falling on age: a stack past its stop-loss still comes down,
        // because a slot held for ever costs more than a thin hour does.
        long sinceListed = heldMillis(listed, now);
        long giveUp = (long) (dev.doughbay.fabric.Tuning.get("reprice.stop_loss_min") * 60_000L);
        if (giveUp <= 0 || sinceListed <= giveUp) {
            double demand = demandNow(baseItemId(listed.itemKey()), listed.quantity());
            if (demand < dev.doughbay.fabric.Tuning.get("demand.dead_pct") / 100.0) {
                return listed.targetPrice();
            }
        }
        double step = tierRepriceStepPercent(tierOf(listed.purchasePrice()));
        long stepped = (long) Math.floor(listed.targetPrice() * (1.0 - step / 100.0));
        if (dev.doughbay.fabric.Tuning.get("reprice.to_market") < 0.5) {
            return Math.max(floor, stepped);
        }
        Long market = marketStackPrice(listed);
        if (market == null) return Math.max(floor, stepped);
        long giveUpProfit = (long) (dev.doughbay.fabric.Tuning.get("reprice.stop_loss_min") * 60_000L);
        long age = heldMillis(listed, now);
        boolean patienceLeft = giveUpProfit <= 0 || age <= giveUpProfit;
        if (listed.targetPrice() <= market && patienceLeft) {
            // Under the market already: repriceDue() wants a lower number than
            // the current ask, so returning the ask unchanged skips this one.
            return listed.targetPrice();
        }
        if (listed.targetPrice() > market) {
            long toMarket = (long) Math.floor(market * (1.0 - UNDERCUT_PERCENT / 100.0));
            return Math.max(floor, Math.min(stepped, toMarket));
        }
        return Math.max(floor, stepped);
    }

    private long repriceAfterMillis(Position listed) {
        long hold = tierRepriceAfterMillis(tierOf(listed.purchasePrice()));
        // A full book changes what waiting costs. With a slot to spare, sitting
        // on a listing is patience: it may still sell at the price we asked.
        // With no slot to spare it is not patience, it is the thing stopping
        // every other trade - the desk cannot buy, the queue cannot list, and
        // the only move left on the board is making something sell. So the
        // wait is cut short, because a slot that frees is worth more than the
        // few percent the full price might still have fetched.
        return bookFullNow(System.currentTimeMillis()) ? Math.max(60_000L, hold / 3) : hold;
    }

    /**
     * If a parked listing has sat unsold long enough and can still come down
     * a step, pull it back now. Returns true when a pull-back was started.
     */
    /** The oldest parked listing that has sat long enough and can still come down a step, or null. */
    private Position repriceDue(long now) {
        Position due = null;
        for (Position listed : openListings) {
            if (listed.listedAt() <= 0 || now - listed.listedAt() < repriceAfterMillis(listed)) continue;
            if (now - repriceAttemptedAt.getOrDefault(listed.positionId(), 0L) < REPRICE_RETRY_MILLIS()) continue;
            // Do not walk back the price of something we no longer trade. An
            // item on the deny list is being wound down or handled by hand, and
            // repricing it in a loop burns turns that should be finding the next
            // flip - the banners did exactly this, 8 of every 12 pull-backs. A
            // liquidating item is the exception: clearing it out is the point.
            String repriceItem = baseItemId(listed.itemKey());
            if (!dev.doughbay.fabric.Tuning.itemAllowed(repriceItem)
                    && !dev.doughbay.fabric.Tuning.liquidating(repriceItem)) continue;
            // The floor cuts both ways. The ladder only ever moved an ask
            // down, so an ask already sitting under the floor - listed under
            // an older rule, or against a market that has since fallen - just
            // sat there until a buyer took the loss: a lead stack bought at
            // 150k with a standing ask of 104k from the night before. A
            // standing ask below the floor is due to be lifted to it.
            long floorNow = repriceFloor(listed, now);
            boolean underFloor = listed.targetPrice() < floorNow;
            if (!underFloor && repricedTarget(listed, now) >= listed.targetPrice()) continue;
            if (due == null || listed.listedAt() < due.listedAt()) due = listed;
        }
        return due;
    }

    /** Turns the bid desk has taken since a listing was last repriced; the desk must not starve the reprices. */
    private int deskTurnsSinceReprice;

    private boolean startRepriceIfDue(Minecraft client, long now) {
        if (dev.doughbay.fabric.ServerStrain.holding()) {
            detail = "Holding the pull-backs: " + dev.doughbay.fabric.ServerStrain.describe();
            return false;
        }
        if (openListings.isEmpty()) return false;
        Position due = repriceDue(now);
        if (due == null) return false;
        if (closeStrayAuctionPage(client)) return true;
        if (!safeInventoryContext(client) && !modScreenOpen(client)) {
            detail = "Waiting before repricing: " + guiBlockerDescription(client);
            return true;
        }
        if (modScreenOpen(client)) {
            client.gui.setScreen(null);
            detail = "Closing the GoNuts screen to reprice a listing";
            return true;
        }
        if (!safeInventoryContext(client)) {
            detail = "Waiting before repricing: " + guiBlockerDescription(client);
            return true;
        }
        Inventory inventory = client.player.getInventory();
        if (!inventory.getItem(policy.reservedHotbarSlot()).isEmpty()) {
            detail = "Reserved hotbar slot is occupied; cannot reprice until it is empty";
            repriceAttemptedAt.put(due.positionId(), now);
            return false;
        }
        String itemId = baseItemId(due.itemKey());
        if (inventoryContainsItem(client, itemId)) {
            detail = "Cannot reprice " + itemId + " while the inventory already holds some";
            repriceAttemptedAt.put(due.positionId(), now);
            return false;
        }
        long newPrice = repricedTarget(due, now);
        repriceAttemptedAt.put(due.positionId(), now);
        inventoryBeforeBuy = copyNonEquipmentInventory(inventory);
        awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
        ExecutionResult result = driver.cancelOwnListing(itemId, due.quantity(), due.targetPrice());
        if (!result.ok()) {
            detail = "Reprice could not start: " + result.detail();
            return false;
        }
        repricing = due;
        repricingNewPrice = newPrice;
        repriceStartedTick = controllerTick;
        repriceStartedAtMillis = now;
        reconciliationStatus = "Repricing " + itemId + " x" + due.quantity()
                + " from " + due.targetPrice() + " to " + newPrice
                + " after " + ((now - due.listedAt()) / 60000) + " min unsold ("
                + tierOf(due.purchasePrice()).name().toLowerCase(java.util.Locale.ROOT) + " tier)";
        transition(State.REPRICING, reconciliationStatus);
        return true;
    }

    private void observeRepriceTerminal(Minecraft client, long now) {
        if (repricing == null) {
            transition(State.SCANNING, "No pull-back in progress; scanning");
            return;
        }
        // A pull-back that never resolves used to wait for its cancel result
        // every tick, for ever: a stray auction page the server kept reopening
        // blocked the driver, no terminal event ever came, and the session sat
        // in REPRICING for over two hours - the liveness watchdog saw it and
        // could only complain. A pull-back that has not finished well past any
        // reasonable time is abandoned: the driver is stopped, the book is
        // recounted against the server, and scanning resumes. If the stack was
        // actually pulled it is in the pack and the in-hand relister takes it.
        if (repriceStartedAtMillis > 0 && now - repriceStartedAtMillis > REPRICE_DEADLINE_MILLIS) {
            String itemId = baseItemId(repricing.itemKey());
            LOGGER.warn("DoughBay automation: pull-back of {} x{} did not complete in {} s; abandoning it and recounting the book",
                    itemId, repricing.quantity(), REPRICE_DEADLINE_MILLIS / 1000);
            repriceAttemptedAt.put(repricing.positionId(), now);
            repricing = null;
            repricingArrived = false;
            driver.emergencyStop();
            restoreSelectedSlot(client);
            if (now - lastDriftAuditAt > 60_000L) {
                lastDriftAuditAt = now;
                slotAuditPending = true;
            }
            transition(State.SCANNING, "Pull-back timed out; recounting the book and scanning");
            return;
        }
        AutomatedExecutionDriver.TerminalEvent event = nextTerminal(
                AutomatedExecutionDriver.OperationIntent.CANCEL);
        if (event == null) {
            if (repricingArrived) {
                finishReprice(client, now);
            }
            return;
        }
        if (event.intent() != AutomatedExecutionDriver.OperationIntent.CANCEL
                || !event.listingKey().startsWith("cancel:")) {
            pauseInternal("Unexpected terminal event while pulling back a listing");
            return;
        }
        if (event.outcome() != AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED) {
            String itemId = baseItemId(repricing.itemKey());
            LOGGER.info("DoughBay automation: pull-back of {} x{} at {} failed: {}",
                    itemId, repricing.quantity(), repricing.targetPrice(), event.detail());
            // A listing the page does not have, after walking every page of
            // it, is not a page problem: it is a listing that is not there.
            // The ledger said seventy-two were up while the server counted
            // forty-one, and the repricer spent the difference hunting the
            // thirty-one that were gone - failing, waiting, and failing again.
            // Those phantoms also count against the outstanding-spend cap,
            // which is the same drift that locked a client out twice today.
            // So missing from the page is evidence, and it asks the server to
            // settle the whole book rather than guessing at this one row.
            String why = event.detail() == null ? "" : event.detail();
            if (why.contains("is not among your listings") && now - lastDriftAuditAt > 60_000L) {
                lastDriftAuditAt = now;
                slotAuditPending = true;
                LOGGER.info("DoughBay: {} was not on any page of your listings; recounting the book against the server",
                        itemId);
            }
            // Not straight away again: the same failure nine seconds later is a loop.
            repriceAttemptedAt.put(repricing.positionId(), now);
            repricing = null;
            transition(State.SCANNING, "Pull-back failed (" + event.detail() + "); listing stays up");
            return;
        }
        repricingArrived = true;
        repriceVerifiedTick = controllerTick;
        finishReprice(client, now);
    }

    private boolean repricingArrived;
    private long repriceVerifiedTick;
    /** The sell command waits until this moment; the server reopens the auction page after a pull-back. */
    private long listNotBeforeMillis;
    private int listRetries;
    /** When the wait for a durable LIST intent began; 0 while not waiting. */
    private long listIntentWaitSinceMillis;
    /** The stack is not touched before this moment: the server reopens the auction page a second after a pull-back. */
    private long prepareNotBeforeMillis;

    /**
     * Puts a stack away instead of relisting it under cost.
     *
     * <p>Only ever at the moment the ladder would book a loss. This is a
     * failsafe rather than a strategy: nothing goes on the shelf because it
     * might do better later, only because the alternative is selling it for
     * less than it cost.
     */
    private boolean shelveRatherThanLose(Minecraft client, Position listed, long now) {
        if (dev.doughbay.fabric.Tuning.get("shelf.instead_of_loss") < 0.5) return false;
        if (!dev.doughbay.fabric.StashDesk.enabled()) return false;
        if (!worthShelving(listed, now)) return false;
        Position put = new Position(listed.positionId(), listed.mode(), listed.itemKey(),
                listed.bucket(), listed.quantity(), listed.purchasePrice(), listed.targetPrice(),
                listed.purchasedAt(), 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
        shelved.add(put);
        dev.doughbay.fabric.Shelf.take(put.positionId());
        // Nothing lists it while it waits for a shelf visit, and nothing
        // adopts it as a stray either.
        setAsideItems.add(put.itemKey());
        setAsideItems.add(baseItemId(put.itemKey()));
        refreshShelfWants(now);
        persistence.submit(checkpoint(sessionOpen, state.name(), put, uncertainExposure,
                "Taken off the auction house rather than sold under cost; waiting on the shelf"));
        Long market = marketStackPrice(listed);
        LOGGER.info("DoughBay shelf: {} x{} cost {} and the market is at {}; up {} min and it would relist at {}. "
                        + "Putting it away rather than walking the loss down",
                baseItemId(put.itemKey()), put.quantity(), put.purchasePrice(),
                market == null ? "unknown" : String.valueOf(market),
                listed.listedAt() > 0 ? (now - listed.listedAt()) / 60_000L : 0, repricingNewPrice);
        transition(State.SCANNING, "Put away rather than sold under cost; "
                + shelved.size() + " stack(s) waiting on the shelf");
        return true;
    }

    /**
     * Whether this stack has outlived its market.
     *
     * <p>The first version of this waited for the ladder to reach its last
     * rung - the point where a listing may be priced under what it cost - and
     * that rung is twelve hours away. Nine sales in ten are gone in three
     * minutes and ninety-nine in a hundred inside twelve hours, so the shelf
     * was aimed at a moment that almost never arrives, and sat idle while the
     * thing it exists for happened all day.
     *
     * <p>What the slot-time actually says: the slowest one per cent of
     * listings occupy forty-five per cent of the book. Those are not stock
     * waiting for a buyer, they are stock whose market has moved below what it
     * cost - and no number of ten per cent cuts fixes that, it just walks the
     * loss down in steps. So the test is the market, not the ladder: if what
     * the stack would fetch today does not cover what it cost, and it has been
     * up long enough that a passing dip is ruled out, it comes off the book.
     *
     * <p>The age gate is what keeps this from touching anything healthy. By
     * two hours, ninety-six sales in a hundred have already happened.
     */
    private boolean worthShelving(Position listed, long now) {
        // The ladder's own answer still counts: about to relist under cost is
        // reason enough whatever its age.
        if (auctionFees.netSale(repricingNewPrice) < listed.purchasePrice()) return true;
        long after = (long) (dev.doughbay.fabric.Tuning.get("shelf.after_min") * 60_000L);
        if (after <= 0) return false;
        long age = listed.listedAt() > 0 ? now - listed.listedAt() : 0;
        if (age < after) return false;
        Long market = marketStackPrice(listed);
        if (market == null) return false;
        return auctionFees.netSale(market) < listed.purchasePrice();
    }

    /**
     * Puts away anything the shelf has claimed but is still being carried.
     *
     * <p>The run itself is the same one the wander uses - place a chest, open
     * it, move the stock, close, put the view back - so this only decides
     * when, not how. It takes a turn like the desk or a pull-back does rather
     * than interrupting one, because nothing here is urgent enough to be
     * worth a half-finished listing.
     */
    private boolean visitShelfIfDue(Minecraft client, long now) {
        if (shelved.isEmpty()) return false;
        if (!dev.doughbay.fabric.StashDesk.enabled()) return false;
        // The decision to hold stock off the auction house is the whole
        // point; physically carrying it to a chest is the flourish. When the
        // flourish is off, stock simply waits in the pack, set aside, still
        // safe from the loss ladder - and the run that fights the server's
        // jump never happens.
        if (dev.doughbay.fabric.Tuning.get("shelf.place_in_chest") < 0.5) return false;
        if (dev.doughbay.fabric.StashDesk.busy()) return false;
        // A shelf trip is a visible physical act - a jump, a placement, a
        // chest opening. Doing it on a loop, every few seconds, is the single
        // most obvious bot tell there is, worse than any freeze. So the rate
        // is capped hard and a shelf that is not getting anywhere stops
        // knocking:
        //
        //   - never more than one trip a minute, whatever is asking;
        //   - if three trips in a row leave the shelved count unchanged, the
        //     stock cannot be put away for some reason, so hold off for a good
        //     while rather than repeat a pointless, conspicuous act.
        long minGap = (long) (dev.doughbay.fabric.Tuning.get("shelf.min_gap_sec") * 1000);
        if (minGap <= 0) minGap = 60_000L;
        if (now - lastShelfRunAt < minGap) return false;
        if (now < shelfHeldOffUntil) return false;
        // Did the last trip help? If shelved did not shrink since then, it did
        // nothing useful.
        if (shelvedAtLastRun >= 0) {
            if (shelved.size() >= shelvedAtLastRun) {
                shelfNoProgress++;
            } else {
                shelfNoProgress = 0;
            }
        }
        if (shelfNoProgress >= 3) {
            shelfHeldOffUntil = now + 15 * 60_000L;
            shelfNoProgress = 0;
            LOGGER.warn("DoughBay shelf: three trips changed nothing - the stock cannot be put away; "
                    + "holding the shelf off for fifteen minutes rather than looping");
            return false;
        }
        if (dev.doughbay.fabric.ServerStrain.holding()) return false;
        if (client == null || client.player == null) return false;
        // The driver must be genuinely idle, not merely screenless. The server
        // pushes the auction page back open a second after almost every step,
        // so "no screen right now" is true for a moment and false again before
        // the jump - which started three runs in forty seconds, each dying
        // when the page came back, with the chest bouncing between the hand
        // and the off hand. The pull-backs have always taken this precaution;
        // this did not.
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return false;
        if (closeStrayAuctionPage(client)) return false;
        if (modScreenOpen(client)) return false;
        if (client.gui.screen() != null || !safeInventoryContext(client)) return false;
        // Only if it is actually in the pack. Stock already on the shelf needs
        // no trip, and asking for one would place a chest to look at nothing.
        // Only stock that genuinely needs putting away - market still below
        // cost - and is actually in the pack. Stock whose price has recovered
        // is listed by reclaimRecoveredPackStock instead, and running a chest
        // trip for it found nothing to deposit and looped.
        boolean carrying = false;
        for (Position p : shelved) {
            Long market = marketStackPrice(p);
            boolean recovered = market != null && auctionFees.netSale(market) >= p.purchasePrice();
            if (recovered) continue;
            if (unlistedPurchaseSlot(client, p.itemKey(), p.quantity()) >= 0) {
                carrying = true;
                break;
            }
        }
        if (!carrying) return false;
        if (!dev.doughbay.fabric.StashDesk.run(client)) return false;
        lastShelfRunAt = now;
        shelvedAtLastRun = shelved.size();
        LOGGER.info("DoughBay shelf: carrying {} stack(s) that belong on the shelf; going to put them away",
                shelved.size());
        detail = "Putting " + shelved.size() + " stack(s) away on the shelf";
        return true;
    }

    /**
     * Tells the shelf what to hold and what to hand back.
     *
     * <p>Handing back is the whole point of holding: a stack comes off the
     * shelf the moment the market clears what it cost, and goes straight into
     * the ordinary listing flow as the purchase it always was.
     */
    private void refreshShelfWants(long now) {
        List<String> away = new ArrayList<>();
        List<String> back = new ArrayList<>();
        for (Position p : shelved) {
            String id = baseItemId(p.itemKey());
            Long market = marketStackPrice(p);
            if (market != null && auctionFees.netSale(market) >= p.purchasePrice()) {
                back.add(id);
            } else {
                away.add(id);
            }
        }
        dev.doughbay.fabric.StashDesk.putAway(away);
        dev.doughbay.fabric.StashDesk.fetch(back);
    }

    /**
     * Takes back what the shelf actually handed over.
     *
     * <p>Claimed on the move rather than on the request: the position rejoins
     * the queue only once the stack is really in the pack, because a position
     * booked as back when the click never happened is a position nothing will
     * ever find again.
     */
    /**
     * Lists shelved stock that never reached the chest once its market comes
     * back.
     *
     * <p>A stack set aside to dodge a loss is in the pack, not the chest, until
     * a shelf visit carries it over. If the price recovers in that window the
     * shelf marked it "fetch" - and a fetch pulls from the chest, where it is
     * not, so the visit found nothing to do and fired again the next tick,
     * opening the chest over and over. The stock is already in hand; the right
     * answer is simply to list it, not to walk to a chest for it.
     */
    private void reclaimRecoveredPackStock(Minecraft client, long now) {
        if (shelved.isEmpty() || client == null || client.player == null) return;
        // The shelf holds a stack in the pack, not a chest, until its market
        // comes back. With no chest run that wait is unbounded, and a pack that
        // fills with stock waiting on a recovery that never comes wedges the
        // whole client: nothing can be collected, and a stray item in the
        // reserved listing slot can never be moved out because there is no free
        // slot to move it to - which is exactly how a full pack turned into a
        // dead bot. When the pack runs low, shelved stock that has sat past its
        // loss window is listed at the floor to free a slot, the same bargain
        // the loss ladder already makes for a listing that will not sell.
        boolean packPressure = freeInventorySlots(client) <= SHELF_DRAIN_FREE_SLOTS;
        long lossWindow = (long) (dev.doughbay.fabric.Tuning.get("reprice.below_cost_min") * 60_000L);
        for (var it = shelved.iterator(); it.hasNext(); ) {
            Position p = it.next();
            Long market = marketStackPrice(p);
            boolean recovered = market != null && auctionFees.netSale(market) >= p.purchasePrice();
            boolean drainAtLoss = !recovered && packPressure && lossWindow > 0
                    && p.purchasedAt() > 0 && heldMillis(p, now) > lossWindow;
            // A liquidating item leaves the shelf the moment its stack is in the
            // pack, whatever the market or the pack pressure: being rid of it is
            // the point, and it lists at the liquidation price below.
            boolean liquidate = dev.doughbay.fabric.Tuning.liquidating(baseItemId(p.itemKey()));
            if (!recovered && !drainAtLoss && !liquidate) continue;
            if (unlistedPurchaseSlot(client, p.itemKey(), p.quantity()) < 0) continue;
            it.remove();
            dev.doughbay.fabric.Shelf.release(p.positionId());
            setAsideItems.remove(p.itemKey());
            setAsideItems.remove(baseItemId(p.itemKey()));
            if (liquidate && !drainAtLoss) {
                long liq = repriceFloor(p, now);
                if (market != null) {
                    long mkt = (long) Math.floor(market * (1.0 - UNDERCUT_PERCENT / 100.0));
                    liq = Math.max(liq, mkt);
                }
                Position atMkt = p.targetPrice() > liq ? withTargetPrice(p, liq) : p;
                recoveredQueue.add(atMkt);
                LOGGER.info("DoughBay shelf: liquidating {} x{} (cost {}); listing to sell it off at {}",
                        baseItemId(p.itemKey()), p.quantity(), p.purchasePrice(), atMkt.targetPrice());
            } else if (drainAtLoss) {
                // List at the floor: the slot is worth more than the last of a
                // margin that is not coming back.
                long floor = repriceFloor(p, now);
                Position atFloor = p.targetPrice() > floor ? withTargetPrice(p, floor) : p;
                recoveredQueue.add(atFloor);
                LOGGER.info("DoughBay shelf: {} x{} sat {} min in a full pack with its market still under cost {}; "
                                + "listing it at {} to free a slot",
                        baseItemId(p.itemKey()), p.quantity(), heldMillis(p, now) / 60_000L,
                        p.purchasePrice(), atFloor.targetPrice());
            } else {
                recoveredQueue.add(p);
                LOGGER.info("DoughBay shelf: {} x{} never left the pack and its market is back at cost {}; "
                                + "listing it rather than walking to the chest",
                        baseItemId(p.itemKey()), p.quantity(), p.purchasePrice());
            }
        }
    }

    /** Below this many free pack slots, shelved stock past its loss window is drained to the auction. */
    private static final int SHELF_DRAIN_FREE_SLOTS = 4;

    private void claimFetchedStock(long now) {
        List<String> came = dev.doughbay.fabric.StashDesk.takeFetched();
        if (came.isEmpty()) return;
        for (String id : came) {
            for (var it = shelved.iterator(); it.hasNext(); ) {
                Position p = it.next();
                if (!baseItemId(p.itemKey()).equals(id)) continue;
                it.remove();
                dev.doughbay.fabric.Shelf.release(p.positionId());
                setAsideItems.remove(p.itemKey());
                setAsideItems.remove(id);
                recoveredQueue.add(p);
                LOGGER.info("DoughBay shelf: {} x{} came back off the shelf at a cost of {}; listing it again",
                        id, p.quantity(), p.purchasePrice());
                break;
            }
        }
    }

    private void finishReprice(Minecraft client, long now) {
        String itemId = baseItemId(repricing.itemKey());
        // The full key carries the descriptor hash for a box, so a pulled-back
        // box is recognised by its contents, not mistaken for a plain box.
        int arrival = exactSingleArrivalSlot(client, repricing.itemKey(), repricing.quantity());
        if (arrival < 0 && repricing.itemKey().indexOf('#') >= 0) {
            // The pulled-back box's contents hash differs from the position's key:
            // the slot check once matched this position to another box. What
            // came back is still this listing's box; re-key rather than strand it.
            int box = singleNewContainerSlot(client, itemId, repricing.quantity());
            if (box >= 0) {
                ItemStack stack = client.player.getInventory().getNonEquipmentItems().get(box);
                String actual = itemId + "#" + dev.doughbay.fabric.ItemDescriptor.of(stack).hash();
                LOGGER.info("DoughBay automation: pulled-back box is {} not {}; re-keying position #{}",
                        actual, repricing.itemKey(), repricing.positionId());
                repricing = withItemKey(repricing, actual);
                arrival = box;
            }
        }
        if (arrival < 0) {
            if (controllerTick - repriceVerifiedTick < ARRIVAL_GRACE_TICKS) {
                detail = "Pulled back; waiting for the stack to land in the inventory";
                return;
            }
            repricingArrived = false;
            repricing = null;
            pauseInternal("Pulled a listing back but no single new stack of " + itemId
                    + " arrived; inspect the inventory");
            return;
        }
        repricingArrived = false;
        Position listed = repricing;
        repricing = null;
        openListings.removeIf(p -> p.positionId() == listed.positionId());
        if (shelveRatherThanLose(client, listed, now)) return;
        // The stack is in the inventory, so the server has definitely taken
        // the listing down and that slot is free. The relist a moment later
        // will put one back; without this the pair only ever counted upward.
        cancelledSinceAudit++;
        noteSlotFreed();
        trackedPosition = new Position(listed.positionId(), listed.mode(), listed.itemKey(),
                listed.bucket(), listed.quantity(), listed.purchasePrice(), repricingNewPrice,
                listed.purchasedAt(), 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
        purchasedInventorySlot = arrival;
        Listing own = new Listing("own:" + listed.positionId(), now, "", "market",
                listed.itemKey(), itemId, listed.quantity(), listed.purchasePrice(), null);
        MarketStats stats = dev.doughbay.fabric.TestBuyPicker.placeholderStats(own, now);
        double net = auctionFees.netSale(repricingNewPrice);
        positionValuation = new Opportunity(own, stats, listed.purchasePrice(), repricingNewPrice,
                net - listed.purchasePrice(),
                (net - listed.purchasePrice()) / Math.max(1, listed.purchasePrice()) * 100.0,
                0, 1.0, 1.0, 0, List.of("Repriced after sitting unsold"));
        pendingOpportunity = null;
        buyIntentCoverageBoundaryMillis = 0;
        purchasePersistenceGeneration = persistence.submit(checkpoint(
                true, State.PREPARING_LIST.name(), trackedPosition,
                AutomationUncertainExposure.none(),
                "Repricing parked listing from " + listed.targetPrice() + " to " + repricingNewPrice));
        if (purchasePersistenceGeneration <= 0) {
            trackedPosition = null;
            pauseInternal("Repriced position could not be queued for persistence");
            return;
        }
        clearCoverageEvidence();
        preListMarketVerified = true;
        preListVerifiedScanStartedAt = now;
        preListValuationCalculatedAt = now;
        preparationStartedAtMillis = now;
        originalSelectedSlot = -1;
        sourceInventorySlot = -1;
        swapIssuedAtTick = -1;
        listNotBeforeMillis = now + 2500;
        prepareNotBeforeMillis = now + 2000;
        probePending = UNDERCUT_ENABLED;
        probeStarted = false;
        LOGGER.info("DoughBay automation: pulled back {} x{}; relisting at {} (was {})",
                itemId, listed.quantity(), repricingNewPrice, listed.targetPrice());
        transition(State.PREPARING_LIST, "Pulled back " + itemId + " x" + listed.quantity()
                + "; relisting at " + repricingNewPrice + " (was " + listed.targetPrice() + ")");
    }

    private static String summarizeWatch(List<Opportunity> watched) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Opportunity o : watched) {
            if (shown++ == 4) {
                out.append(", +").append(watched.size() - 4).append(" more");
                break;
            }
            if (out.length() > 0) out.append(", ");
            out.append(baseItemId(o.listing().itemId()).replace("minecraft:", ""))
                    .append(" x").append(o.listing().itemCount());
        }
        return out.toString();
    }

    private void beginWatchBuy(Minecraft client, long now) {
        if (closeStrayAuctionPage(client)) return;
        if (!safeInventoryContext(client) && !modScreenOpen(client)) {
            detail = "Waiting before buying: " + guiBlockerDescription(client);
            return;
        }
        if (modScreenOpen(client)) {
            client.gui.setScreen(null);
            detail = "Closing the GoNuts screen to begin watching";
            return;
        }
        if (!safeInventoryContext(client)) {
            detail = "Waiting before buying: " + guiBlockerDescription(client);
            return;
        }
        Inventory inventory = client.player.getInventory();
        switch (ensureReservedSlotFree(client)) {
            case CLEARING -> { detail = "Moving a stray item out of the reserved listing slot"; return; }
            case STUCK -> { pauseInternal(reservedSlotBlocked(inventory)); return; }
            case EMPTY -> { /* slot clear; carry on */ }
        }
        if (freeInventorySlots(client) < 1) {
            detail = "Inventory full; no room for a purchase to arrive";
            return;
        }
        long ceiling = 0;
        for (Opportunity o : watchCandidates) ceiling = Math.max(ceiling, ceilingFor(o));
        if (ceiling <= 0) {
            watchBuy = false;
            watchCandidates = List.of();
            clearPendingCandidate();
            detail = "No watched market clears a purchase price any more";
            return;
        }
        targetAbsentBeforeBuy = true;
        inventoryBeforeBuy = copyNonEquipmentInventory(inventory);
        buyTerminalSucceeded = false;
        purchasedInventorySlot = -1;
        purchaseStableSinceTick = -1;
        buyCeiling = ceiling;
        verifiedBuyPrice = 0;
        buyIntentCoverageBoundaryMillis = now;
        candidateCoverageBoundaryMillis = 0;
        clearCoverageEvidence();
        boundListingKey = "";
        awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
        uncertainExposure = uncertainBuy(pendingOpportunity, ceilingFor(pendingOpportunity), now);
        buyCommandStarted = false;
        buyIntentPersistenceGeneration = persistence.submit(checkpoint(
                true, "BUY_INTENT", null, uncertainExposure,
                "Recent-listings watch intent queued before any auction command"));
        if (buyIntentPersistenceGeneration <= 0) {
            uncertainExposure = AutomationUncertainExposure.none();
            pauseInternal("BUY intent could not be queued durably; no command was sent");
            return;
        }
        transition(State.BUYING,
                "Watch intent queued; waiting for durable acknowledgement before opening the auction house");
    }

    private void observeBuyTerminal(Minecraft client,
                                    MarketWatcher.Snapshot marketSnapshot,
                                    long now) {
        if (!buyCommandStarted) {
            if (!persistence.status().durable(buyIntentPersistenceGeneration)) {
                detail = "Waiting for durable BUY intent; no auction command has been sent";
                return;
            }
            String marketBlocker = liveSnapshotBlocker(marketSnapshot, now);
            if (marketBlocker != null) {
                pauseInternal("Durable BUY intent outlived valid market evidence: "
                        + marketBlocker);
                return;
            }
            if (watchBuy) {
                if (!safeInventoryContext(client) || !inventoryEquals(client, inventoryBeforeBuy)
                        || !client.player.getInventory()
                        .getItem(policy.reservedHotbarSlot()).isEmpty()) {
                    // Nothing was sent, so nothing is at risk: a pull-back that
                    // just returned a stack, or a screen the player opened, is
                    // a reason to look again, not to lock the session.
                    releaseUnclickedWatchExposure();
                    persistence.submit(checkpoint(true, State.SCANNING.name(), null,
                            AutomationUncertainExposure.none(),
                            "Watch intent released: inventory changed before any command"));
                    buyIntentPersistenceGeneration = 0;
                    buyCommandStarted = false;
                    transition(State.SCANNING,
                            "Inventory changed before the watch could start; scanning again");
                    return;
                }
                List<AutomatedExecutionDriver.WatchTarget> targets = new ArrayList<>();
                for (Opportunity o : watchCandidates) {
                    long ceiling = liveAwareCeiling(o.listing().itemId(), o.listing().itemCount(),
                            ceilingFor(o), now);
                    if (ceiling > 0) {
                        targets.add(new AutomatedExecutionDriver.WatchTarget(
                                o.listing().itemId(), o.listing().itemCount(), ceiling));
                    }
                }
                // Every so often the same look goes to the box market instead
                // of Recently Listed. Filled boxes were only ever found by
                // accident, on pages opened for something else, which is why
                // 53 of them sold against a thousand ordinary stacks a day
                // while returning triple the margin. They also sit unsold for
                // most of a day, so the underpriced ones pile up far past page
                // one and a deep pass finds more than a frequent shallow one.
                String sweepTerm = "";
                int sweepPages = 0;
                long sweepEvery = (long) (dev.doughbay.fabric.Tuning.get("boxes.sweep_min") * 60_000L);
                if (sweepEvery > 0 && dev.doughbay.fabric.Tuning.get("boxes.enabled") >= 0.5
                        && boxSweepQueue.isEmpty() && now - lastBoxSweepAt > sweepEvery) {
                    // A shulker and a bundle are both containers people list
                    // without looking inside, but they are different searches
                    // and one look can only be on one page. When the sweep
                    // comes round every market goes in the queue, and the
                    // looks that follow take them one after another rather
                    // than waiting another two hours for the second one.
                    lastBoxSweepAt = now;
                    for (String t : dev.doughbay.fabric.Tuning.text("boxes.sweep_term").split("[,;\n]")) {
                        if (!t.strip().isEmpty()) boxSweepQueue.addLast(t.strip());
                    }
                    if (boxSweepQueue.isEmpty()) boxSweepQueue.addLast("shulker");
                    LOGGER.info("DoughBay box sweep: {} market(s) to read - {}",
                            boxSweepQueue.size(), String.join(", ", boxSweepQueue));
                }
                if (!boxSweepQueue.isEmpty()) {
                    sweepTerm = boxSweepQueue.pollFirst();
                    sweepPages = (int) dev.doughbay.fabric.Tuning.get("boxes.sweep_pages");
                    LOGGER.info("DoughBay box sweep: reading {} page(s) of \"{}\" ({} market(s) left)",
                            sweepPages, sweepTerm, boxSweepQueue.size());
                }
                ExecutionResult result = driver.watchRecentListings(targets, 24, sweepTerm, sweepPages);
                if (!result.ok()) {
                    pauseInternal("Watch could not start after durable intent: " + result.detail());
                    return;
                }
                buyCommandStarted = true;
                detail = "Durable watch intent acknowledged; reading the recent listings";
                return;
            }
            MarketStats latestStats = exactMarketStats(marketSnapshot, pendingOpportunity);
            ContinuousOpportunityRevalidator.Result finalCheck = revalidator.revalidate(
                    pendingOpportunity, latestStats,
                    isMarketCandidate(pendingOpportunity) ? List.of()
                            : marketListings(marketSnapshot, pendingOpportunity.listing().itemId()),
                    policy, auctionFees, now,
                    Math.max(0, pendingOpportunity.stats().calculatedAt() - 1), false);
            if (finalCheck.opportunity().isEmpty()
                    || finalCheck.opportunity().orElseThrow().recommendedSellPrice()
                    != pendingOpportunity.recommendedSellPrice()) {
                pauseInternal("Fresh post-ACK market revalidation changed or rejected BUY terms");
                return;
            }
            if (!safeInventoryContext(client)
                    || !inventoryEquals(client, inventoryBeforeBuy)
                    || inventoryContainsItem(client, pendingOpportunity.listing().itemId())
                    || !client.player.getInventory()
                    .getItem(policy.reservedHotbarSlot()).isEmpty()) {
                pauseInternal("Inventory/context changed while BUY intent became durable; no command sent");
                return;
            }
            ExecutionResult result = driver.buyCheapestVisible(
                    pendingOpportunity.listing().itemId(), buyCeiling,
                    pendingOpportunity.listing().itemCount(), 8);
            if (!result.ok()) {
                pauseInternal("Buy could not start after durable intent: " + result.detail());
                return;
            }
            buyCommandStarted = true;
            detail = "Durable BUY intent acknowledged; verifying exact purchase";
            return;
        }
        if (!buyTerminalSucceeded) {
            AutomatedExecutionDriver.TerminalEvent event = nextTerminal(
                    AutomatedExecutionDriver.OperationIntent.BUY);
            if (event == null) {
                // The watch's exposure record names a placeholder until a row
                // is clicked; once it is, the record must name that item so a
                // recovery checks the right thing.
                String clicked = driver.watchClickedItemId();
                if (watchBuy && !clicked.isBlank() && uncertainExposure.active()
                        && !clicked.equals(uncertainExposure.itemId())) {
                    boolean matched = false;
                    for (Opportunity o : watchCandidates) {
                        if (o.listing().itemId().equals(clicked)) {
                            uncertainExposure = uncertainBuy(o, ceilingFor(o), now);
                            persistence.submit(checkpoint(true, "BUY_INTENT", null, uncertainExposure,
                                    "Watch clicked " + clicked + "; exposure updated"));
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        Opportunity box = boxOpportunity(driver.watchClickedItemKey(), now);
                        if (box != null) {
                            uncertainExposure = uncertainBuy(box, box.buyPrice(), now);
                            persistence.submit(checkpoint(true, "BUY_INTENT", null, uncertainExposure,
                                    "Watch clicked box " + clicked + "; exposure updated"));
                        }
                    }
                }
                return;
            }
            if (!matchesPendingBuy(event)) {
                pauseInternal("Unexpected or mismatched BUY terminal event");
                return;
            }
            if (event.outcome() != AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED) {
                if (nothingCharged(event)) {
                    abandonBuyAndRescan(event.detail());
                    return;
                }
                pauseInternal("Buy did not complete: " + event.detail());
                return;
            }
            if (!event.confirmationSubmitted()) {
                pauseInternal("BUY success lacked verified confirmation-click evidence");
                return;
            }
            if (event.price() <= 0 || event.price() > buyCeiling) {
                pauseInternal("Verified purchase price " + event.price()
                        + " is outside the " + buyCeiling + " ceiling; inspect before continuing");
                return;
            }
            buyTerminalSucceeded = true;
            verifiedBuyPrice = event.price();
            buyVerifiedTick = controllerTick;
            detail = "Buy event verified; proving one stable empty-to-target inventory change";
        }

        int changedSlot = exactSingleArrivalSlot(client);
        if (!targetAbsentBeforeBuy) {
            pauseInternal("Inventory changed ambiguously during BUY; no listing will be sent");
            return;
        }
        if (changedSlot < 0 && arrivedItemKey == null) {
            // The row looked plain but what arrived carries a potion, enchantment
            // or trim (the tipped arrows of 2026-09-02). The money is spent and
            // the receipt is real: remember the stack under its real key and
            // list it, rather than pause and forget it was ever bought.
            int withParts = singleNewStackWithParts(client, pendingOpportunity.listing().itemId(),
                    pendingOpportunity.listing().itemCount());
            if (withParts >= 0) {
                ItemStack stack = client.player.getInventory().getNonEquipmentItems().get(withParts);
                arrivedItemKey = pendingOpportunity.listing().itemId() + "#"
                        + dev.doughbay.fabric.ItemDescriptor.of(stack).hash();
                LOGGER.info("DoughBay automation: bought stack carries parts; tracking it as {} ({})",
                        arrivedItemKey, dev.doughbay.fabric.ItemDescriptor.of(stack).key());
                changedSlot = withParts;
            }
        }
        if (changedSlot < 0) {
            // The server's receipt arrives in chat a tick or more before the
            // inventory update does. Give the stack a moment to land.
            if (controllerTick - buyVerifiedTick < ARRIVAL_GRACE_TICKS) {
                detail = "Purchase confirmed; waiting for the stack to arrive in the inventory";
                return;
            }
            pauseInternal("Purchase confirmed but no single new stack of the item arrived; inspect the inventory");
            return;
        }
        if (purchasedInventorySlot != changedSlot) {
            purchasedInventorySlot = changedSlot;
            purchaseStableSinceTick = controllerTick;
            return;
        }
        if (controllerTick - purchaseStableSinceTick < 1) return;

        tradesStarted++;
        watchBuy = false;
        watchCandidates = List.of();
        // A market that just filled rests briefly so the next-ranked one gets
        // a turn; otherwise the top market is hunted again and again while
        // the rest of the list never comes up.
        restMarket(pendingOpportunity.listing().itemId(), now + MARKET_REST_MILLIS);
        long paid = verifiedBuyPrice > 0 ? verifiedBuyPrice : buyCeiling;
        spentSinceAudit += paid;
        huntNotes.put(pendingOpportunity.listing().itemId(),
                "bought at " + paid + " at " + java.time.LocalTime.now().withNano(0));
        committedSpend = Math.addExact(committedSpend, paid);
        trackedPosition = new Position(nextPositionId++, "REAL",
                arrivedItemKey != null ? arrivedItemKey : pendingOpportunity.listing().itemKey(),
                StackBucket.of(pendingOpportunity.listing().itemCount()),
                pendingOpportunity.listing().itemCount(),
                paid,
                pendingOpportunity.recommendedSellPrice(),
                now, 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
        positionValuation = pendingOpportunity;
        pendingOpportunity = null;
        arrivedItemKey = null;
        deliverAttempted = false;
        deliveringOrder = null;
        buyIntentCoverageBoundaryMillis = 0;
        purchasePersistenceGeneration = persistence.submit(checkpoint(
                true, State.PREPARING_LIST.name(), trackedPosition,
                AutomationUncertainExposure.none(),
                "Verified purchase and conservative session caps"));
        if (purchasePersistenceGeneration <= 0) {
            // The durable pre-command BUY intent remains the sole recovery
            // identity; do not present an unpersisted Position as a second one.
            trackedPosition = null;
            pauseInternal("Verified purchase could not be queued for persistence");
            return;
        }
        clearCoverageEvidence();
        preListMarketVerified = false;
        preListVerifiedScanStartedAt = 0;
        preparationStartedAtMillis = now;
        originalSelectedSlot = -1;
        sourceInventorySlot = -1;
        swapIssuedAtTick = -1;
        probePending = UNDERCUT_ENABLED;
        probeStarted = false;
        transition(State.PREPARING_LIST,
                "Purchase verified; waiting for durable position before any relist");
    }

    private void prepareAndList(Minecraft client, MarketWatcher.Snapshot marketSnapshot,
                                long now) {
        if (trackedPosition == null || trackedPosition.status() != PositionStatus.PURCHASED) {
            pauseInternal("No exact purchased position is available to list");
            return;
        }
        // Before anything else: never let a stack sit on the cursor. A peel that
        // could not set one back down would otherwise strand it here, and a
        // disconnect in that window drops it on the ground. Stow it first.
        if (stowStrayCursor(client)) return;
        if (purchasePersistenceGeneration > 0) {
            if (!persistence.status().durable(purchasePersistenceGeneration)) {
                detail = "Waiting for the verified PURCHASED position to become durable";
                return;
            }
            purchasePersistenceGeneration = 0;
            buyIntentPersistenceGeneration = 0;
            uncertainExposure = AutomationUncertainExposure.none();
        }
        if (now - preparationStartedAtMillis > policy.preparationTimeoutMillis()) {
            pauseInternal("Post-purchase revaluation or exact-stack preparation timed out");
            return;
        }
        if (now < prepareNotBeforeMillis) {
            // A swap sent while the server is reopening its page is dropped
            // server-side, and the stack silently stays where it was.
            detail = "Letting the auction house settle before moving the stack";
            return;
        }

        // Piece-out: before valuing or reserving, if this is a market that pays
        // more one at a time and the flag is on, peel a single off the stack and
        // re-size the position to it. The leftover falls back to the inventory
        // and re-lists on the next pass. This runs before the valuation below so
        // the single is priced as a single, not as a stack. See advanceSinglePeel.
        if (advanceSinglePeel(client, now)) return;

        if (!preListMarketVerified) {
            String snapshotBlocker = liveSnapshotBlocker(marketSnapshot, now);
            if (snapshotBlocker != null) {
                detail = "Waiting for post-purchase completed-sale data: " + snapshotBlocker;
                return;
            }
            // No exact-book gate before listing. The API's active rows lag by
            // minutes and cannot show our own listing; the resale target comes
            // from completed sales, and the sell dialog states the price.
            // The resale target was set when the item was bought. Fresh
            // statistics refine it when they happen to exist; the listing
            // does not wait a sweep for them, since an unlisted item earns
            // nothing while it waits.
            MarketStats latestStats = exactMarketStats(marketSnapshot, positionValuation);
            if (latestStats == null || latestStats.calculatedAt() <= trackedPosition.purchasedAt()) {
                latestStats = positionValuation.stats();
            }
            // An item already bought is listed regardless: parking it earns
            // nothing, so a thin margin lists at the median or cost plus the
            // minimum profit, whichever is higher, rather than pausing.
            ContinuousOpportunityRevalidator.Result revised = revalidator.revalidate(
                    positionValuation, latestStats, List.of(),
                    policy, auctionFees, now, trackedPosition.purchasedAt(), false);
            Opportunity latestValuation;
            if (revised.opportunity().isPresent()) {
                latestValuation = revised.opportunity().orElseThrow();
            } else {
                long floorTarget;
                long target;
                if (dev.doughbay.fabric.Tuning.liquidating(baseItemId(trackedPosition.itemKey()))) {
                    // Clearing out: cost stops setting the floor. Price at the
                    // market undercut, floored only by the half-of-cost safety,
                    // and never move the ask up - liquidation only ever comes
                    // down toward what will actually sell.
                    floorTarget = repriceFloor(trackedPosition, now);
                    long mkt = (long) Math.floor(latestStats.weightedMedian()
                            * (1.0 - UNDERCUT_PERCENT / 100.0));
                    target = Math.max(floorTarget, Math.min(trackedPosition.targetPrice(),
                            Math.max(floorTarget, mkt)));
                } else {
                    floorTarget = Math.max(
                            (long) Math.floor(latestStats.weightedMedian()),
                            trackedPosition.purchasePrice()
                                    + Math.max(policy.minimumProfit(), riskConfig.minimumProfit()));
                    target = Math.max(trackedPosition.targetPrice(), floorTarget);
                }
                LOGGER.info("DoughBay automation: margin thinned after purchase ({}); listing at {} anyway",
                        revised.detail(), target);
                latestValuation = new Opportunity(positionValuation.listing(), latestStats,
                        positionValuation.buyPrice(), target,
                        auctionFees.netSale(target) - trackedPosition.purchasePrice(),
                        (auctionFees.netSale(target) - trackedPosition.purchasePrice())
                                / Math.max(1, trackedPosition.purchasePrice()) * 100.0,
                        positionValuation.estimatedHoldHours(), positionValuation.saleProbability(),
                        latestStats.confidence(), positionValuation.score(),
                        List.of("Listed at median or cost-plus-minimum after margin thinned"));
            }
            positionValuation = latestValuation;
            if (latestValuation.recommendedSellPrice() != trackedPosition.targetPrice()) {
                trackedPosition = withTargetPrice(
                        trackedPosition, latestValuation.recommendedSellPrice());
            }
            // Piece-out pricing: price a pieced single from StackProfile's
            // robust, outlier-filtered single median - clamping BOTH ways. Up
            // from the stack's per-unit median (the premium is the whole point),
            // and down from a noisy valuation or a troll live-ask: one golden
            // apple listed at $1.5M because somebody trolled the market gets
            // pulled back to what singles actually sell for. We only piece out
            // markets that have this robust shape, so it is the number to trust.
            // Never below cost plus the minimum profit; the live-ask probe below
            // still undercuts from here. Off unless list.singles is set.
            long singleTarget = singleMarketTarget(trackedPosition);
            if (singleTarget > 0 && singleTarget != trackedPosition.targetPrice()) {
                LOGGER.info("DoughBay singles: pricing one {} at {} (robust single median; valuation/probe said {})",
                        baseItemId(trackedPosition.itemKey()), singleTarget, trackedPosition.targetPrice());
                trackedPosition = withTargetPrice(trackedPosition, singleTarget);
            }
            preListMarketVerified = true;
            preListVerifiedScanStartedAt = now;
            preListValuationCalculatedAt = latestStats.calculatedAt();
            reconciliationStatus = "Resale target " + trackedPosition.targetPrice()
                    + " set from completed sales; listing";
        }

        // The median is the ceiling, not the price: buyers take the cheapest
        // row first, so one search reads the cheapest live ask from other
        // sellers and the listing goes just under it, never below cost plus
        // the minimum profit.
        if (probePending) {
            if (!probeStarted) {
                awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
                ExecutionResult probe = driver.probeCheapest(
                        baseItemId(trackedPosition.itemKey()), trackedPosition.quantity(),
                        localName(client));
                if (!probe.ok()) {
                    probePending = false;
                    LOGGER.info("DoughBay automation: live price check skipped: {}", probe.detail());
                } else {
                    probeStarted = true;
                    detail = "Checking the cheapest live ask before listing";
                    return;
                }
            } else {
                AutomatedExecutionDriver.TerminalEvent event = nextTerminal(
                        AutomatedExecutionDriver.OperationIntent.PROBE);
                if (event == null) {
                    detail = "Checking the cheapest live ask before listing";
                    return;
                }
                probeStarted = false;
                probePending = false;
                if (event.intent() == AutomatedExecutionDriver.OperationIntent.PROBE
                        && event.outcome() == AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED
                        && event.price() > 0) {
                    liveAsks.put(baseItemId(trackedPosition.itemKey()) + "|" + trackedPosition.quantity(),
                            new long[] {event.price(), now});
                    long floor = repriceFloor(trackedPosition, now);
                    long undercut = event.price()
                            - Math.max(1, Math.round(event.price() * UNDERCUT_PERCENT / 100.0));
                    // Ghost rows pull the visible median well under what buyers
                    // actually pay (furnaces: median 1,500 on the page, sold at
                    // 5,000 twice). So the page may pull a listing down by at
                    // most 30% per step; the time-based reprice does the rest.
                    long deepest = (long) Math.floor(trackedPosition.targetPrice() * 0.7);
                    long target = Math.max(floor, Math.min(trackedPosition.targetPrice(),
                            Math.max(undercut, deepest)));
                    if (target != trackedPosition.targetPrice()) {
                        LOGGER.info("DoughBay automation: median live ask for {} x{} is {}; listing at {} instead of {} (floor {})",
                                baseItemId(trackedPosition.itemKey()), trackedPosition.quantity(),
                                event.price(), target, trackedPosition.targetPrice(), floor);
                        trackedPosition = withTargetPrice(trackedPosition, target);
                        reconciliationStatus = "Undercutting the median live ask " + event.price()
                                + ": listing at " + target;
                    }
                }
                // The probe's page closes a moment after; let it.
                prepareNotBeforeMillis = Math.max(prepareNotBeforeMillis, now + 1200);
                return;
            }
        }

        if (now - preListVerifiedScanStartedAt > policy.maximumSnapshotAgeMillis()) {
            preListMarketVerified = false;
            preListVerifiedScanStartedAt = 0;
            clearCoverageEvidence();
            detail = "Pre-list proof aged out; requesting two new exact scans";
            return;
        }
        if (!safeInventoryContext(client)) {
            // The server reopens the auction page a tick after the purchase
            // dialog closes. Nobody else will close it in an unattended
            // session, and the stack cannot be moved while it is open.
            if (client != null && client.player != null) {
                Screen open = client.gui.screen();
                if (open instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                    client.player.closeContainer();
                    detail = "Closing the auction page before preparing the stack";
                    return;
                }
                if (open instanceof DoughBayScreen) {
                    client.gui.setScreen(null);
                    detail = "Closing the GoNuts screen before preparing the stack";
                    return;
                }
                if (open == null && client.player.containerMenu != client.player.inventoryMenu) {
                    // No page is showing but the server still has the auction
                    // container open (the player's own screen was up when the
                    // pull-back finished). Close it ourselves; nobody else can.
                    client.player.closeContainer();
                    detail = "Closing the auction container the server left open";
                    return;
                }
            }
            detail = "Waiting before preparing the stack: " + guiBlockerDescription(client);
            return;
        }

        Inventory inventory = client.player.getInventory();
        int reserved = policy.reservedHotbarSlot();
        if (swapIssuedAtTick < 0) {
            preparedInventoryClose.reset();
            int source = uniqueExactPlainSlot(client,
                    trackedPosition.itemKey(), trackedPosition.quantity());
            if (source < 0 && trackedPosition.itemKey().indexOf('#') < 0) {
                int slot = largestStackSlot(client, trackedPosition.itemKey());
                if (slot >= 0) {
                    int merged = inventory.getNonEquipmentItems().get(slot).getCount();
                    LOGGER.info("DoughBay automation: #{} ({} x{}) sits in a stack of {}; listing the stack",
                            trackedPosition.positionId(), trackedPosition.itemKey(), trackedPosition.quantity(), merged);
                    trackedPosition = supersedeQuantity(trackedPosition, merged, now);
                    purchasedInventorySlot = slot;
                    source = slot;
                }
            }
            if (source < 0) {
                pauseInternal("Purchased stack is missing or has unexpected components");
                return;
            }
            // With an identical copy elsewhere, the verified arrival slot is the one to take.
            List<ItemStack> held = inventory.getNonEquipmentItems();
            if (purchasedInventorySlot >= 0 && purchasedInventorySlot < held.size()
                    && exactPlainStack(held.get(purchasedInventorySlot), trackedPosition.itemKey(), trackedPosition.quantity())) {
                source = purchasedInventorySlot;
            }
            if (source != purchasedInventorySlot) {
                pauseInternal("Purchased stack moved from its verified arrival slot");
                return;
            }
            originalSelectedSlot = inventory.getSelectedSlot();
            sourceInventorySlot = source;
            displacedFromReserved = ItemStack.EMPTY;
            if (source != reserved) {
                if (!inventory.getItem(reserved).isEmpty()) {
                    // A full inventory leaves nothing free: the swap is a swap,
                    // so whatever sits in the reserved slot takes the stack's
                    // old place and is verified there before anything is sent.
                    displacedFromReserved = inventory.getItem(reserved).copy();
                    LOGGER.info("DoughBay automation: reserved slot holds {} x{}; it moves to slot {} while the stack is listed",
                            itemId(displacedFromReserved), displacedFromReserved.getCount(), source);
                }
                int menuSlot = inventoryMenuSlot(client, source);
                if (menuSlot < 0) {
                    pauseInternal("Could not bind the purchased stack to an inventory-menu slot");
                    return;
                }
                try {
                    client.gameMode.handleContainerInput(
                            client.player.inventoryMenu.containerId,
                            menuSlot,
                            reserved,
                            ContainerInput.SWAP,
                            client.player);
                } catch (RuntimeException e) {
                    pauseInternal("Exact-stack swap failed; no listing command was sent");
                    return;
                }
            }
            if (!setSelectedSlot(client, reserved)) {
                pauseInternal("Could not synchronize the reserved selected slot");
                return;
            }
            swapIssuedAtTick = controllerTick;
            detail = "Verifying the reserved exact stack before /ah sell";
            return;
        }

        if (controllerTick - swapIssuedAtTick < 2) return;
        if (!ourStack(inventory.getItem(reserved),
                trackedPosition.itemKey(), trackedPosition.quantity())
                || inventory.getSelectedSlot() != reserved) {
            if (controllerTick - swapIssuedAtTick >= SWAP_VERIFY_TICKS) {
                // The server drops a swap now and then, usually because it
                // reopened the auction page underneath it. The stack is still
                // ours, so settle and try again before treating it as a fault.
                if (prepareRetryPositionId != trackedPosition.positionId()) {
                    prepareRetryPositionId = trackedPosition.positionId();
                    prepareRetries = 0;
                }
                // Keyed by item, not by position: each retry writes a new
                // position record, so a per-position count is reset every cycle
                // and never reaches its cap. That is how one trident wrote 851
                // rows in half an hour while printing "1 of 3" throughout.
                String stuckKey = trackedPosition == null ? "" : trackedPosition.itemKey();
                if (!stuckKey.isEmpty() && unpreparableItems.merge(stuckKey, 1, Integer::sum) > 6) {
                    // Setting it aside in memory was not enough: the position is
                    // read back from the ledger on the next pass and prepared
                    // again, so the same phantom trident was set aside twelve
                    // times in six minutes and wrote a row on every cycle. If
                    // the stack is not in the inventory at all then it is gone -
                    // sold by hand, dropped, or lost - and the position is
                    // closed for good rather than prepared for ever.
                    // Whether or not it is still in the inventory, the ledger
                    // must stop tracking it: keeping the position only means it
                    // is picked up and prepared again on the next pass, which is
                    // how the same trident was set aside twelve times in six
                    // minutes and wrote a row every cycle. The item itself stays
                    // where it is for a person to list; the record does not.
                    boolean here = uniqueExactPlainSlot(client, stuckKey, trackedPosition.quantity()) >= 0
                            || inventoryContainsItem(client, baseItemId(stuckKey));
                    setAsideItems.add(stuckKey);
                    // A prepare failure is often transient; record it so the
                    // cooldown retry can release it rather than strand it for good.
                    preparationSetAsideAt.put(stuckKey, System.currentTimeMillis());
                    preparationSetAsideRounds.merge(stuckKey, 1, Integer::sum);
                    closePhantom(trackedPosition, here
                            ? "it will not go into the reserved slot; it stays in the inventory to be listed by hand"
                            : "the stack is not in the inventory; it is gone");
                    trackedPosition = null;
                    positionValuation = null;
                    prepareRetries = 0;
                    transition(State.SCANNING, "Set aside a stack that would not prepare; scanning");
                    return;
                }
                if (prepareRetries < PREPARE_MAX_RETRIES) {
                    prepareRetries++;
                    int where = uniqueExactPlainSlot(client,
                            trackedPosition.itemKey(), trackedPosition.quantity());
                    if (where >= 0) purchasedInventorySlot = where;
                    swapIssuedAtTick = -1;
                    prepareNotBeforeMillis = System.currentTimeMillis() + 1500;
                    detail = "Reserved stack did not verify; retrying the preparation ("
                            + prepareRetries + " of " + PREPARE_MAX_RETRIES + ")";
                    LOGGER.info("DoughBay automation: {}", detail);
                    return;
                }
                // The retry counter resets when the pause auto-resumes, so on
                // its own it never reaches the cap: one trident that would not
                // go into the reserved slot paused the session forty-one times
                // in an hour and wrote a position record on every attempt.
                // Counting per position instead, and setting the stack aside
                // once it is clearly not going to prepare, keeps the rest of
                // the book trading.
                long stuckId = trackedPosition == null ? 0 : trackedPosition.positionId();
                int failures = unpreparable.merge(stuckId, 1, Integer::sum);
                if (failures >= 3) {
                    LOGGER.warn("DoughBay automation: {} x{} will not go into the reserved slot after {} tries; "
                                    + "setting it aside and carrying on. It stays in the inventory to be listed by hand",
                            trackedPosition == null ? "the stack" : trackedPosition.itemKey(),
                            trackedPosition == null ? 0 : trackedPosition.quantity(), failures);
                    setAside.add(stuckId);
                    trackedPosition = null;
                    positionValuation = null;
                    prepareRetries = 0;
                    transition(State.SCANNING, "Set aside a stack that would not prepare; scanning");
                    return;
                }
                pauseInternal("Reserved stack or selected slot did not verify after preparation");
            }
            return;
        }
        if (sourceInventorySlot != reserved) {
            ItemStack atSource = inventory.getItem(sourceInventorySlot);
            boolean asExpected = displacedFromReserved.isEmpty()
                    ? atSource.isEmpty()
                    : !atSource.isEmpty() && itemId(atSource).equals(itemId(displacedFromReserved))
                            && atSource.getCount() == displacedFromReserved.getCount();
            if (!asExpected) {
                pauseInternal("Source slot changed during exact-stack preparation");
                return;
            }
        }

        // Inventory clicks update the client immediately, even without an open
        // InventoryScreen. The server still needs the normal container-zero
        // close before accepting auction commands. Do this with a clear cursor,
        // then re-run the stack/source/selection checks above after it settles.
        try {
            if (!preparedInventoryClose.ready(now, () -> {
                client.player.closeContainer();
                LOGGER.info("DoughBay automation: closed prepared player inventory before /ah sell");
            })) {
                detail = "Closing prepared inventory before /ah sell; waiting for it to settle";
                return;
            }
        } catch (RuntimeException e) {
            pauseInternal("Could not close prepared inventory; no listing command was sent");
            return;
        }

        awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
        inventoryBeforeList = copyNonEquipmentInventory(inventory);
        listTerminalSucceeded = false;
        listStableSinceTick = -1;
        listingInventoryVerified = false;
        listInventoryProofAtMillis = 0;
        listingReconciliationStartedAt = now;
        reconciliationStatus = "Pre-list snapshot proved zero exact own listings";
        uncertainExposure = uncertainList(trackedPosition, now);
        listIntentCoverageBoundaryMillis =
                marketSnapshot.exactListingCoverage().completedAt();
        listCommandStarted = false;
        listRetries = 0;
        listIntentWaitSinceMillis = 0;
        listIntentPersistenceGeneration = persistence.submit(checkpoint(
                true, "LIST_INTENT", trackedPosition, uncertainExposure,
                "Exact LIST intent queued before /ah sell"));
        if (listIntentPersistenceGeneration <= 0) {
            pauseInternal("LIST intent could not be queued durably; no command was sent");
            return;
        }
        transition(State.LISTING,
                "LIST intent queued; waiting for durable acknowledgement before /ah sell");
    }

    private void observeListTerminal(Minecraft client, MarketWatcher.Snapshot marketSnapshot,
                                     long now) {
        if (!listCommandStarted) {
            if (!persistence.status().durable(listIntentPersistenceGeneration)) {
                // Guard against an intent that never (re-)acknowledges. On a
                // retry the command flag is cleared and this gate is re-checked;
                // if the generation regresses to not-durable it would otherwise
                // sit here every tick forever. Bound the wait and, past it,
                // re-queue the stack and scan on rather than freeze in LISTING.
                if (listIntentWaitSinceMillis == 0) listIntentWaitSinceMillis = now;
                if (now - listIntentWaitSinceMillis > LIST_INTENT_WAIT_TIMEOUT_MILLIS) {
                    abandonListingForNow(client, now,
                            "LIST intent did not become durable within "
                            + (LIST_INTENT_WAIT_TIMEOUT_MILLIS / 1000) + "s");
                    return;
                }
                detail = "Waiting for durable LIST intent; no listing command has been sent";
                return;
            }
            listIntentWaitSinceMillis = 0;
            // The item is already bought; it is listed at the target it was
            // bought against regardless of how old the statistics behind that
            // target are. Pausing here only parked stock on a stale timestamp.
            if (preListVerifiedScanStartedAt <= 0) {
                pauseInternal("LIST intent has no pre-list verification; refusing to sell blindly");
                return;
            }
            String marketBlocker = liveSnapshotBlocker(marketSnapshot, now);
            if (marketBlocker != null && !staleFeedListingLogged) {
                // The stock is bought; a stale feed changes nothing about
                // listing it at the target it was bought against.
                staleFeedListingLogged = true;
                LOGGER.info("DoughBay automation: listing despite a stale market feed ({})", marketBlocker);
            }
            if (now < listNotBeforeMillis) {
                detail = "Letting the auction house settle before /ah sell";
                return;
            }
            // The server reopens the auction page on its own a moment after
            // an item is collected; a sell command sent over it is refused.
            if (client != null && client.player != null && client.gui.screen()
                    instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                client.player.closeContainer();
                listNotBeforeMillis = now + 600;
                detail = "Closing the auction page before /ah sell";
                return;
            }
            // No exact-book gate before the sell command. The target was set
            // at purchase from completed sales, the stack is verified in the
            // reserved slot below, and the sell dialog states the price that
            // will be listed; an API book, minutes stale, adds nothing here.
            String listGate = listGateBlocker(client);
            if (listGate != null) {
                pauseInternal("Inventory/context changed while LIST intent became durable; no command sent ("
                        + listGate + ")");
                return;
            }
            // An open order that pays at least the listing target is a sale
            // now rather than a listing that waits; one attempt per position,
            // and a failed delivery falls back to the listing.
            dev.doughbay.fabric.OrderBook.Order order = deliverAttempted ? null : deliverableOrder(trackedPosition);
            if (order != null) {
                deliverAttempted = true;
                ExecutionResult delivery = driver.deliver(trackedPosition, order.unitPrice());
                if (delivery.ok()) {
                    deliveringOrder = order;
                    listCommandStarted = true;
                    LOGGER.info("DoughBay automation: delivering #{} ({} x{}) to an order paying {} each ({} total, listing target {})",
                            trackedPosition.positionId(), trackedPosition.itemKey(), trackedPosition.quantity(),
                            order.unitPrice(), order.unitPrice() * trackedPosition.quantity(), trackedPosition.targetPrice());
                    detail = "Delivering to an order paying " + order.unitPrice() + " each";
                    return;
                }
                LOGGER.info("DoughBay automation: delivery could not start ({}); listing instead", delivery.detail());
            }
            // Never offer a stack to a book with no room in it. Every listing
            // in the mod comes through this one call, so this is the only
            // place the rule has to hold - and it is the difference between
            // waiting for a slot and asking ninety times whether one appeared.
            int cap = (int) Math.round(dev.doughbay.fabric.Tuning.get("slots.max"));
            int listedNow = serverListedSlots();
            if (bookFullNow(now) || (listedNow >= 0 && listedNow >= cap)) {
                holdStackUntilThereIsRoom(client, now, listedNow >= 0 && listedNow >= cap
                        ? "the book is full (" + listedNow + " of " + cap + " listed)"
                        : "the server has said the book is full and nothing has sold since");
                return;
            }
            ExecutionResult result = driver.list(
                    trackedPosition, trackedPosition.targetPrice());
            if (!result.ok()) {
                pauseInternal("Listing could not start after durable intent: " + result.detail());
                return;
            }
            listCommandStarted = true;
            detail = "Durable LIST intent acknowledged; verifying exact listing";
            return;
        }
        if (!listTerminalSucceeded && deliveringOrder != null) {
            AutomatedExecutionDriver.TerminalEvent event = nextTerminal(
                    AutomatedExecutionDriver.OperationIntent.DELIVER);
            if (event == null) return;
            if (event.intent() == AutomatedExecutionDriver.OperationIntent.DELIVER
                    && event.outcome() == AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED && event.price() > 0) {
                settleDelivered(client, now, event.price());
                return;
            }
            // The order was gone or the page misbehaved; the stack is still in
            // the reserved slot, so it is listed the ordinary way.
            LOGGER.info("DoughBay automation: delivery did not complete ({}); listing instead", event.detail());
            if (event.detail() != null && event.detail().contains("No order pays")) {
                dev.doughbay.fabric.OrderBook book = dev.doughbay.fabric.DoughBayClient.orderBook();
                if (book != null) book.drop(deliveringOrder);
            }
            deliveringOrder = null;
            listCommandStarted = false;
            listNotBeforeMillis = now + 1500;
            if (client != null && client.player != null && client.gui.screen()
                    instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                client.player.closeContainer();
            }
            detail = "Delivery did not complete (" + event.detail() + "); listing instead";
            return;
        }
        if (!listTerminalSucceeded) {
            AutomatedExecutionDriver.TerminalEvent event = nextTerminal(
                    AutomatedExecutionDriver.OperationIntent.LIST);
            if (event == null) return;
            if (!matchesTrackedList(event)) {
                restoreSelectedSlot(client);
                pauseInternal("Unexpected or mismatched LIST terminal event");
                return;
            }
            if (event.outcome() != AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED) {
                // Nothing was confirmed, so nothing was listed: the stack is
                // still in the reserved slot. A refused or unanswered sell
                // command is retried after the page the server reopened is
                // closed, rather than parking the item on a pause.
                // A full auction house is not a fault. It is the ordinary
                // state of a book with ninety slots and a desk that fills
                // them, and stopping the session for it means the one thing
                // that frees a slot - repricing, so something sells - stops
                // too. The stack is still ours and still unlisted, so it goes
                // back on the queue and the book gets recounted; the full-house
                // gate then holds the queue until there is somewhere to put it.
                String refusal = event.detail() == null ? ""
                        : event.detail().toLowerCase(java.util.Locale.ROOT);
                if (refusal.contains("too many listed items")) {
                    holdStackUntilThereIsRoom(client, now, "the auction house refused the listing: the book is full");
                    return;
                }
                if (!event.confirmationSubmitted() && listRetries < LIST_MAX_RETRIES) {
                    listRetries++;
                    listCommandStarted = false;
                    // Progressive backoff: a sell eaten by the server's chat-
                    // command rate limit needs the channel to go quiet, or the
                    // retry lands in the same cooldown and is lost the same way.
                    listNotBeforeMillis = now + 2500L + 1500L * (listRetries - 1);
                    if (client != null && client.player != null && client.gui.screen()
                            instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                        client.player.closeContainer();
                    }
                    LOGGER.info("DoughBay automation: sell command did not list ({}); retry {} of {}",
                            event.detail(), listRetries, LIST_MAX_RETRIES);
                    detail = "Sell command did not list (" + event.detail() + "); retrying";
                    return;
                }
                // Retries exhausted with nothing listed. The stack is still ours
                // and unlisted; re-queue it and scan on rather than parking the
                // session on a pause a fresh (non-recovered) run will not
                // auto-resume.
                abandonListingForNow(client, now,
                        "listing did not complete after " + LIST_MAX_RETRIES + " tries ("
                        + event.detail() + ")");
                return;
            }
            if (!event.confirmationSubmitted()) {
                restoreSelectedSlot(client);
                pauseInternal("LIST success lacked verified confirmation-click evidence");
                return;
            }
            listTerminalSucceeded = true;
            detail = "Listing event verified; proving one stable reserved-stack removal";
        }
        if (!listingInventoryVerified) {
            if (!exactSingleListingRemoval(client)) {
                restoreSelectedSlot(client);
                pauseInternal("Inventory changed ambiguously during LIST; position remains unresolved");
                return;
            }
            if (client.player.getInventory().getSelectedSlot() != policy.reservedHotbarSlot()) {
                restoreSelectedSlot(client);
                pauseInternal("Selected hotbar slot changed during LIST verification");
                return;
            }
            if (listStableSinceTick < 0) {
                listStableSinceTick = controllerTick;
                return;
            }
            if (controllerTick - listStableSinceTick < 1) return;
            listingInventoryVerified = true;
            listInventoryProofAtMillis = now;
            listingReconciliationStartedAt = now;
            restoreSelectedSlot(client);
            // The sell dialog was confirmed and the stack left the reserved
            // slot: the listing exists. The API's book will not show it for
            // minutes, and searching it every tick for our own row hammered
            // the API. Bind the position to itself and move on; the server's
            // sale notice closes it.
            bindListedPosition(now, "own:" + trackedPosition.positionId(), now);
            return;
        }

        if (now - listingReconciliationStartedAt > policy.maximumSnapshotAgeMillis()) {
            pauseInternal("Timed out reconciling the new active listing");
            return;
        }
        String blocker = liveSnapshotBlocker(marketSnapshot, now);
        if (blocker != null) {
            detail = "Waiting to reconcile LIST against a healthy feed: " + blocker;
            return;
        }
        CoverageRequest request = coverageRequest();
        String coverageBlocker = exactCoverageBlocker(
                marketSnapshot.exactListingCoverage(), request, now);
        if (coverageBlocker != null) {
            detail = "Waiting for post-LIST exact coverage: " + coverageBlocker;
            return;
        }
        ActiveBookEvidence.Analysis book = activeBookEvidence.analyze(
                request.itemId(), marketSnapshot.exactListingCoverage().listings());
        if (!book.valid()) {
            pauseInternal("Post-LIST exact coverage is internally ambiguous: " + book.detail());
            return;
        }
        ActiveListingReconciler.Reconciliation afterList = reconcileOwnTarget(
                client, book.listings(), trackedPosition);
        if (afterList.ambiguous()) {
            pauseInternal("New own listing is ambiguous: " + afterList.detail());
            return;
        }
        if (afterList.uniqueOwn().isPresent()) {
            Listing bound = afterList.uniqueOwn().orElseThrow();
            if (bound.observedAt() < marketSnapshot.exactListingCoverage().scanStartedAt()
                    || bound.observedAt() > marketSnapshot.exactListingCoverage().completedAt()) {
                pauseInternal("The apparent own listing falls outside the post-LIST scan interval");
                return;
            }
            bindListedPosition(now, bound.listingKey(),
                    marketSnapshot.exactListingCoverage().scanStartedAt());
            return;
        }
        if (bufferedSaleNotice != null) {
            if (!acceptConsecutiveStableCoverage(postListCoveragePhase(),
                    marketSnapshot.exactListingCoverage(), book, ownRows(client))) {
                detail = "First post-LIST zero captured; requesting a second stable scan";
                return;
            }
            // A very fast sale can disappear between API polls. The trusted
            // exact notice plus LIST proof and two later stable full-book zero
            // scans is the only allowed no-listing-key completion path.
            bindListedPosition(now, "<fast-sale-unobserved>",
                    marketSnapshot.exactListingCoverage().scanStartedAt());
            finalizeReconciledSale(now);
            return;
        }
        rollCoverageBaseline(postListCoveragePhase(),
                marketSnapshot.exactListingCoverage(), book);
        reconciliationStatus =
                "Complete post-list scan has zero own matches; scanning again for listing or sale";
        detail = reconciliationStatus;
    }

    private void bindListedPosition(long now, String listingKey,
                                    long bindingScanStartedAt) {
        trackedPosition = new Position(
                trackedPosition.positionId(), trackedPosition.mode(), trackedPosition.itemKey(),
                trackedPosition.bucket(), trackedPosition.quantity(),
                trackedPosition.purchasePrice(), trackedPosition.targetPrice(),
                trackedPosition.purchasedAt(), now, 0, 0, Double.NaN,
                PositionStatus.LISTED);
        boundListingKey = listingKey;
        listedPersistenceGeneration = persistence.submit(checkpoint(
                true, State.MONITORING.name(), trackedPosition,
                AutomationUncertainExposure.none(),
                "Verified LIST bound to exact active listing"));
        if (listedPersistenceGeneration <= 0) {
            pauseInternal("Verified LISTED position could not be queued for persistence");
            return;
        }
        clearPreparationState();
        if (bufferedSaleNotice == null) {
            reconciliationStatus = "Bound one exact own active listing: " + listingKey;
            transition(State.MONITORING,
                    "Listing bound; waiting for durable LISTED checkpoint");
        } else {
            saleNoticeBaselineScanStartedAt = Math.max(
                    saleNoticeBaselineScanStartedAt, bindingScanStartedAt);
            reconciliationStatus =
                    "Listing proof complete; buffered sale awaits a later zero-match snapshot";
            transition(State.MONITORING,
                    "Buffered sale retained; waiting for durable LISTED checkpoint");
        }
    }

    private void reconcileSaleDisappearance(Minecraft client,
                                            MarketWatcher.Snapshot marketSnapshot,
                                            long now) {
        if (bufferedSaleNotice == null || trackedPosition == null
                || trackedPosition.status() != PositionStatus.LISTED) return;
        if (saleNoticeObservedAtMillis <= 0
                || now - saleNoticeObservedAtMillis > policy.maximumSnapshotAgeMillis()) {
            pauseInternal("Timed out reconciling the sale notice with active listings");
            return;
        }
        String blocker = liveSnapshotBlocker(marketSnapshot, now);
        if (blocker != null) {
            detail = "Waiting to reconcile sale against a healthy feed: " + blocker;
            return;
        }
        CoverageRequest request = coverageRequest();
        String coverageBlocker = exactCoverageBlocker(
                marketSnapshot.exactListingCoverage(), request, now);
        if (coverageBlocker != null) {
            detail = "Waiting for post-sale exact coverage: " + coverageBlocker;
            return;
        }
        ActiveBookEvidence.Analysis book = activeBookEvidence.analyze(
                request.itemId(), marketSnapshot.exactListingCoverage().listings());
        if (!book.valid()) {
            pauseInternal("Post-sale exact coverage is internally ambiguous: " + book.detail());
            return;
        }
        ActiveListingReconciler.Reconciliation afterSale = reconcileOwnTarget(
                client, book.listings(), trackedPosition);
        if (afterSale.ambiguous()) {
            pauseInternal("Post-sale own-listing state is ambiguous: " + afterSale.detail());
            return;
        }
        if (afterSale.uniqueOwn().isPresent()) {
            String observedKey = afterSale.uniqueOwn().orElseThrow().listingKey();
            if ("<fast-sale-unobserved>".equals(boundListingKey)) {
                boundListingKey = observedKey;
                saleNoticeBaselineScanStartedAt =
                        marketSnapshot.exactListingCoverage().completedAt();
                clearCoverageEvidence();
                reconciliationStatus = "Late-bound exact listing is still active; waiting for disappearance";
                detail = reconciliationStatus;
                return;
            }
            if (!observedKey.equals(boundListingKey)) {
                pauseInternal("A different exact own listing appeared after the sale notice");
                return;
            }
            reconciliationStatus = "Bound listing remains active after sale notice; not closing position";
            detail = reconciliationStatus;
            rollCoverageBaseline(postSaleCoveragePhase(),
                    marketSnapshot.exactListingCoverage(), book);
            return;
        }
        if (!acceptConsecutiveStableCoverage(postSaleCoveragePhase(),
                marketSnapshot.exactListingCoverage(), book, ownRows(client))) {
            detail = "First clean post-sale zero captured; requesting a second stable scan";
            return;
        }
        finalizeReconciledSale(now);
    }

    private void finalizeReconciledSale(long now) {
        DonutSaleMessageParser.SaleNotice notice = bufferedSaleNotice;
        double profit = auctionFees.netSale(notice.price()) - trackedPosition.purchasePrice();
        // A sale that lands before the listing is parked still returns its
        // purchase price to the budget; without this the outstanding-spend
        // figure drifted up by every instant sale.
        committedSpend = Math.max(0, committedSpend - trackedPosition.purchasePrice());
        Position sold = trackedPosition.closed(
                PositionStatus.SOLD, now, notice.price(), profit);
        recordSaleTime(System.currentTimeMillis(), notice.price());
        notifySaleObserver(sold, notice.buyer());
        boolean closeSession = runMode == RunMode.SINGLE;
        settlementPersistenceGeneration = persistence.submit(checkpoint(
                !closeSession, closeSession ? State.STOPPED.name() : State.COOLDOWN.name(),
                sold, AutomationUncertainExposure.none(),
                "Exact sale notice and active-listing disappearance reconciled"));
        if (settlementPersistenceGeneration <= 0) {
            // The prior durable LISTED row intentionally remains unresolved.
            recoveredSession = true;
            pauseInternal("Closed sale could not be queued durably; restart will retain exposure");
            return;
        }
        settlementPendingPosition = sold;
        reconciliationStatus =
                "Sale proven; durable closure acknowledgement still pending";
        if (closeSession) {
            cooldownUntilMillis = now;
            transition(State.COOLDOWN,
                    "Single sale reconciled; persisting the closed position and session");
        } else {
            // The configured cooldown is the minimum; the actual pause varies up
        // to two and a half times it so listings do not land on a fixed beat.
        cooldownUntilMillis = Math.addExact(now,
                Math.round(policy.cooldownMillis() * (1.0 + 1.5 * Math.random())));
            transition(State.COOLDOWN,
                    "Sale reconciled against active listings; bounded cooldown started");
        }
    }

    private ActiveListingReconciler.Reconciliation reconcileOwnTarget(
            Minecraft client, List<Listing> completeActiveBook, Position position) {
        return listingReconciler.reconcile(completeActiveBook,
                new ActiveListingReconciler.Target(
                        baseItemId(position.itemKey()), position.quantity(), position.targetPrice()),
                localUuid(client), localName(client));
    }

    private ContinuousAutomationContext selectionContext(
            MarketWatcher.Snapshot snapshot, long now, boolean hasOpenPosition) {
        return selectionContext(snapshot, now, hasOpenPosition, snapshot.updatedAt());
    }

    private ContinuousAutomationContext selectionContext(
            MarketWatcher.Snapshot snapshot, long now, boolean hasOpenPosition,
            long containingSnapshotAt) {
        // The selector compares spend against the policy's fixed cap. The
        // session's cap grows with the balance, so the spend is shifted down
        // by the difference: the selector then trips exactly when the spend
        // reaches the balance-scaled cap.
        long shifted = Math.max(0, committedSpend - (effectiveSpendCap() - policy.maxSessionSpend()));
        return new ContinuousAutomationContext(
                now, containingSnapshotAt, snapshot.demo(), false, true,
                driver.operationIntent() == AutomatedExecutionDriver.OperationIntent.NONE,
                hasOpenPosition, tradesStarted, shifted,
                cooldownUntilMillis, attemptedListingKeys);
    }

    private MarketStats exactMarketStats(MarketWatcher.Snapshot snapshot,
                                         Opportunity opportunity) {
        if (snapshot == null || opportunity == null || opportunity.listing() == null) return null;
        List<MarketStats> exact = snapshot.markets().stream()
                .filter(stats -> stats != null
                        && opportunity.listing().itemKey().equals(stats.itemKey())
                        && opportunity.listing().bucket() == stats.bucket())
                .toList();
        return exact.size() == 1 ? exact.getFirst() : null;
    }

    private String exactCoverageBlocker(MarketWatcher.ExactListingCoverage coverage,
                                        CoverageRequest request, long now) {
        if (request == null || !request.requested()) return "no exact coverage request is active";
        if (coverage == null) return "coverage is missing";
        if (!coverage.complete()) {
            return coverage.detail().isBlank() ? "coverage is pending or incomplete" : coverage.detail();
        }
        if (!coverage.isFreshFor(request.itemId(), request.scanMustStartAfterMillis())) {
            return "coverage scan did not begin after the phase evidence boundary";
        }
        if (coverage.parseFailures() != 0 || coverage.pagesScanned() <= 0) {
            return "coverage has parse failures or no page evidence";
        }
        if (coverage.scanStartedAt() <= 0
                || coverage.completedAt() < coverage.scanStartedAt()
                || coverage.completedAt() > now) {
            return "coverage scan interval is missing, inverted, or future-dated";
        }
        if (now - coverage.scanStartedAt() > policy.maximumSnapshotAgeMillis()
                || now - coverage.completedAt() > policy.maximumSnapshotAgeMillis()) {
            return "coverage is stale";
        }
        return null;
    }

    /**
     * Two consecutive complete scans in which the rows this phase depends on
     * are identical. The rest of the book may change between them: on a
     * bot-traded item it does, several times a second, and demanding a fully
     * identical book rescanned one busy market seven times in two seconds
     * without ever accepting.
     */
    private boolean acceptConsecutiveStableCoverage(
            String phase, MarketWatcher.ExactListingCoverage coverage,
            ActiveBookEvidence.Analysis book, Predicate<Listing> relevant) {
        if (!phase.equals(firstCoveragePhase) || firstCoverageBook == null) {
            firstCoveragePhase = phase;
            firstCoverageBook = book;
            firstCoverageCompletedAt = coverage.completedAt();
            return false;
        }
        if (!activeBookEvidence.consecutiveStable(
                firstCoverageBook, firstCoverageCompletedAt,
                book, coverage.scanStartedAt(), relevant)) {
            if (coverage.scanStartedAt() > firstCoverageCompletedAt) {
                LOGGER.info("DoughBay automation [{}]: rows this phase depends on changed "
                        + "between scans ({} -> {} rows in book); taking a new baseline",
                        phase, firstCoverageBook.listings().size(), book.listings().size());
                firstCoverageBook = book;
                firstCoverageCompletedAt = coverage.completedAt();
            }
            return false;
        }
        if (!firstCoverageBook.fingerprint().equals(book.fingerprint())) {
            LOGGER.info("DoughBay automation [{}]: book changed between scans "
                    + "({} -> {} rows) but the rows this phase depends on held still",
                    phase, firstCoverageBook.listings().size(), book.listings().size());
        }
        clearCoverageEvidence();
        return true;
    }

    /** Rows whose seller is, or cannot be shown not to be, the local player. */
    private Predicate<Listing> ownRows(Minecraft client) {
        UUID uuid = localUuid(client);
        String name = localName(client);
        return listing -> listingReconciler.ownershipOf(listing, uuid, name)
                != ActiveListingReconciler.Ownership.OTHER;
    }

    private static Predicate<Listing> rowWithKey(String listingKey) {
        return listing -> listingKey != null && listingKey.equals(listing.listingKey());
    }

    private void rollCoverageBaseline(String phase,
                                      MarketWatcher.ExactListingCoverage coverage,
                                      ActiveBookEvidence.Analysis book) {
        if (!phase.equals(firstCoveragePhase)
                || coverage.scanStartedAt() > firstCoverageCompletedAt) {
            firstCoveragePhase = phase;
            firstCoverageBook = book;
            firstCoverageCompletedAt = coverage.completedAt();
        }
    }

    private void clearCoverageEvidence() {
        firstCoveragePhase = "";
        firstCoverageBook = null;
        firstCoverageCompletedAt = 0;
    }

    private String preBuyCoveragePhase() {
        return pendingOpportunity == null ? ""
                : "PRE_BUY:" + pendingOpportunity.listing().listingKey();
    }

    private String preListCoveragePhase() {
        return trackedPosition == null ? ""
                : "PRE_LIST:" + trackedPosition.positionId()
                + ":" + trackedPosition.targetPrice();
    }

    private String postListCoveragePhase() {
        if (trackedPosition == null) return "";
        return (bufferedSaleNotice == null ? "POST_LIST:" : "POST_LIST_FAST_SALE:")
                + trackedPosition.positionId() + ":" + trackedPosition.targetPrice()
                + (bufferedSaleNotice == null ? "" : ":" + saleNoticeObservedAtMillis);
    }

    private String postSaleCoveragePhase() {
        return trackedPosition == null ? ""
                : "POST_SALE:" + trackedPosition.positionId()
                + ":" + trackedPosition.targetPrice()
                + ":" + saleNoticeObservedAtMillis;
    }

    private void clearPendingCandidate() {
        watchBuy = false;
        watchCandidates = List.of();
        pendingOpportunity = null;
        candidateCoverageBoundaryMillis = 0;
        clearCoverageEvidence();
    }

    private static Position withItemKey(Position position, String itemKey) {
        return new Position(position.positionId(), position.mode(), itemKey,
                position.bucket(), position.quantity(), position.purchasePrice(), position.targetPrice(),
                position.purchasedAt(), position.listedAt(), position.closedAt(),
                position.salePrice(), position.realizedProfit(), position.status());
    }

    /**
     * A page row is this position's listing when the stack matches: for a box
     * that means the same contents hash, so three shulker boxes can never be
     * confused for one another; for a plain item the id and count suffice.
     */
    private static boolean rowMatches(AutomatedExecutionDriver.OwnListingRow row, Position listed) {
        if (row.count() != listed.quantity()) return false;
        if (listed.itemKey().indexOf('#') >= 0 || row.itemKey().indexOf('#') >= 0) {
            return row.itemKey().equals(listed.itemKey());
        }
        return row.itemId().equals(baseItemId(listed.itemKey()));
    }

    /**
     * The one inventory slot holding a listable box of this position's kind and
     * count whose contents hash no other open position and no page row claims;
     * -1 when there is none or more than one.
     */
    private int unclaimedContainerSlot(Minecraft client, Position listed,
                                       List<AutomatedExecutionDriver.OwnListingRow> rows) {
        if (client == null || client.player == null) return -1;
        String itemId = baseItemId(listed.itemKey());
        Set<String> claimed = new HashSet<>();
        for (Position p : openListings) if (p.positionId() != listed.positionId()) claimed.add(p.itemKey());
        if (trackedPosition != null) claimed.add(trackedPosition.itemKey());
        for (AutomatedExecutionDriver.OwnListingRow row : rows) claimed.add(row.itemKey());
        List<ItemStack> stacks = client.player.getInventory().getNonEquipmentItems();
        int found = -1;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !itemId(stack).equals(itemId) || stack.getCount() != listed.quantity()) continue;
            if (!AutomatedExecutionDriver.plainForListing(stack)) continue;
            dev.doughbay.fabric.ItemDescriptor descriptor = dev.doughbay.fabric.ItemDescriptor.of(stack);
            if (!descriptor.isContainer() || claimed.contains(itemId + "#" + descriptor.hash())) continue;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    /** The one slot that went from empty to a listable box of this kind and count since the pull-back; -1 otherwise. */
    private int singleNewContainerSlot(Minecraft client, String itemId, int count) {
        if (client == null || client.player == null) return -1;
        List<ItemStack> current = client.player.getInventory().getNonEquipmentItems();
        if (current.size() != inventoryBeforeBuy.size()) return -1;
        int found = -1;
        for (int i = 0; i < current.size(); i++) {
            ItemStack before = inventoryBeforeBuy.get(i);
            ItemStack after = current.get(i);
            if (!before.isEmpty() || after.isEmpty() || !itemId(after).equals(itemId) || after.getCount() != count) continue;
            if (!AutomatedExecutionDriver.plainForListing(after)
                    || !dev.doughbay.fabric.ItemDescriptor.of(after).isContainer()) continue;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    private String reservedSlotBlocked(Inventory inventory) {
        ItemStack held = inventory.getItem(policy.reservedHotbarSlot());
        return "Hotbar slot " + (policy.reservedHotbarSlot() + 1) + " is reserved for listing but holds "
                + itemId(held).replace("minecraft:", "") + " x" + held.getCount()
                + "; move it to another slot, then Resume";
    }

    private enum ReservedSlot { EMPTY, CLEARING, STUCK }

    /** Swap attempts made to empty the reserved listing slot without it taking. */
    private int reservedClearAttempts;

    /**
     * A stray stack in the reserved listing slot (slot 9) used to halt the whole
     * session and wait for a human to move it. The bot already knows how to swap
     * that slot, so instead it parks the occupant in a free inventory slot itself
     * and carries on. It only gives up - {@link ReservedSlot#STUCK}, so the caller
     * pauses - when the inventory is genuinely full or the swap will not take.
     */
    private ReservedSlot ensureReservedSlotFree(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        int reserved = policy.reservedHotbarSlot();
        if (inventory.getItem(reserved).isEmpty()) {
            reservedClearAttempts = 0;
            return ReservedSlot.EMPTY;
        }
        List<ItemStack> items = inventory.getNonEquipmentItems();
        int free = -1;
        for (int i = 0; i < items.size(); i++) {
            if (reservedSlot(i)) continue;
            if (items.get(i).isEmpty()) { free = i; break; }
        }
        if (free < 0) return ReservedSlot.STUCK;                 // no room to park it
        if (reservedClearAttempts >= 4) return ReservedSlot.STUCK;   // swaps not taking
        int menuSlot = inventoryMenuSlot(client, free);
        if (menuSlot < 0) return ReservedSlot.STUCK;
        ItemStack occupant = inventory.getItem(reserved).copy();
        reservedClearAttempts++;
        try {
            client.gameMode.handleContainerInput(
                    client.player.inventoryMenu.containerId, menuSlot, reserved,
                    ContainerInput.SWAP, client.player);
        } catch (RuntimeException e) {
            return ReservedSlot.STUCK;
        }
        LOGGER.info("DoughBay automation: reserved listing slot held {} x{}; moved it to slot {} instead of pausing",
                itemId(occupant), occupant.getCount(), free);
        return ReservedSlot.CLEARING;
    }

    private static Position withTargetPrice(Position position, long targetPrice) {
        return new Position(position.positionId(), position.mode(), position.itemKey(),
                position.bucket(), position.quantity(), position.purchasePrice(), targetPrice,
                position.purchasedAt(), position.listedAt(), position.closedAt(),
                position.salePrice(), position.realizedProfit(), position.status());
    }

    /**
     * Whether the broad feed is usable as live evidence.
     *
     * <p>PARTIAL means some other book could not be proven this sweep. The
     * watcher leaves unproven books out of listings and valuation entirely,
     * so everything a PARTIAL snapshot contains came from a proven book, and
     * the candidate's own evidence is the separate exact scan anyway.
     * Refusing PARTIAL blocked a session on every sweep in which any one of
     * dozens of scanned markets hiccupped.
     */
    static boolean healthyFeedStatus(String status) {
        if (status == null) return false;
        return "OK".equals(status)
                || "No opportunities passed the filters".equals(status)
                || status.startsWith("PARTIAL: ");
    }

    private String liveSnapshotBlocker(MarketWatcher.Snapshot snapshot, long now) {
        if (snapshot == null) return "snapshot is missing";
        if (snapshot.demo()) return "demo snapshot cannot prove active listings";
        if (!healthyFeedStatus(snapshot.status())) {
            return "feed status is " + (snapshot.status() == null ? "missing" : snapshot.status());
        }
        if (snapshot.updatedAt() <= 0 || snapshot.updatedAt() > now) {
            return "snapshot timestamp is missing or future-dated";
        }
        if (snapshot.activeListingScanStartedAt() <= 0
                || snapshot.activeListingScanStartedAt() > snapshot.updatedAt()
                || snapshot.activeListingScanStartedAt() > now) {
            return "active-listing scan interval is missing or invalid";
        }
        if (now - snapshot.updatedAt() > policy.maximumSnapshotAgeMillis()) {
            return "snapshot is stale";
        }
        // The broad sweep over a hundred markets can outlast the snapshot age
        // while the snapshot itself is refreshed every minute; the page look
        // verifies every row live anyway. Three ages before the sweep is stale.
        if (now - snapshot.activeListingScanStartedAt()
                > 3 * policy.maximumSnapshotAgeMillis()) {
            return "broad active-listing scan start is stale";
        }
        return null;
    }

    private static UUID localUuid(Minecraft client) {
        return client == null || client.player == null ? null : client.player.getUUID();
    }

    private static String localName(Minecraft client) {
        return client == null || client.player == null
                ? "" : client.player.getGameProfile().name();
    }

    private AutomatedExecutionDriver.TerminalEvent nextTerminal(
            AutomatedExecutionDriver.OperationIntent intent) {
        AutomatedExecutionDriver.TerminalEvent event = driver.lastTerminalEvent();
        if (event.sequence() <= awaitedTerminalSequence) return null;
        awaitedTerminalSequence = event.sequence();
        if (event.intent() != intent) return event;
        return event;
    }

    private boolean matchesPendingBuy(AutomatedExecutionDriver.TerminalEvent event) {
        if (pendingOpportunity == null || event.intent()
                != AutomatedExecutionDriver.OperationIntent.BUY) return false;
        if (watchBuy) {
            if (!event.listingKey().startsWith("watch:")) return false;
            if (event.outcome() != AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED) return true;
            // The watch bought whichever market came in first; the position
            // is priced on that market's signal from here on.
            for (Opportunity o : watchCandidates) {
                if (o.listing().itemId().equals(event.itemId())
                        && o.listing().itemCount() == event.itemCount()) {
                    pendingOpportunity = o;
                    buyCeiling = Math.max(ceilingFor(o), event.price());
                    return true;
                }
            }
            String clickedKey = driver.watchClickedItemKey();
            if (clickedKey.isBlank()) clickedKey = driver.lastWatchClickedKey();
            Opportunity box = boxOpportunity(clickedKey, System.currentTimeMillis());
            if (box != null && box.listing().itemId().equals(event.itemId())) {
                pendingOpportunity = box;
                buyCeiling = Math.max(box.buyPrice(), event.price());
                LOGGER.info("DoughBay automation: bought a box {} for {}; contents valued at {}",
                        box.listing().itemKey(), event.price(), box.recommendedSellPrice());
                return true;
            }
            return false;
        }
        // An abort that never chose a row carries no stack size; only a
        // success has to be the stack the candidate was priced on.
        return event.listingKey().startsWith("ceiling:")
                && event.itemId().equals(pendingOpportunity.listing().itemId())
                && (event.outcome() != AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED
                || event.itemCount() == pendingOpportunity.listing().itemCount());
    }

    private boolean matchesTrackedList(AutomatedExecutionDriver.TerminalEvent event) {
        if (trackedPosition == null || event.intent()
                != AutomatedExecutionDriver.OperationIntent.LIST) return false;
        return event.listingKey().equals("position:" + trackedPosition.positionId())
                && event.itemKey().equals(trackedPosition.itemKey())
                && event.itemId().equals(baseItemId(trackedPosition.itemKey()))
                && event.itemCount() == trackedPosition.quantity()
                && event.price() == trackedPosition.targetPrice();
    }

    private void pauseInternal(String reason) {
        pausedByPlayer = false;
        pauseFromCurrent(reason, false);
    }

    /**
     * A pause the session raised itself, with nothing left at risk, is a
     * hiccup, not a verdict: a late server receipt, a page that did not
     * open, a slot that moved. Nobody is at the keyboard for an eight-hour
     * run, so after a minute and a half of quiet it goes back to scanning.
     */
    /**
     * The one exposure that needs no reconciliation: the bought stack is
     * right there in the inventory. Whether the session paused mid-listing
     * or the game restarted with the purchase unlisted, the answer is the
     * same as for any purchase: put it through the listing flow.
     */
    private boolean purchasedStackInHand(Minecraft client) {
        if (client == null || client.player == null || client.level == null) return false;
        // A LIST intent that never took the stack out of the inventory is
        // not exposure; the stack being there is the proof nothing listed.
        if (uncertainExposure.active() && !"LISTING".equals(uncertainExposure.phase())) return false;
        if (trackedPosition == null || trackedPosition.status() != PositionStatus.PURCHASED) return false;
        if (recoveredOpenPositions.size() > 1) return false;
        if (unlistedPurchaseSlot(client, trackedPosition.itemKey(), trackedPosition.quantity()) >= 0) return true;
        if (trackedPosition.itemKey().indexOf('#') >= 0) return false;
        // No exact stack: the purchase merged into, or split across, other
        // stacks of the item. The largest one is listed under this position.
        return largestStackSlot(client, trackedPosition.itemKey()) >= 0;
    }

    /**
     * Where an unlisted purchase sits: the exact stack for its key or, for a
     * plain key, the one stack of that item and count whatever its parts. A
     * plain market's row can turn out to carry a potion or an enchantment;
     * the money is spent either way, so the stack is listed under the key of
     * what is really there. -1 when there is no such stack or more than one.
     */
    private static int unlistedPurchaseSlot(Minecraft client, String itemKey, int count) {
        int exact = uniqueExactPlainSlot(client, itemKey, count);
        if (exact >= 0 || itemKey == null || itemKey.indexOf('#') >= 0
                || client == null || client.player == null) return exact;
        String itemId = baseItemId(itemKey);
        List<ItemStack> stacks = client.player.getInventory().getNonEquipmentItems();
        int found = -1;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !itemId(stack).equals(itemId) || stack.getCount() != count) continue;
            if (!AutomatedExecutionDriver.listableWithParts(stack)) continue;
            found = i;
            break;
        }
        return found;
    }

    /** The position key for a stack: its id, with the descriptor hash when it carries parts. */
    private static String keyOfStack(ItemStack stack) {
        dev.doughbay.fabric.ItemDescriptor descriptor = dev.doughbay.fabric.ItemDescriptor.of(stack);
        return descriptor.hasParts() ? itemId(stack) + "#" + descriptor.hash() : itemId(stack);
    }

    private void listPurchasedStackInHand(Minecraft client, long now, String why) {
        // Every route to listing a stack found in hand comes through here, so
        // this is where the deny list has to be honoured. The desk already
        // refuses to list what it names ("held but the desk will not list what
        // it is not allowed to trade"), while nothing stopped that same stack
        // being adopted as an unlisted purchase - so the two halves disagreed
        // and the stack went round for ever: adopt, fail to prepare six times,
        // close for good, get adopted again on the next pass. Two damaged
        // turtle helmets did that 282 times, writing a cancelled position at
        // $87,255 on every lap, none of which was a purchase.
        //
        // Give up on the first lap instead of the sixth, and set the key aside
        // so the next pass leaves it alone. The item itself stays in the
        // inventory for a person to deal with; only the record stops.
        String heldId = baseItemId(trackedPosition.itemKey());
        // A liquidating item is denied from buying but must still be sold off,
        // so it is the one denied thing this path lists rather than sets aside.
        if (!dev.doughbay.fabric.Tuning.itemAllowed(heldId)
                && !dev.doughbay.fabric.Tuning.liquidating(heldId)) {
            setAsideItems.add(trackedPosition.itemKey());
            closePhantom(trackedPosition,
                    "it is on the deny list; it stays in the inventory to be dealt with by hand");
            trackedPosition = null;
            positionValuation = null;
            prepareRetries = 0;
            transition(State.SCANNING, "Set aside " + heldId + ": not allowed to trade");
            return;
        }
        int slot = unlistedPurchaseSlot(client, trackedPosition.itemKey(),
                trackedPosition.quantity());
        if (slot < 0 && trackedPosition.itemKey().indexOf('#') < 0) {
            // The purchase merged into a stack already held: that stack is listed, at the purchase's cost.
            int larger = largestStackSlot(client, trackedPosition.itemKey());
            if (larger >= 0) {
                int merged = client.player.getInventory().getNonEquipmentItems().get(larger).getCount();
                LOGGER.info("DoughBay automation: #{} ({} x{}) has no exact stack; listing the stack of {} under it",
                        trackedPosition.positionId(), trackedPosition.itemKey(), trackedPosition.quantity(), merged);
                trackedPosition = supersedeQuantity(trackedPosition, merged, now);
                slot = larger;
            }
        }
        if (slot >= 0) {
            ItemStack stack = client.player.getInventory().getNonEquipmentItems().get(slot);
            String actual = keyOfStack(stack);
            if (!actual.equals(trackedPosition.itemKey())) {
                LOGGER.info("DoughBay automation: unlisted purchase #{} recorded as {} is {} in the inventory; re-keyed",
                        trackedPosition.positionId(), trackedPosition.itemKey(), actual);
                trackedPosition = withItemKey(trackedPosition, actual);
            }
        }
        String itemId = baseItemId(trackedPosition.itemKey());
        long target = trackedPosition.targetPrice();
        if (target <= 0) {
            double minimumProfit = Math.max(policy.minimumProfit(), riskConfig.minimumProfit());
            target = trackedPosition.purchasePrice()
                    + (long) Math.ceil(Math.max(minimumProfit, trackedPosition.purchasePrice() * 0.10));
        }
        // The listing request reads the position's own price, so a recovered
        // position without one takes the fallback before it is handed over.
        if (trackedPosition.targetPrice() != target) trackedPosition = withTargetPrice(trackedPosition, target);
        recoveredOpenPositions = List.of();
        recoveredSession = false;
        pauseResumable = false;
        purchasedInventorySlot = slot;
        Listing own = new Listing("own:" + trackedPosition.positionId(), now, "", "market",
                itemId, itemId, trackedPosition.quantity(), trackedPosition.purchasePrice(), null);
        MarketStats placeholder = dev.doughbay.fabric.TestBuyPicker.placeholderStats(own, now);
        double net = auctionFees.netSale(target);
        double profit = net - trackedPosition.purchasePrice();
        positionValuation = new Opportunity(own, placeholder, trackedPosition.purchasePrice(), target,
                profit, profit / Math.max(1, trackedPosition.purchasePrice()) * 100.0,
                0, 1.0, 1.0, 0, List.of(why));
        pendingOpportunity = null;
        uncertainExposure = AutomationUncertainExposure.none();
        purchasePersistenceGeneration = persistence.submit(checkpoint(
                true, State.PREPARING_LIST.name(), trackedPosition,
                AutomationUncertainExposure.none(), why));
        if (purchasePersistenceGeneration <= 0) {
            pauseInternal("Purchase in hand could not be queued for persistence");
            return;
        }
        clearCoverageEvidence();
        clearManualResolution();
        preListMarketVerified = true;
        preListVerifiedScanStartedAt = now;
        preListValuationCalculatedAt = now;
        preparationStartedAtMillis = now;
        originalSelectedSlot = -1;
        sourceInventorySlot = -1;
        swapIssuedAtTick = -1;
        prepareRetryPositionId = trackedPosition.positionId();
        prepareRetries = 0;
        probePending = false;
        buyCommandStarted = false;
        buyTerminalSucceeded = false;
        prepareNotBeforeMillis = now + 1500;
        listNotBeforeMillis = now + 2000;
        LOGGER.info("DoughBay automation: {} x{} is in the inventory (bought at {}); listing it at {} ({})",
                itemId, trackedPosition.quantity(), trackedPosition.purchasePrice(), target, why);
        transition(State.PREPARING_LIST, "Listing the purchase in hand: " + itemId
                + " x" + trackedPosition.quantity() + " at " + target);
    }

    /**
     * A BUY intent that paused after the click, with the bought stack now
     * sitting in the inventory, is a purchase the books never received.
     * The exposure record carries the item (with its descriptor hash for a
     * box), the ceiling and the resale target; a receipt from the last
     * minutes gives the real price when one is still in memory. Book it
     * and list it.
     */
    private void adoptBuyExposureInHand(Minecraft client, long now) {
        if (pausedByPlayer || !armed) return;
        if (state != State.PAUSED || !sessionOpen || client == null || client.player == null) return;
        if (!uncertainExposure.active() || !"BUYING".equals(uncertainExposure.phase())) return;
        if (trackedPosition != null && trackedPosition.status() == PositionStatus.PURCHASED) return;
        if (now - stateChangedAtMillis < 5_000) return;
        String itemKey = uncertainExposure.itemKey() == null || uncertainExposure.itemKey().isBlank()
                ? uncertainExposure.itemId() : uncertainExposure.itemKey();
        int count = Math.max(1, uncertainExposure.itemCount());
        int slot = unlistedPurchaseSlot(client, itemKey, count);
        if (slot < 0) return;
        String actual = keyOfStack(client.player.getInventory().getNonEquipmentItems().get(slot));
        if (!actual.equals(itemKey)) {
            LOGGER.info("DoughBay automation: BUY intent for {} matches {} in the inventory; booking it under that key",
                    itemKey, actual);
            itemKey = actual;
        }
        if (policy == null) {
            policy = dev.doughbay.fabric.DoughBayClient.continuousPolicy();
            if (policy == null) return;
        }
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return;
        if (authorizationBlocker(client) != null) return;
        long paid = uncertainExposure.buyPrice();
        String itemId = baseItemId(itemKey);
        String display = AutomatedExecutionDriver.displayNameFor(itemId);
        for (AutomatedExecutionDriver.PurchaseReceipt receipt : driver.recentPurchaseReceipts()) {
            if (receipt.count() == count && !display.isEmpty() && receiptNames(display, receipt.itemName())) {
                paid = receipt.price();
                driver.consumeReceipt(receipt);
                break;
            }
        }
        long target = uncertainExposure.targetPrice();
        tradesStarted++;
        committedSpend = Math.addExact(committedSpend, paid);
        trackedPosition = new Position(nextPositionId++, "REAL", itemKey, StackBucket.of(count), count,
                paid, target, now, 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
        recoveredOpenPositions = List.of();
        LOGGER.info("DoughBay automation: BUY intent for {} found in the inventory; booked at {} and listing at {}",
                itemKey, paid, target);
        listPurchasedStackInHand(client, now, "Bought stack found in the inventory after a paused buy");
    }

    /** Whether the only recovered exposure is a bought stack sitting in the inventory. */
    public synchronized boolean recoverableInHand() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || state != State.PAUSED) return false;
        if (purchasedStackInHand(client)) return true;
        if (!uncertainExposure.active() || !"BUYING".equals(uncertainExposure.phase())) return false;
        String itemKey = uncertainExposure.itemKey() == null || uncertainExposure.itemKey().isBlank()
                ? uncertainExposure.itemId() : uncertainExposure.itemKey();
        return unlistedPurchaseSlot(client, itemKey, Math.max(1, uncertainExposure.itemCount())) >= 0;
    }

    /** After a restart: the recovered purchase is in the inventory, so list it. */
    private boolean recoveryAuditPending;
    private boolean recoveryAuditTried;

    /**
     * A recovered purchase that is not in the inventory was very likely
     * listed already, with the LISTED checkpoint lost (a locked database, a
     * crash between the sell and the write). Rather than ask the player to
     * inspect anything, read the own-listings page: if the stack is up
     * there, adopt it as listed at the price shown and carry on.
     */
    private void autoAdoptRecoveredListing(Minecraft client, long now) {
        if (pausedByPlayer) return;
        // A fresh start is never armed, so on a bot meant to run itself this
        // check never ran and a purchase not in the inventory held the whole
        // session until somebody looked. With auto-resume on, the standing
        // yes covers the check too.
        if ((!armed && !autoResumeOn()) || state != State.PAUSED || !recoveredSession || !sessionOpen) return;
        if (trackedPosition == null || trackedPosition.status() != PositionStatus.PURCHASED) return;
        if (client == null || client.player == null || client.level == null) return;
        if (now - stateChangedAtMillis < 5_000) return;
        if (purchasedStackInHand(client)) return;
        if (recoveryAuditPending) {
            AutomatedExecutionDriver.TerminalEvent event = nextTerminal(AutomatedExecutionDriver.OperationIntent.AUDIT);
            if (event == null) return;
            recoveryAuditPending = false;
            if (event.intent() != AutomatedExecutionDriver.OperationIntent.AUDIT
                    || event.outcome() != AutomatedExecutionDriver.TerminalOutcome.SUCCEEDED) {
                LOGGER.info("DoughBay automation: recovery check of your listings did not complete: {}", event.detail());
                return;
            }
            for (AutomatedExecutionDriver.OwnListingRow row : driver.lastOwnListings()) {
                if (!rowMatches(row, trackedPosition)) continue;
                Position listed = new Position(trackedPosition.positionId(), trackedPosition.mode(), trackedPosition.itemKey(),
                        trackedPosition.bucket(), trackedPosition.quantity(), trackedPosition.purchasePrice(), row.displayedPrice(),
                        trackedPosition.purchasedAt(), now, 0, 0, Double.NaN, PositionStatus.LISTED);
                openListings.add(listed);
                trackedPosition = null;
                positionValuation = null;
                recoveredOpenPositions = List.of();
                recoveredSession = false;
                pauseResumable = false;
                uncertainExposure = AutomationUncertainExposure.none();
                persistence.submit(checkpoint(sessionOpen, State.SCANNING.name(), listed,
                        AutomationUncertainExposure.none(), "Recovered purchase found listed on the page; adopted"));
                LOGGER.info("DoughBay automation: recovered purchase #{} ({} x{}) was already listed at {}; adopted",
                        listed.positionId(), listed.itemKey(), listed.quantity(), row.displayedPrice());
                reconciliationStatus = openListings.size() + " listing(s) open; buying continues";
                transition(State.SCANNING, "Recovered purchase was already listed; adopted, scanning");
                return;
            }
            // A delivery whose receipt came in late looks exactly like a lost
            // stack: gone from the inventory, never listed. The server's own
            // words settle it.
            long receiptAt = driver.lastDeliveryReceiptAt();
            if (receiptAt > trackedPosition.purchasedAt() && now - receiptAt < 10 * 60_000L) {
                long paid = driver.lastDeliveryReceiptAmount();
                if (paid > 0) {
                    LOGGER.info("DoughBay automation: {} was delivered to an order after the wait gave up ({}); settling as sold",
                            trackedPosition.itemKey(), driver.lastDeliveryReceipt());
                    settleDelivered(client, now, paid);
                    return;
                }
            }
            LOGGER.info("DoughBay automation: recovered purchase {} x{} is neither in the inventory nor among your listings",
                    trackedPosition.itemKey(), trackedPosition.quantity());
            if (autoResumeOn()) {
                writeOffRecoveredPurchase(now,
                        "Written off after a restart: not in the inventory, not listed, no delivery receipt");
                return;
            }
            detail = "PAUSED: the bought " + baseItemId(trackedPosition.itemKey()) + " x" + trackedPosition.quantity()
                    + " is neither in the inventory nor listed; resolve it below";
            return;
        }
        if (recoveryAuditTried) return;
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return;
        if (policy == null) {
            policy = dev.doughbay.fabric.DoughBayClient.continuousPolicy();
            if (policy == null) return;
        }
        if (authorizationBlocker(client) != null) return;
        if (closeStrayAuctionPage(client)) return;
        if (modScreenOpen(client)) {
            client.gui.setScreen(null);
            detail = "Closing the GoNuts screen to check your listings for the recovered purchase";
            return;
        }
        if (!safeInventoryContext(client)) return;
        awaitedTerminalSequence = driver.lastTerminalEvent().sequence();
        recoveryAuditTried = true;
        ExecutionResult result = driver.auditOwnListings();
        if (!result.ok()) {
            detail = "Could not check your listings for the recovered purchase: " + result.detail();
            return;
        }
        recoveryAuditPending = true;
        detail = "Recovered purchase not in the inventory; checking your auction listings for it";
    }

    private static boolean autoResumeOn() {
        return dev.doughbay.fabric.Tuning.get("session.auto_resume") >= 0.5;
    }

    /**
     * Closes a recovered purchase that is provably nowhere - not in the
     * inventory, not on the own-listings page, no late delivery receipt - as
     * the loss it already is, so the session can carry on. Only under
     * auto-resume: with a person watching, the pause and its controls are the
     * right answer; without one, a stack that is gone for good was holding a
     * whole account idle over its own cost, with nothing left to protect.
     */
    private void writeOffRecoveredPurchase(long now, String reason) {
        Position p = trackedPosition;
        if (p == null) return;
        Position written = p.closed(PositionStatus.CANCELLED, now, 0, -(double) p.purchasePrice());
        committedSpend = Math.max(0, committedSpend - p.purchasePrice());
        trackedPosition = null;
        positionValuation = null;
        recoveredOpenPositions = recoveredOpenPositions.stream()
                .filter(x -> x.positionId() != p.positionId()).toList();
        recoveryAuditTried = false;
        recoveryAuditPending = false;
        if (!recoveredOpenPositions.isEmpty()) trackedPosition = recoveredOpenPositions.getFirst();
        // A written-off phantom must not leave its uncertain-exposure flag
        // behind. That flag - still pointing at the position just cancelled - is
        // what re-pauses the session on every later recovery ("N unresolved REAL
        // exposure record(s)"), forcing a person to reconcile a stack that is
        // already gone for good. Clearing it in the same step as the write-off
        // lets the next recovery come up clean, on its own.
        if (uncertainExposure.active()
                && (uncertainExposure.listingKey().contains(String.valueOf(p.positionId()))
                        || (uncertainExposure.itemKey().equals(p.itemKey())
                                && uncertainExposure.itemCount() == p.quantity()))) {
            LOGGER.info("DoughBay automation: cleared the uncertain-exposure flag left by the written-off {} x{}",
                    baseItemId(p.itemKey()), p.quantity());
            uncertainExposure = dev.doughbay.storage.AutomationUncertainExposure.none();
        }
        persistence.submit(checkpoint(sessionOpen, State.PAUSED.name(), written,
                uncertainExposure, reason));
        LOGGER.warn("DoughBay automation: wrote off recovered purchase #{} ({} x{}, cost {}): {}",
                p.positionId(), p.itemKey(), p.quantity(), p.purchasePrice(), reason);
        reconciliationStatus = recoveredExposureRemains()
                ? "Recovered exposure is locked pending explicit reconciliation"
                : "Recovered session caps retained; choose Resume or End session";
        detail = recoveredExposureRemains()
                ? "PAUSED: recovered " + recoveredExposureCount()
                + " unresolved REAL exposure record(s); no automatic command may run"
                : "PAUSED: recovered open session; spend/trade caps were not reset";
    }

    boolean mayAutoListRecoveredPurchase(long now) {
        return !pausedByPlayer && (armed || autoResumeOn())
                && state == State.PAUSED && recoveredSession && sessionOpen
                && now - stateChangedAtMillis >= 5_000;
    }

    private void autoListRecoveredPurchase(Minecraft client, long now) {
        if (!mayAutoListRecoveredPurchase(now)) return;
        if (!purchasedStackInHand(client)) return;
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return;
        if (policy == null) {
            policy = dev.doughbay.fabric.DoughBayClient.continuousPolicy();
            if (policy == null) return;
        }
        if (authorizationBlocker(client) != null) return;
        listPurchasedStackInHand(client, now, "Recovered purchase found in the inventory after a restart");
    }

    private long autoResumeRecoveredCheckedAt;

    /**
     * Resumes a recovered session on its own once the world and feed are ready,
     * when {@code session.auto_resume} is on.
     *
     * <p>The recovered-session pause is there so a person confirms the retained
     * spend caps before real money moves again after a restart. For an account
     * meant to run itself for days that confirmation is a standing yes, and the
     * toggle makes it one - so a restart no longer needs a hand on the Resume
     * button. Two things keep it honest: only the clean pause auto-resumes, a
     * pause still holding unresolved exposure waits for eyes exactly as before;
     * and it waits until the client is in a server and the market feed is a
     * completed healthy scan, so it comes back as a working bot, not a blind
     * one attempting an auction that is not there. resumeRecoveredSession keeps
     * every one of its own guards - this only decides when to call it, and
     * retries quietly until they all pass.
     */
    private void autoResumeRecoveredSession(Minecraft client, MarketWatcher.Snapshot snapshot, long now) {
        if (dev.doughbay.fabric.Tuning.get("session.auto_resume") < 0.5) return;
        // A pause the player asked for - the emergency stop, or Stop - must stay
        // put. Auto-resume is for coming back after a restart, where this flag is
        // clear; without this guard an emergency stop paused and then resumed
        // itself seconds later, so it took a fistful of presses and the GUI Stop.
        if (pausedByPlayer) return;
        if (!recoveryApplied || !recoveredSession || !sessionOpen || state != State.PAUSED) return;
        if (recoveredExposureRemains()) return;                       // needs a human
        if (now - autoResumeRecoveredCheckedAt < 5_000L) return;
        autoResumeRecoveredCheckedAt = now;
        if (client == null || client.getConnection() == null) return; // not in a server yet
        // The tick's own snapshot, not lastSnapshot: that field is only set
        // inside scanAndBuy, which never runs while paused, so a paused
        // session read null for ever and this never fired - four clean
        // sweeps in and still waiting for a feed it could not see.
        if (snapshot == null || !healthyFeedStatus(snapshot.status())) return; // feed not a healthy live scan
        resumeRecoveredSession(dev.doughbay.fabric.DoughBayClient.continuousPolicy());
        if (!recoveredSession) {
            LOGGER.info("DoughBay automation: auto-resumed the recovered session "
                    + "(session.auto_resume on; in server, feed healthy)");
        }
    }

    private void autoResumeInternalPause(Minecraft client, long now) {
        // A session recovered from disk has no policy until the player presses
        // Resume; only pauses raised while running are resumed here.
        if (!armed) return;
        if (state != State.PAUSED || pausedByPlayer || !sessionOpen || policy == null) return;
        // Evasion owns the timing of its own resume: never while somebody is
        // still near, however long the internal-pause clock says it has been.
        dev.doughbay.fabric.EvasionGuard evasion = dev.doughbay.fabric.DoughBayClient.evasionGuard();
        if (evasion != null && evasion.triggered()) return;
        if (now - stateChangedAtMillis >= INTERNAL_PAUSE_RESUME_MILLIS() && purchasedStackInHand(client)
                && driver.operationIntent() == AutomatedExecutionDriver.OperationIntent.NONE
                && authorizationBlocker(client) == null) {
            listPurchasedStackInHand(client, now, "Purchase still in the inventory after a pause");
            return;
        }
        if (recoveredExposureRemains()) return;
        if (detail != null && detail.contains("Join a server")
                && dev.doughbay.fabric.Tuning.get("session.resume_after_leave") < 0.5) {
            // Leaving the server is the player's doing; the session waits for Resume.
            pauseResumable = true;
            return;
        }
        if (now - stateChangedAtMillis < INTERNAL_PAUSE_RESUME_MILLIS()) return;
        // A restart hold outlasts the ordinary internal-pause timer, and until
        // now nothing here said so: the server announced it was restarting, the
        // session paused for the ten minutes that hold is worth, and ninety
        // seconds later this resumed it anyway. It then spent the outage
        // attempting an auction that was not there - a hundred and fifty-four
        // aborts in one restart, with bought stock left sitting unlisted in the
        // inventory because every listing attempt timed out too.
        if (serverRestarting()) return;
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return;
        String blocker = authorizationBlocker(client);
        if (blocker != null) return;
        LOGGER.info("DoughBay automation: auto-resuming after an internal pause: {}", detail);
        uncertainExposure = AutomationUncertainExposure.none();
        clearPendingCandidate();
        buyCommandStarted = false;
        buyTerminalSucceeded = false;
        recoveredSession = false;
        pauseResumable = false;
        persistence.submit(checkpoint(true, State.SCANNING.name(), null,
                AutomationUncertainExposure.none(), "Auto-resumed after an internal pause"));
        transition(State.SCANNING, "Auto-resumed after an internal pause; scanning again");
    }

    private void pauseFromCurrent(String reason, boolean resumable) {
        // Repeated Stop/E-stop while already paused must not erase the phase
        // that explains a possible BUY exposure.
        State prior = state == State.PAUSED ? pausedFromState : state;
        if (recoveredExposureRemains()) recoveredSession = true;
        if (prior == State.SCANNING) clearPendingCandidate();
        if (prior == State.REPRICING) {
            repricing = null;
            repricingArrived = false;
        }
        if (prior == State.AUDITING_SLOTS) slotAuditPending = true;
        driver.emergencyStop();
        restoreSelectedSlot(Minecraft.getInstance());
        pausedFromState = prior;
        pauseResumable = resumable
                && (prior == State.SCANNING || prior == State.MONITORING);
        clearCoverageEvidence();
        transition(State.PAUSED, "PAUSED: " + reason);
    }

    /** Names the screen, cursor item, or container that is keeping the buy from starting. */
    private static String guiBlockerDescription(Minecraft client) {
        if (client == null || client.player == null || client.gameMode == null) {
            return "no player is in a world";
        }
        Screen open = client.gui.screen();
        if (open != null) {
            return "the " + open.getClass().getSimpleName()
                    + " screen is open (pause menu, chat, inventory...); close it";
        }
        if (client.player.containerMenu != client.player.inventoryMenu) {
            return "a container is open; close it";
        }
        if (!client.player.inventoryMenu.getCarried().isEmpty()) {
            return "an item is held on the cursor; put it down";
        }
        return "gameplay GUI/cursor activity";
    }

    private static boolean modScreenOpen(Minecraft client) {
        // Always false: the desk used to treat an open GoNuts panel as a screen
        // to close for itself, which yanked the panel out from under you while
        // you were reading it. Now it waits until you close the panel yourself
        // (an open panel just fails safeInventoryContext, so the desk holds).
        return false;
    }

    private boolean safeInventoryContext(Minecraft client) {
        return client != null && client.player != null && client.gameMode != null
                && client.gui.screen() == null
                && client.player.containerMenu == client.player.inventoryMenu
                && client.player.inventoryMenu.getCarried().isEmpty();
    }

    private static boolean inventoryContainsItem(Minecraft client, String itemId) {
        if (client == null || client.player == null) return true;
        if (client.player.containerMenu != null) {
            ItemStack carried = client.player.containerMenu.getCarried();
            if (!carried.isEmpty() && itemId(carried).equals(itemId)) return true;
        }
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && itemId(stack).equals(itemId)) return true;
        }
        return false;
    }

    /**
     * Whether this stack is the one the tracked position is about, for the
     * purpose of finding it and moving it.
     *
     * <p>Deliberately a different question from {@link #exactPlainStack},
     * which asks whether a stack may be <em>priced</em> as the plain commodity
     * its key names. The two were the same code, and that is the whole trident
     * affair: a position opened against a plain market carries the market's
     * key, which has no descriptor, so the moment the stack actually bought
     * turned out to be enchanted it could never be found again. It went round
     * the prepare loop for ever and wrote six thousand ledger rows about one
     * trident. It also puts every gear market out of reach, and gear is where
     * the volume is on a server people raid each other on.
     *
     * <p>Locating our own stack is a looser question and safely so: same item,
     * same count, and only one of them in the inventory. Wear is the exception
     * and is never guessed at - a damaged item is a different thing from the
     * one the market priced, so it is never adopted as ours.
     */
    private static boolean ourStack(ItemStack stack, String itemKeyOrId, int count) {
        if (exactPlainStack(stack, itemKeyOrId, count)) return true;
        if (stack == null || stack.isEmpty() || itemKeyOrId == null) return false;
        // A key with a hash names one specific thing; the hash is the answer
        // and a near miss is a different item, not this one.
        if (itemKeyOrId.indexOf('#') >= 0) return false;
        if (!itemId(stack).equals(baseItemId(itemKeyOrId)) || stack.getCount() != count) return false;
        return !AutomatedExecutionDriver.worn(stack);
    }

    private static int uniqueExactPlainSlot(Minecraft client, String itemKeyOrId, int count) {
        if (client == null || client.player == null) return -1;
        Inventory inventory = client.player.getInventory();
        String itemId = baseItemId(itemKeyOrId);
        int found = -1;
        List<ItemStack> stacks = inventory.getNonEquipmentItems();
        // Two exact copies of a plain stack are interchangeable: listing
        // either leaves the other behind, so the first one serves. Only a
        // stack that is not exact is skipped, never a reason to stop.
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !itemId(stack).equals(itemId)) continue;
            if (!exactPlainStack(stack, itemKeyOrId, count)) continue;
            found = i;
            break;
        }
        if (found >= 0) return found;
        // Nothing matched strictly. The stack may still be ours and simply
        // carry components the market key does not mention - an enchanted
        // pickaxe bought from the pickaxe market. Take it only when there is
        // exactly one candidate, so "ours" is never a guess between two.
        int relaxed = -1;
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !itemId(stack).equals(itemId)) continue;
            if (!ourStack(stack, itemKeyOrId, count)) continue;
            if (relaxed >= 0) return -1;   // two of them: which is ours is not knowable
            relaxed = i;
        }
        return relaxed;
    }

    private int exactSingleArrivalSlot(Minecraft client) {
        if (pendingOpportunity == null) return -1;
        return exactSingleArrivalSlot(client, pendingOpportunity.listing().itemId(),
                pendingOpportunity.listing().itemCount());
    }

    private int exactSingleArrivalSlot(Minecraft client, String itemKeyOrId, int count) {
        String itemId = itemKeyOrId;
        if (client == null || client.player == null) return -1;
        List<ItemStack> current = client.player.getInventory().getNonEquipmentItems();
        if (current.size() != inventoryBeforeBuy.size()) return -1;
        int arrival = -1;
        int arrivals = 0;
        StringBuilder others = new StringBuilder();
        for (int i = 0; i < current.size(); i++) {
            ItemStack before = inventoryBeforeBuy.get(i);
            ItemStack after = current.get(i);
            if (sameStack(before, after)) continue;
            if (before.isEmpty() && exactPlainStack(after,
                    arrivedItemKey != null ? arrivedItemKey
                            : pendingOpportunity != null ? pendingOpportunity.listing().itemKey() : itemKeyOrId, count)) {
                arrival = i;
                arrivals++;
            } else {
                // The server rewrites components on unrelated stacks (value
                // hints and the like). Those changes are logged, not held
                // against the purchase: what matters is one new exact stack
                // of the bought item, and the server's receipt already
                // proved the purchase happened.
                others.append(" slot").append(i).append(':')
                        .append(before.isEmpty() ? "empty" : itemId(before) + "x" + before.getCount())
                        .append("->")
                        .append(after.isEmpty() ? "empty" : itemId(after) + "x" + after.getCount())
                        .append(after.getComponentsPatch().entrySet().stream()
                                .map(e -> e.getKey().toString()).toList());
            }
        }
        if (others.length() > 0) {
            LOGGER.info("DoughBay automation: unrelated inventory changes during BUY:{}", others);
        }
        return arrivals == 1 ? arrival : -1;
    }

    /** The real key of a bought stack that arrived carrying parts under a plain market's buy. */
    private String arrivedItemKey;

    /** The one slot that went from empty to a listable stack of this id and count, whatever its parts; -1 otherwise. */
    private int singleNewStackWithParts(Minecraft client, String itemId, int count) {
        if (client == null || client.player == null) return -1;
        List<ItemStack> current = client.player.getInventory().getNonEquipmentItems();
        if (current.size() != inventoryBeforeBuy.size()) return -1;
        int found = -1;
        for (int i = 0; i < current.size(); i++) {
            ItemStack before = inventoryBeforeBuy.get(i);
            ItemStack after = current.get(i);
            if (!before.isEmpty() || after.isEmpty() || !itemId(after).equals(itemId) || after.getCount() != count) continue;
            if (!AutomatedExecutionDriver.listableWithParts(after)) continue;
            if (AutomatedExecutionDriver.worn(after)) {
                // Bought sight-unseen and it turned out to be damaged: still
                // list it rather than strand the money, but under its own key.
                LOGGER.warn("DoughBay automation: the bought {} is damaged; listing it under its own identity", itemId);
            }
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    private static List<ItemStack> copyNonEquipmentInventory(Inventory inventory) {
        List<ItemStack> copy = new ArrayList<>(inventory.getNonEquipmentItems().size());
        for (ItemStack stack : inventory.getNonEquipmentItems()) copy.add(stack.copy());
        return List.copyOf(copy);
    }

    /**
     * Why the sell command may not be sent yet, or null when it may. Items
     * and counts per slot must match the snapshot taken when the stack was
     * moved; component rewrites by the server (value hints and the like)
     * are not a change, and the reserved slot must still hold the exact
     * plain stack with the hotbar selection on it.
     */
    private String listGateBlocker(Minecraft client) {
        if (!safeInventoryContext(client)) return "a screen or carried item is in the way";
        Inventory inventory = client.player.getInventory();
        List<ItemStack> current = inventory.getNonEquipmentItems();
        if (current.size() != inventoryBeforeList.size()) return "inventory size changed";
        for (int i = 0; i < current.size(); i++) {
            ItemStack before = inventoryBeforeList.get(i);
            ItemStack after = current.get(i);
            // Something arriving in a slot that was empty is a delivery, not
            // tampering. The bid desk has players filling orders continuously,
            // so a fill lands mid-listing, this gate refuses to send the sell,
            // and the pause it raises stops the desk from clearing the fill -
            // the two halves of the bot deadlock on each other and the same
            // slot is named every few seconds until someone restarts it.
            //
            // What this gate exists to protect is the stack about to be sold,
            // and that is checked on its own below: the reserved slot must
            // still hold our exact stack and the hotbar must still be pointing
            // at it. Neither can be touched by an item appearing somewhere
            // that was empty, so an arrival is allowed and everything else -
            // a slot emptied, a count changed, an item swapped - still stops
            // the sell.
            if (before.isEmpty() && !after.isEmpty()) continue;
            boolean same = before.isEmpty() ? after.isEmpty()
                    : !after.isEmpty() && itemId(before).equals(itemId(after))
                    && before.getCount() == after.getCount();
            if (!same) {
                return "slot " + i + " changed from "
                        + (before.isEmpty() ? "empty" : itemId(before) + "x" + before.getCount())
                        + " to " + (after.isEmpty() ? "empty" : itemId(after) + "x" + after.getCount());
            }
        }
        ItemStack reserved = inventory.getItem(policy.reservedHotbarSlot());
        if (!ourStack(reserved, trackedPosition.itemKey(), trackedPosition.quantity())) {
            return "reserved slot holds " + (reserved.isEmpty() ? "nothing"
                    : itemId(reserved) + "x" + reserved.getCount() + " with components "
                    + reserved.getComponentsPatch().entrySet().stream()
                    .map(e -> e.getKey().toString()).toList());
        }
        if (inventory.getSelectedSlot() != policy.reservedHotbarSlot()) {
            return "hotbar selection moved to slot " + (inventory.getSelectedSlot() + 1);
        }
        return null;
    }

    private static boolean inventoryEquals(Minecraft client, List<ItemStack> expected) {
        if (client == null || client.player == null || expected == null) return false;
        List<ItemStack> current = client.player.getInventory().getNonEquipmentItems();
        if (current.size() != expected.size()) return false;
        for (int i = 0; i < current.size(); i++) {
            if (!sameStack(current.get(i), expected.get(i))) return false;
        }
        return true;
    }

    private boolean exactSingleListingRemoval(Minecraft client) {
        if (client == null || client.player == null || trackedPosition == null) return false;
        List<ItemStack> current = client.player.getInventory().getNonEquipmentItems();
        if (current.size() != inventoryBeforeList.size()) return false;
        int changed = -1;
        for (int i = 0; i < current.size(); i++) {
            ItemStack before = inventoryBeforeList.get(i);
            ItemStack after = current.get(i);
            if (sameStack(before, after)) continue;
            if (changed >= 0 || i != policy.reservedHotbarSlot()
                    || !exactPlainStack(before, trackedPosition.itemKey(),
                    trackedPosition.quantity())
                    || !after.isEmpty()) {
                return false;
            }
            changed = i;
        }
        return changed == policy.reservedHotbarSlot();
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty();
        if (left.getCount() != right.getCount()) return false;
        // DonutSMP rewrites a sell-value hint into the lore of every stack in
        // the inventory as prices move. That is not the stack changing, and
        // treating it as one made every purchase look ambiguous.
        ItemStack a = left.copy();
        ItemStack b = right.copy();
        a.remove(net.minecraft.core.component.DataComponents.LORE);
        b.remove(net.minecraft.core.component.DataComponents.LORE);
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /**
     * Whether a stack is exactly the tracked thing. A key without a hash
     * is a plain commodity: same item, same count, nothing attached, and
     * not a filled box. A key with a descriptor hash is a specific box:
     * same item and count, and the same contents, verified by hash.
     */
    private static boolean exactPlainStack(ItemStack stack, String itemKeyOrId, int count) {
        if (stack == null || stack.isEmpty() || itemKeyOrId == null) return false;
        String itemId = baseItemId(itemKeyOrId);
        if (!itemId(stack).equals(itemId) || stack.getCount() != count) return false;
        int hash = itemKeyOrId.indexOf('#');
        dev.doughbay.fabric.ItemDescriptor descriptor = dev.doughbay.fabric.ItemDescriptor.of(stack);
        if (hash < 0) return AutomatedExecutionDriver.plainForListing(stack) && !descriptor.hasParts();
        return AutomatedExecutionDriver.listableWithParts(stack)
                && descriptor.hash().equals(itemKeyOrId.substring(hash + 1));
    }

    private static int inventoryMenuSlot(Minecraft client, int inventorySlot) {
        Inventory inventory = client.player.getInventory();
        List<Slot> slots = client.player.inventoryMenu.slots;
        int found = -1;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.container == inventory && slot.getContainerSlot() == inventorySlot) {
                if (found >= 0) return -1;
                found = i;
            }
        }
        return found;
    }

    private void restoreSelectedSlot(Minecraft client) {
        if (originalSelectedSlot >= 0 && originalSelectedSlot <= 8
                && client != null && client.player != null) {
            setSelectedSlot(client, originalSelectedSlot);
        }
        originalSelectedSlot = -1;
    }

    private void clearPreparationState() {
        preparationStartedAtMillis = 0;
        originalSelectedSlot = -1;
        sourceInventorySlot = -1;
        swapIssuedAtTick = -1;
        targetAbsentBeforeBuy = false;
        inventoryBeforeBuy = List.of();
        buyTerminalSucceeded = false;
        purchasedInventorySlot = -1;
        purchaseStableSinceTick = -1;
        inventoryBeforeList = List.of();
        listTerminalSucceeded = false;
        listStableSinceTick = -1;
        listingInventoryVerified = false;
        listInventoryProofAtMillis = 0;
        listingReconciliationStartedAt = 0;
        preListMarketVerified = false;
        preListVerifiedScanStartedAt = 0;
        preListValuationCalculatedAt = 0;
    }

    private static boolean setSelectedSlot(Minecraft client, int slot) {
        if (client == null || client.player == null || slot < 0 || slot > 8) return false;
        try {
            client.player.getInventory().setSelectedSlot(slot);
            if (client.getConnection() == null) return false;
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
            return true;
        } catch (RuntimeException ignored) {
            // Disconnect/menu races are expected failure modes. Callers either
            // pause before listing or finish a best-effort restore without
            // allowing the exception to escape END_CLIENT_TICK.
            return false;
        }
    }

    private void rememberAttempt(String listingKey) {
        if (listingKey != null && listingKey.startsWith("market:")) return;
        attemptedListingKeys.add(listingKey);
        while (attemptedListingKeys.size() > MAX_ATTEMPTED_LISTINGS) {
            attemptedListingKeys.remove(attemptedListingKeys.iterator().next());
        }
    }

    private String persistenceStartBlocker() {
        if (persistence == null) return "Automation persistence is unavailable";
        AutomationPersistencePort.Status status = persistence.status();
        if (!status.ready()) {
            return "Automation recovery is not ready: " + status.loadState()
                    + (status.detail().isBlank() ? "" : " — " + status.detail());
        }
        if (!recoveryApplied) {
            return "Automation recovery has not yet been applied on the client tick";
        }
        if (status.lastAcceptedGeneration() > status.lastDurableGeneration()) {
            return "A prior automation checkpoint is still awaiting durable acknowledgement";
        }
        if (hasPendingControllerPersistence()) {
            return "A durable automation checkpoint has not yet been applied by the controller";
        }
        return null;
    }

    private String persistenceHealthBlocker() {
        if (persistence == null) return "Automation persistence is unavailable";
        AutomationPersistencePort.Status status = persistence.status();
        return status.ready() ? null
                : "Automation persistence is " + status.loadState()
                + (status.detail().isBlank() ? "" : ": " + status.detail());
    }

    private AutomationSessionCheckpoint checkpoint(
            boolean open, String checkpointState, Position position,
            AutomationUncertainExposure uncertain, String checkpointDetail) {
        accrueActiveRuntime();
        long now = System.currentTimeMillis();
        long started = sessionStartedAtMillis > 0
                ? sessionStartedAtMillis : Math.max(1, now);
        return new AutomationSessionCheckpoint(
                open,
                runMode.name(),
                checkpointState == null ? state.name() : checkpointState,
                tradesStarted,
                committedSpend,
                started,
                sessionActiveMillis,
                lifetimeActiveMillis,
                now,
                checkpointDetail,
                boundListingKey,
                position,
                uncertain);
    }

    /**
     * An abort that provably moved no money: the driver never submitted a
     * confirmation, or every confirmed row turned out to be already sold.
     */
    private static boolean nothingCharged(AutomatedExecutionDriver.TerminalEvent event) {
        String detail = event.detail() == null ? "" : event.detail().toLowerCase(Locale.ROOT);
        return !event.confirmationSubmitted()
                || detail.contains("nothing was charged")
                || detail.contains("already bought");
    }

    /**
     * No live row cleared the ceiling, or every candidate row was a ghost.
     * That is a normal market outcome, not a fault: release the intent, rest
     * this market briefly, and go back to scanning.
     */
    private void abandonBuyAndRescan(String why) {
        boolean watched = watchBuy;
        watchBuy = false;
        watchCandidates = List.of();
        if (pendingOpportunity != null && !watched) {
            // A market whose cheapest live row sits well above the ceiling is
            // not going to move in two minutes; a near miss or a ghost might.
            long cheapest = driver.lastCheapestVisible();
            boolean farOff = cheapest > 0 && buyCeiling > 0 && cheapest > buyCeiling * 1.1;
            if (cheapest > 0) {
                huntNotes.put(pendingOpportunity.listing().itemId(),
                        "cheapest seen " + cheapest + " vs ceiling " + buyCeiling + " at "
                                + java.time.LocalTime.now().withNano(0));
            }
            long rest = farOff ? MARKET_LONG_REST_MILLIS
                    : cheapest <= 0 ? MARKET_ABSENT_REST_MILLIS : MARKET_REST_MILLIS;
            restMarket(pendingOpportunity.listing().itemId(), System.currentTimeMillis() + rest);
        }
        uncertainExposure = AutomationUncertainExposure.none();
        persistence.submit(checkpoint(true, State.SCANNING.name(), null,
                AutomationUncertainExposure.none(),
                "BUY abandoned before any purchase: " + why));
        buyIntentPersistenceGeneration = 0;
        buyCommandStarted = false;
        buyTerminalSucceeded = false;
        buyCeiling = 0;
        verifiedBuyPrice = 0;
        buyIntentCoverageBoundaryMillis = 0;
        clearPendingCandidate();
        transition(State.SCANNING, "No purchase: " + why + "; scanning again");
    }

    private static AutomationUncertainExposure uncertainBuy(
            Opportunity opportunity, long ceiling, long observedAt) {
        Listing listing = opportunity.listing();
        return new AutomationUncertainExposure(
                true, "BUYING", "ceiling:" + listing.itemId() + ":" + ceiling,
                listing.itemKey(), listing.itemId(),
                listing.itemCount(), ceiling,
                opportunity.recommendedSellPrice(), "", observedAt);
    }

    private static AutomationUncertainExposure uncertainList(
            Position position, long observedAt) {
        return new AutomationUncertainExposure(
                true, "LISTING", "position:" + position.positionId(),
                position.itemKey(), baseItemId(position.itemKey()), position.quantity(),
                position.purchasePrice(), position.targetPrice(), "", observedAt);
    }

    private static boolean sameExecutionTerms(Opportunity expected, Opportunity actual) {
        if (expected == null || actual == null
                || expected.listing() == null || actual.listing() == null) return false;
        Listing left = expected.listing();
        Listing right = actual.listing();
        return expected.buyPrice() == actual.buyPrice()
                && expected.recommendedSellPrice() == actual.recommendedSellPrice()
                && safeString(left.listingKey()).equals(safeString(right.listingKey()))
                && safeString(left.itemKey()).equals(safeString(right.itemKey()))
                && safeString(left.itemId()).equals(safeString(right.itemId()))
                && left.itemCount() == right.itemCount()
                && left.totalPrice() == right.totalPrice()
                && safeString(left.sellerUuid()).equalsIgnoreCase(safeString(right.sellerUuid()))
                && safeString(left.sellerName()).equalsIgnoreCase(safeString(right.sellerName()));
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private void queuePauseCheckpoint(String checkpointDetail) {
        if (!sessionOpen || persistence == null || !persistence.status().ready()) return;
        AutomationPersistencePort.Status status = persistence.status();
        if (status.lastAcceptedGeneration() > status.lastDurableGeneration()
                || hasPendingControllerPersistence()) {
            // Never enqueue stale in-memory exposure behind a newer critical
            // write (manual resolution, SOLD, PURCHASED, LISTED, etc.).
            return;
        }
        long generation = persistence.submit(checkpoint(
                true, State.PAUSED.name(), trackedPosition, uncertainExposure,
                checkpointDetail));
        if (generation <= 0) {
            persistenceStatus = "FAILED — pause checkpoint queue rejected; prior durable state retained";
        }
    }

    private boolean requestSessionEnd(String endDetail) {
        if (endSessionPersistenceGeneration > 0) {
            detail = "Session-end checkpoint is already awaiting durable acknowledgement";
            return false;
        }
        if (recoveredExposureRemains()) {
            detail = "Cannot end a session while unresolved exposure remains";
            return false;
        }
        if (persistence == null || !persistence.status().ready()) {
            detail = "Cannot end session until persistence is READY";
            return false;
        }
        endSessionPersistenceGeneration = persistence.submit(checkpoint(
                false, State.STOPPED.name(), trackedPosition,
                AutomationUncertainExposure.none(), endDetail));
        if (endSessionPersistenceGeneration <= 0) {
            detail = "Session-end checkpoint was rejected; durable session remains open";
            return false;
        }
        recoveredSession = true;
        pauseFromCurrent(endDetail + "; waiting for durable acknowledgement", false);
        return true;
    }

    private void clearPersistenceGenerations() {
        sessionStartPersistenceGeneration = 0;
        buyIntentPersistenceGeneration = 0;
        purchasePersistenceGeneration = 0;
        listIntentPersistenceGeneration = 0;
        listedPersistenceGeneration = 0;
        settlementPersistenceGeneration = 0;
        endSessionPersistenceGeneration = 0;
        manualResolutionPersistenceGeneration = 0;
        runtimePersistenceGeneration = 0;
        buyCommandStarted = false;
        buyIntentCoverageBoundaryMillis = 0;
        listCommandStarted = false;
        listIntentCoverageBoundaryMillis = 0;
        clearManualResolution();
    }

    private long pendingPersistenceSince;
    private long wedgedSince;   // when a locked recovered exposure first wedged the session
    private static final long STUCK_PERSISTENCE_MILLIS = 120_000L;

    private long pendingPersistenceDurableGeneration;

    /** Watch unfinished writes, not durable receipts still needed by an active trade. */
    private void checkStuckPersistence(long now) {
        if (persistence == null) return;
        AutomationPersistencePort.Status status = persistence.status();
        if (!status.ready() || status.lastAcceptedGeneration() <= status.lastDurableGeneration()) {
            pendingPersistenceSince = 0;
            return;
        }
        if (pendingPersistenceSince == 0
                || pendingPersistenceDurableGeneration != status.lastDurableGeneration()) {
            pendingPersistenceSince = now;
            pendingPersistenceDurableGeneration = status.lastDurableGeneration();
            return;
        }
        if (now - pendingPersistenceSince < STUCK_PERSISTENCE_MILLIS) return;
        // Zeroing an acknowledged LIST intent made durable(0) permanently false
        // on the next sell retry. Zeroing an unfinished intent also discards the
        // evidence needed to reconcile a late write. Preserve both kinds of receipt.
        pendingPersistenceSince = now;
        LOGGER.warn("DoughBay automation: persistence has made no progress in {} s "
                        + "(accepted {}, durable {}); preserving checkpoint receipts",
                STUCK_PERSISTENCE_MILLIS / 1000,
                status.lastAcceptedGeneration(), status.lastDurableGeneration());
        if (isRunning()) pauseInternal("Automation checkpoint write stalled; checkpoint receipts retained");
    }

    private boolean hasPendingControllerPersistence() {
        return sessionStartPersistenceGeneration > 0
                || buyIntentPersistenceGeneration > 0
                || purchasePersistenceGeneration > 0
                || listIntentPersistenceGeneration > 0
                || listedPersistenceGeneration > 0
                || settlementPersistenceGeneration > 0
                || endSessionPersistenceGeneration > 0
                || manualResolutionPersistenceGeneration > 0
                || runtimePersistenceGeneration > 0;
    }

    private void clearManualResolution() {
        manualResolutionFirstConfirmedAt = 0;
        manualResolutionCoverageBoundary = 0;
        manualResolutionEvidenceAt = 0;
        manualResolutionEvidence = "";
        manualResolutionEvidenceReady = false;
    }

    private int recoveredExposureCount() {
        List<Position> positions = exposurePositions();
        int count = positions.size();
        boolean uncertainIsSamePosition = uncertainExposure.active()
                && positions.size() == 1
                && uncertainMatchesPosition(uncertainExposure, positions.getFirst());
        if (uncertainExposure.active() && !uncertainIsSamePosition) count++;
        return count;
    }

    private List<Position> exposurePositions() {
        List<Position> positions = new ArrayList<>(recoveredOpenPositions);
        if (trackedPosition != null && !trackedPosition.status().isClosed()
                && positions.stream().noneMatch(position ->
                position.positionId() == trackedPosition.positionId())) {
            positions.add(trackedPosition);
        }
        return List.copyOf(positions);
    }

    private static boolean uncertainMatchesPosition(
            AutomationUncertainExposure uncertain, Position position) {
        return uncertain != null && uncertain.active() && position != null
                && uncertain.phase().toUpperCase(Locale.ROOT).contains("LIST")
                && uncertain.listingKey().equals("position:" + position.positionId())
                && uncertain.itemKey().equals(position.itemKey())
                && uncertain.itemId().equals(baseItemId(position.itemKey()))
                && uncertain.itemCount() == position.quantity()
                && uncertain.buyPrice() == position.purchasePrice()
                && uncertain.targetPrice() == position.targetPrice();
    }

    private String recoveredExposureItemId() {
        if (uncertainExposure.active()) return uncertainExposure.itemId();
        List<Position> positions = exposurePositions();
        Position position = positions.size() == 1 ? positions.getFirst() : null;
        return position == null ? "" : baseItemId(position.itemKey());
    }

    private int recoveredExposureItemCount() {
        if (uncertainExposure.active()) return uncertainExposure.itemCount();
        List<Position> positions = exposurePositions();
        Position position = positions.size() == 1 ? positions.getFirst() : null;
        return position == null ? 0 : position.quantity();
    }

    private String recoveredOwnershipBlocker(Minecraft client,
                                             List<Listing> completeBook,
                                             String itemId,
                                             int count) {
        if (client == null || client.player == null || count <= 0) {
            return "local player identity or recovered count is unavailable";
        }
        for (Listing listing : completeBook) {
            if (listing == null || !itemId.equals(listing.itemId())
                    || listing.itemCount() != count) continue;
            ActiveListingReconciler.Ownership ownership = listingReconciler.ownershipOf(
                    listing, localUuid(client), localName(client));
            if (ownership == ActiveListingReconciler.Ownership.UNKNOWN) {
                return "matching listing has unknown or contradictory ownership";
            }
            if (ownership == ActiveListingReconciler.Ownership.OWN) {
                return "an own active listing with the recovered item/count still exists";
            }
        }
        return null;
    }

    private String manualResolutionCoverageBlocker(
            Minecraft client, MarketWatcher.Snapshot snapshot, long now) {
        if (snapshot == null || snapshot.demo()) return "real snapshot is unavailable";
        String snapshotBlocker = liveSnapshotBlocker(snapshot, now);
        if (snapshotBlocker != null) return snapshotBlocker;
        MarketWatcher.ExactListingCoverage coverage = snapshot.exactListingCoverage();
        String itemId = recoveredExposureItemId();
        if (coverage == null || !coverage.complete() || coverage.parseFailures() != 0
                || !itemId.equals(coverage.itemId())) {
            return "complete zero-failure exact coverage is unavailable";
        }
        if (!coverage.isFreshFor(itemId, manualResolutionEvidenceAt)
                || coverage.scanStartedAt() <= 0
                || coverage.completedAt() < coverage.scanStartedAt()
                || coverage.completedAt() > now
                || now - coverage.completedAt() > policy.maximumSnapshotAgeMillis()) {
            return "a later fresh exact-book recheck is not complete";
        }
        ActiveBookEvidence.Analysis book = activeBookEvidence.analyze(itemId, coverage.listings());
        if (!book.valid()) return book.detail();
        return recoveredOwnershipBlocker(
                client, book.listings(), itemId,
                recoveredExposureItemCount());
    }

    private String manualResolutionStage() {
        if (endSessionPersistenceGeneration > 0) return "SESSION_CLOSE_PENDING";
        if (manualResolutionPersistenceGeneration > 0) return "AUDIT_WRITE_PENDING";
        if (manualResolutionEvidenceReady) return "SECOND_CONFIRMATION_REQUIRED";
        if (manualResolutionFirstConfirmedAt > 0) return "EVIDENCE_SCANNING";
        return recoveredSession && recoveredExposureRemains()
                ? "FIRST_CONFIRMATION_REQUIRED" : "NONE";
    }

    private static RunMode parseRunMode(String value) {
        try {
            return RunMode.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException ignored) {
            return RunMode.SINGLE;
        }
    }

    private static State parseState(String value) {
        try {
            return State.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException ignored) {
            return State.STOPPED;
        }
    }

    private boolean hasUnresolvedPosition() {
        return pendingOpportunity != null
                || uncertainExposure.active()
                || !recoveredOpenPositions.isEmpty()
                || (trackedPosition != null && !trackedPosition.status().isClosed());
    }

    /**
     * LISTED positions found on restart are live listings, not uncertain
     * exposure: they are adopted as open listings and closed by their sale
     * notices. Everything else stays in the audited recovery path.
     */
    private int openOfMarket(String itemId, int count) {
        int n = 0;
        for (Position p : openListings) {
            if (baseItemId(p.itemKey()).equals(itemId) && p.quantity() == count) n++;
        }
        // A stack pulled back for a reprice has left openListings and a stack
        // just bought has not joined it yet, but both are exposure in this
        // market and both come back as listings within the minute. Counting
        // only what is up on the auction house made the cap invisible exactly
        // when it mattered: name tags spent the day in the reprice queue, so
        // every buy saw a smaller book than the one it was adding to, and a
        // market capped at ten reached eighteen listings holding 93% of the
        // capital. What is in hand is part of the market's book.
        for (Position p : stockInHand()) {
            if (baseItemId(p.itemKey()).equals(itemId) && p.quantity() == count) n++;
        }
        return n;
    }

    private Tier tierOf(long price) {
        long mid = MID_TIER_FALLBACK;
        long large = LARGE_TIER_FALLBACK;
        if (lastKnownBalance > 0) {
            mid = Math.round(lastKnownBalance * MID_TIER_PERCENT_OF_BALANCE() / 100.0);
            large = Math.round(lastKnownBalance * LARGE_TIER_PERCENT_OF_BALANCE() / 100.0);
        }
        if (price >= large) return Tier.LARGE;
        if (price >= mid) return Tier.MID;
        return Tier.SMALL;
    }

    private int tierSlots(Tier tier) {
        int max = maxOpenListings();
        if (max < 3) return max;
        int large = Math.max(1, (int) Math.round(max * LARGE_TIER_SHARE()));
        int mid = Math.max(1, (int) Math.round(max * MID_TIER_SHARE()));
        return switch (tier) {
            case LARGE -> large;
            case MID -> mid;
            case SMALL -> Math.max(1, max - mid - large) + (quietField() ? QUIET_FIELD_EXTRA_SMALL_SLOTS() : 0);
        };
    }

    /**
     * Listings bought to be held: for the evening lift, or on a long estimate.
     * They earn well, but ninety of them leave the fast churn nowhere to work,
     * which is what an idle morning with every slot full looks like. A share
     * of the slots is therefore kept for quick flips, and a slow candidate is
     * refused once the slow lane is full.
     */
    private final java.util.Set<Long> slowIntent = new java.util.HashSet<>();
    private boolean pendingIsSlow;

    private static long quickHoldMillis() {
        return dev.doughbay.fabric.Tuning.millis("slots.quick_hold_min");
    }

    private boolean quickCandidate(Opportunity o) {
        return o.estimatedHoldHours() * 3_600_000.0 <= quickHoldMillis();
    }

    /** Slots the slow lane may fill: everything not held back for quick flips. */
    private int slowLaneSlots() {
        int max = maxOpenListings();
        int reserve = (int) Math.round(max * dev.doughbay.fabric.Tuning.get("slots.quick_reserve_pct") / 100.0);
        return Math.max(1, max - reserve);
    }

    /** Open listings that are slow: bought as such, or simply up longer than a quick flip should be. */
    private int openSlow(long now) {
        java.util.Set<Long> openIds = new java.util.HashSet<>();
        int slow = 0;
        for (Position p : openListings) {
            openIds.add(p.positionId());
            long since = p.listedAt() > 0 ? p.listedAt() : p.purchasedAt();
            if (slowIntent.contains(p.positionId()) || (since > 0 && now - since > quickHoldMillis())) slow++;
        }
        slowIntent.retainAll(openIds);
        return slow;
    }

    private int openOfTier(Tier tier) {
        int n = 0;
        for (Position p : openListings) {
            if (tierOf(p.purchasePrice()) == tier) n++;
        }
        return n;
    }

    /** "small 12/25 · mid 3/15 · large 1/5", for the Automation tab and status lines. */
    public synchronized String tierSummary() {
        StringBuilder out = new StringBuilder();
        for (Tier tier : Tier.values()) {
            if (out.length() > 0) out.append(" · ");
            out.append(tier.name().toLowerCase(java.util.Locale.ROOT)).append(' ')
                    .append(openOfTier(tier)).append('/').append(tierSlots(tier));
        }
        out.append(" · slow ").append(openSlow(System.currentTimeMillis())).append('/').append(slowLaneSlots());
        return out.toString();
    }

    /** The ticket thresholds at the current balance, for display. */
    public synchronized String tierThresholds() {
        long mid = MID_TIER_FALLBACK;
        long large = LARGE_TIER_FALLBACK;
        if (lastKnownBalance > 0) {
            mid = Math.round(lastKnownBalance * MID_TIER_PERCENT_OF_BALANCE() / 100.0);
            large = Math.round(lastKnownBalance * LARGE_TIER_PERCENT_OF_BALANCE() / 100.0);
        }
        return "mid from " + mid + ", large from " + large;
    }

    private long parkedSpend() {
        long parked = 0;
        for (Position p : openListings) parked += p.purchasePrice();
        return parked;
    }

    private List<Position> adoptListedPositions(List<Position> recovered) {
        List<Position> remaining = new ArrayList<>();
        for (Position position : recovered) {
            if (position.status() == PositionStatus.LISTED
                    && openListings.stream().noneMatch(p -> p.positionId() == position.positionId())) {
                openListings.add(position);
                slotAuditPending = true;
            } else if (position.status() != PositionStatus.LISTED) {
                remaining.add(position);
            }
        }
        // Adopted listings are money still out. A fresh session starts its
        // spend at zero, so without this the outstanding-spend cap would
        // ignore everything parked before the restart.
        long parked = 0;
        for (Position p : openListings) parked += p.purchasePrice();
        committedSpend = Math.max(committedSpend, parked);
        return List.copyOf(remaining);
    }

    /** The verified listing stays up; the session goes back to trading. */
    private void parkListedPosition(long now) {
        // The loan is spent: the listing it paid for carries the id from here,
        // and the next purchase is this client's own money again.
        if (activeLoanId > 0) {
            LOGGER.info("DoughBay hive: loan #{} paid for {} x{}; repayment waits for it to sell",
                    activeLoanId, trackedPosition.itemKey(), trackedPosition.quantity());
            activeLoanId = 0;
            activeLoanAmount = 0;
            persistence.setLoanId(0);
        }
        Position parked = trackedPosition;
        DonutSaleMessageParser.SaleNotice early = bufferedSaleNotice;
        openListings.add(parked);
        if (pendingIsSlow) slowIntent.add(parked.positionId());
        pendingIsSlow = false;
        trackedPosition = null;
        positionValuation = null;
        boundListingKey = "";
        bufferedSaleNotice = null;
        saleNoticeObservedAtMillis = 0;
        clearCoverageEvidence();
        if (early != null && saleMatcher.match(parked, early).matched()) {
            closeOpenListing(parked, early, now);
        }
        reconciliationStatus = openListings.size() + " listing(s) open; buying continues";
        // The configured cooldown is the minimum; the actual pause varies up
        // to two and a half times it so listings do not land on a fixed beat.
        cooldownUntilMillis = Math.addExact(now,
                Math.round(policy.cooldownMillis() * (1.0 + 1.5 * Math.random())));
        listedSinceAudit++;
        dev.doughbay.fabric.DoughBayClient.alertListed(
                baseItemId(parked.itemKey()).replace("minecraft:", "").replace('_', ' '),
                parked.quantity(), parked.targetPrice());
        transition(State.COOLDOWN, "Listing parked (" + openListings.size() + " open); cooldown before the next buy");
    }

    /**
     * The server's own sale notice is authoritative for a parked listing:
     * it names the item, stack, and price, and money has already moved.
     */
    private void closeOpenListing(Position listed, DonutSaleMessageParser.SaleNotice notice, long now) {
        // The notice prints an abbreviated price; the listing price is exact.
        long salePrice = listed.targetPrice() > 0 ? listed.targetPrice() : notice.price();
        double profit = auctionFees.netSale(salePrice) - listed.purchasePrice();
        Position sold = listed.closed(PositionStatus.SOLD, now, salePrice, profit);
        salesSinceAudit += salePrice;
        recordSaleTime(System.currentTimeMillis(), salePrice);
        notifySaleObserver(sold, notice.buyer());
        openListings.removeIf(p -> p.positionId() == listed.positionId());
        // The session spend cap bounds money out at once, not turnover: a
        // sale returns its purchase price to the budget so buying continues.
        committedSpend = Math.max(0, committedSpend - listed.purchasePrice());
        persistence.submit(checkpoint(sessionOpen, state.name(), sold,
                uncertainExposure, "Parked listing sold: " + salePrice));
        LOGGER.info("DoughBay automation: parked listing #{} ({} x{}) sold for {} (profit {}); {} still open",
                listed.positionId(), listed.itemKey(), listed.quantity(), salePrice,
                Math.round(profit), openListings.size());
        reconciliationStatus = openListings.isEmpty()
                ? "All parked listings sold"
                : openListings.size() + " listing(s) still open";
    }

    /** Exposure that still needs the audited recovery: not parked listings. */
    private boolean recoveredExposureRemains() {
        return uncertainExposure.active()
                || !recoveredOpenPositions.isEmpty()
                || (trackedPosition != null && !trackedPosition.status().isClosed());
    }

    private boolean hasFinancialExposure() {
        return uncertainExposure.active()
                || !openListings.isEmpty()
                || !recoveredOpenPositions.isEmpty()
                || (pendingOpportunity != null
                && (state == State.BUYING
                || (state == State.PAUSED && pausedFromState == State.BUYING)))
                || (trackedPosition != null && !trackedPosition.status().isClosed());
    }

    private boolean sessionLimitReached() {
        // Spend at the cap is not the end of the session: it is money out
        // in parked listings, and sales bring it back. Only the trade count
        // ends a session.
        return tradesStarted >= policy.maxTradesPerSession();
    }

    private boolean isRunning() {
        return state != State.STOPPED && state != State.PAUSED;
    }

    /** What the session has been doing, newest last; read by the Discord status page. */
    public record Action(long at, String state, String detail) {
    }

    private final java.util.ArrayDeque<Action> recentActions = new java.util.ArrayDeque<>();

    /** The last few things it did, oldest first. */
    public synchronized java.util.List<Action> recentActions() {
        return java.util.List.copyOf(recentActions);
    }

    /**
     * Everything it is lined up to do, in order.
     *
     * <p>The queue is already there and already decided; showing it is the
     * difference between "it says SCANNING" and "it is about to place these
     * eleven bids". A stall is obvious when the same eleven are still there
     * twenty minutes later.
     */
    public synchronized java.util.List<String> plannedWork() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (pendingOpportunity != null && pendingOpportunity.listing() != null) {
            out.add("buy " + shortId(pendingOpportunity.listing().itemId())
                    + " at " + pendingOpportunity.buyPrice());
        }
        if (trackedPosition != null) {
            out.add("list " + shortId(trackedPosition.itemKey()) + " x" + trackedPosition.quantity()
                    + " at " + trackedPosition.targetPrice());
        }
        if (repricing != null) out.add("reprice " + shortId(repricing.itemKey()));
        for (DeskStep step : deskQueue) {
            out.add(describe(step));
            if (out.size() >= 24) break;
        }
        return out;
    }

    private static String describe(DeskStep step) {
        return switch (step.action()) {
            case READ -> "read the order house";
            case PLACE -> "bid " + step.unitPrice() + " each for " + step.count() + " " + shortId(step.itemId());
            case COLLECT -> "collect " + step.count() + " " + shortId(step.itemId());
            case CANCEL -> "cancel the bid on " + shortId(step.itemId());
        };
    }

    /**
     * What it is about to do: the next step the bid desk has queued, or the
     * market it is lining up to buy. Empty when it is between jobs.
     */
    public synchronized String nextIntent() {
        DeskStep head = deskQueue.peekFirst();
        if (head != null) {
            return switch (head.action()) {
                case READ -> "read the order house";
                case PLACE -> "bid " + head.unitPrice() + " each for " + head.count() + " " + shortId(head.itemId());
                case COLLECT -> "collect " + head.count() + " " + shortId(head.itemId());
                case CANCEL -> "cancel the bid on " + shortId(head.itemId()) + " (" + head.why() + ")";
            };
        }
        if (pendingOpportunity != null && pendingOpportunity.listing() != null) {
            return "buy " + shortId(pendingOpportunity.listing().itemId())
                    + " at " + pendingOpportunity.buyPrice();
        }
        if (trackedPosition != null) {
            return "list " + shortId(trackedPosition.itemKey()) + " x" + trackedPosition.quantity()
                    + " at " + trackedPosition.targetPrice();
        }
        if (repricing != null) return "reprice " + shortId(repricing.itemKey());
        return "";
    }

    private static String shortId(String id) {
        if (id == null) return "";
        int colon = id.indexOf(':');
        String out = colon >= 0 ? id.substring(colon + 1) : id;
        int hash = out.indexOf('#');
        return hash >= 0 ? out.substring(0, hash) : out;
    }

    private void transition(State next, String message) {
        accrueActiveRuntime();
        // A running record of what it did, so the question "what has it been
        // doing?" has an answer that is not a log file. Only a real change is
        // kept: the same state re-entered with the same detail is the machine
        // turning over, not news.
        String note = message == null ? "" : message;
        Action last = recentActions.peekLast();
        boolean same = last != null && last.state().equals(next.name()) && last.detail().equals(note);
        if (!same) {
            recentActions.addLast(new Action(System.currentTimeMillis(), next.name(), note));
            while (recentActions.size() > 40) recentActions.pollFirst();
        }
        state = next;
        detail = message == null ? "" : message;
        stateChangedAtMillis = System.currentTimeMillis();
        activeStateStartedNanos = activeRuntimeState(next)
                ? System.nanoTime() : 0;
        if (next != State.PAUSED) {
            pausedFromState = State.STOPPED;
            pauseResumable = false;
        }
    }

    /**
     * Active time uses a monotonic source. Persisted wall timestamps are
     * labels only and are never subtracted, so a future clock or an offline
     * crash/restart interval cannot inflate runtime.
     */
    private void accrueActiveRuntime() {
        if (!activeRuntimeState(state) || activeStateStartedNanos == 0) return;
        long now = System.nanoTime();
        long elapsed = now - activeStateStartedNanos;
        activeStateStartedNanos = now;
        if (elapsed <= 0) return;
        long withRemainder = elapsed > Long.MAX_VALUE - activeSubMillisNanos
                ? Long.MAX_VALUE : elapsed + activeSubMillisNanos;
        long millis = withRemainder / NANOS_PER_MILLI;
        activeSubMillisNanos = withRemainder % NANOS_PER_MILLI;
        sessionActiveMillis = saturatedRuntimeAdd(sessionActiveMillis, millis);
        lifetimeActiveMillis = saturatedRuntimeAdd(lifetimeActiveMillis, millis);
    }

    private long currentSessionActiveMillis() {
        return saturatedRuntimeAdd(sessionActiveMillis, pendingActiveMillis());
    }

    private long currentLifetimeActiveMillis() {
        return saturatedRuntimeAdd(lifetimeActiveMillis, pendingActiveMillis());
    }

    private long pendingActiveMillis() {
        if (!activeRuntimeState(state) || activeStateStartedNanos == 0) return 0;
        long elapsed = System.nanoTime() - activeStateStartedNanos;
        if (elapsed <= 0) return 0;
        long withRemainder = elapsed > Long.MAX_VALUE - activeSubMillisNanos
                ? Long.MAX_VALUE : elapsed + activeSubMillisNanos;
        return withRemainder / NANOS_PER_MILLI;
    }

    /**
     * Puts the committed-spend counter back in step with the book it describes.
     *
     * <p>The counter is added to on a purchase and taken from when the stack is
     * listed or the position closes, across seven separate paths. Miss one and
     * the money is never given back: it drifted to twenty-five million against
     * three hundred thousand of real stock this afternoon, and to nineteen
     * million two hours after being cleared by hand. Once it passes the
     * outstanding cap the session refuses every purchase, scans finding nothing
     * it may act on, and looks for all the world like a quiet market.
     *
     * <p>The truth is always at hand - it is what the open positions cost - so
     * rather than hunt the leaking path, the counter is checked against it and
     * corrected. Only while nothing is in flight, and only when the gap is far
     * too large to be a purchase mid-air, so a real commitment is never
     * discarded.
     */
    private void reconcileCommittedSpend(long now) {
        if (!sessionOpen || now - lastSpendReconcileAt < 60_000L) return;
        lastSpendReconcileAt = now;
        if (trackedPosition != null || pendingOpportunity != null || !recoveredQueue.isEmpty()) return;
        if (driver.operationIntent() != AutomatedExecutionDriver.OperationIntent.NONE) return;
        long real = parkedSpend();
        long slack = Math.max(2_000_000L, effectivePurchaseCap());
        if (committedSpend <= real + slack) return;
        LOGGER.warn("DoughBay automation: committed spend says {} but the open book cost {}; "
                + "correcting it. Left alone this refuses every purchase once it passes the outstanding cap",
                committedSpend, real);
        committedSpend = real;
    }

    private long lastSpendReconcileAt;

    private void queueRuntimeHeartbeat() {
        reconcileCommittedSpend(System.currentTimeMillis());
        if (!sessionOpen || !activeRuntimeState(state) || persistence == null
                || !persistence.status().ready()
                || runtimePersistenceGeneration > 0) return;
        AutomationPersistencePort.Status status = persistence.status();
        if (status.lastAcceptedGeneration() > status.lastDurableGeneration()) return;
        long now = System.nanoTime();
        if (lastRuntimeHeartbeatNanos != 0
                && now - lastRuntimeHeartbeatNanos < RUNTIME_HEARTBEAT_NANOS) return;
        long generation = persistence.submit(checkpoint(
                true, state.name(), trackedPosition, uncertainExposure,
                "Periodic active-runtime checkpoint"));
        if (generation > 0) {
            runtimePersistenceGeneration = generation;
            lastRuntimeHeartbeatNanos = now;
        }
    }

    private static boolean activeRuntimeState(State candidate) {
        return candidate == State.SCANNING
                || candidate == State.BUYING
                || candidate == State.PREPARING_LIST
                || candidate == State.LISTING
                || candidate == State.MONITORING
                || candidate == State.COOLDOWN;
    }

    private static long saturatedRuntimeAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private String authorizationBlocker(Minecraft client) {
        if (!continuousAuthorizationEnabled) {
            return "Continuous automation is disabled in config";
        }
        if (!auctionFeesConfirmed) {
            return "Auction fees/taxes are not explicitly confirmed in config";
        }
        String driverBlocker = driver.currentEnvironmentBlocker();
        if (driverBlocker != null) return driverBlocker;
        String server = currentServerIdentity(client);
        if (server == null || !continuousAuthorizedServers.contains(server)) {
            return server == null
                    ? "Current server identity is unavailable; continuous automation is locked"
                    : "Server " + server + " is not in continuousAutomationServers";
        }
        if (!saleNotificationTrustedServers.contains(server)) {
            return "Server " + server
                    + " is not trusted for strict sale notifications";
        }
        return null;
    }

    private static String currentServerIdentity(Minecraft client) {
        if (client == null || client.player == null || client.getConnection() == null) return null;
        if (client.isLocalServer() || client.hasSingleplayerServer()) return "singleplayer";
        var server = client.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) return null;
        return server.ip.strip().toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizeServers(List<String> servers) {
        if (servers == null) return Set.of();
        Set<String> normalized = new LinkedHashSet<>();
        for (String server : new ArrayList<>(servers)) {
            if (server != null && !server.isBlank()) {
                normalized.add(server.strip().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(normalized);
    }

    private static boolean validFees(FeeConfig fees) {
        return fees != null && fees.listingFeeFlat() >= 0
                && Double.isFinite(fees.listingFeePercent())
                && Double.isFinite(fees.saleTaxPercent())
                && fees.listingFeePercent() >= 0 && fees.saleTaxPercent() >= 0
                && fees.listingFeePercent() <= 100 && fees.saleTaxPercent() <= 100
                && fees.listingFeePercent() + fees.saleTaxPercent() < 100;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String baseItemId(String itemKey) {
        if (itemKey == null) return "";
        int hash = itemKey.indexOf('#');
        return hash < 0 ? itemKey : itemKey.substring(0, hash);
    }

    public record CoverageRequest(String itemId, long scanMustStartAfterMillis) {
        public CoverageRequest {
            itemId = itemId == null ? "" : itemId;
            if (scanMustStartAfterMillis < 0) {
                throw new IllegalArgumentException("coverage boundary cannot be negative");
            }
            if (itemId.isBlank() && scanMustStartAfterMillis != 0) {
                throw new IllegalArgumentException("empty coverage request cannot have a boundary");
            }
        }

        public static CoverageRequest none() {
            return new CoverageRequest("", 0);
        }

        public boolean requested() {
            return !itemId.isBlank() && scanMustStartAfterMillis > 0;
        }
    }

    public record SessionSnapshot(
            State state,
            RunMode runMode,
            String detail,
            Position position,
            String pendingListingKey,
            String boundListingKey,
            String reconciliationStatus,
            int tradesStarted,
            long committedSpend,
            int attemptedListings,
            long cooldownUntilMillis,
            long updatedAtMillis,
            boolean unresolvedExposure,
            boolean resumable,
            String requestedCoverageItemId,
            String persistenceStatus,
            boolean recoveredSession,
            int recoveredExposureCount,
            String manualResolutionStage,
            long currentSessionActiveMillis,
            long lifetimeActiveMillis,
            int openListings
    ) {
        public SessionSnapshot {
            state = state == null ? State.STOPPED : state;
            runMode = runMode == null ? RunMode.SINGLE : runMode;
            detail = detail == null ? "" : detail;
            pendingListingKey = pendingListingKey == null ? "" : pendingListingKey;
            boundListingKey = boundListingKey == null ? "" : boundListingKey;
            reconciliationStatus = reconciliationStatus == null ? "" : reconciliationStatus;
            requestedCoverageItemId = requestedCoverageItemId == null
                    ? "" : requestedCoverageItemId;
            persistenceStatus = persistenceStatus == null ? "" : persistenceStatus;
            manualResolutionStage = manualResolutionStage == null
                    ? "NONE" : manualResolutionStage;
            if (recoveredExposureCount < 0) {
                throw new IllegalArgumentException("recovered exposure count cannot be negative");
            }
            if (currentSessionActiveMillis < 0 || lifetimeActiveMillis < 0
                    || lifetimeActiveMillis < currentSessionActiveMillis) {
                throw new IllegalArgumentException("invalid automation runtime snapshot");
            }
        }

        public boolean active() {
            return state != State.STOPPED && state != State.PAUSED;
        }
    }
}
