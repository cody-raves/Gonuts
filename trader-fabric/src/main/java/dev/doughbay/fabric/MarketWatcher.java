package dev.doughbay.fabric;

import dev.doughbay.api.DonutApiClient;
import dev.doughbay.api.DonutApiConfig;
import dev.doughbay.core.analysis.AnalyzerConfig;
import dev.doughbay.core.analysis.CommodityRegistry;
import dev.doughbay.core.analysis.OpportunityDetector;
import dev.doughbay.core.analysis.RiskConfig;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.NamespacedId;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.engine.MarketService;
import dev.doughbay.storage.Database;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Runs collection and analysis on a background daemon thread so the game
 * thread is never blocked by network or SQLite work. Strictly read-only with
 * respect to the game: it talks to the auction web API and the local database,
 * and never sends packets, chat, or clicks.
 */
public final class MarketWatcher {

    private static final long POLL_INTERVAL_MILLIS = 30_000;
    private static final int BOOTSTRAP_PAGES = 10;
    /**
     * Transaction pages fetched per poll once history is healthy. Two pages is
     * 200 of the newest sales, comfortably more than a 30-second window on a
     * normal day.
     */
    private static final int STEADY_HISTORY_PAGES = 2;
    /**
     * The API's own ceiling: "Page (Max 10, Min 1, 100 items per page)".
     * Asking for page 11 answers HTTP 500, so this is a hard limit, not a
     * budget choice.
     */
    private static final int MAX_HISTORY_PAGES = 10;
    /**
     * The community proxy has no page-10 limit: it serves the whole history
     * table it has ingested, so the walk can go deeper. When the market's
     * volume outruns ten pages between polls - a tripling of sales in
     * September put a ten-page window under seventy seconds against
     * hundred-second sweeps, so every poll fell short of its last sale and
     * logged "no overlap" for ever - the deeper walk finds its overlap again.
     * Only used when talking to the proxy; Donut still gets ten.
     */
    private static final int PROXY_MAX_HISTORY_PAGES = 30;

    /**
     * The proxy serves the full history table without Donut's page-10 ceiling.
     * Anything that is not Donut's own API is the proxy (the IP or the domain).
     */
    static boolean usingProxy(String baseUrl) {
        return baseUrl != null && !baseUrl.contains("donutsmp.net");
    }
    /**
     * Completed sales a market needs before it earns a row. Display is not
     * trading: the confidence column already says how much to trust a thin
     * market, so this only filters out rows too sparse to mean anything.
     */
    private static final int MARKET_DISPLAY_MINIMUM_SALES = 3;
    /** How often the scanned commodity set is re-chosen from history. */
    private static final long SELECTION_INTERVAL_MILLIS = 15L * 60_000L;
    /**
     * How often the account balance is refreshed. Balances move slowly next to
     * market data, and this is one request against the same budget, so it does
     * not belong on the 30-second sweep.
     */
    private static final long BALANCE_INTERVAL_MILLIS = 5L * 60_000L;
    private static final int EXACT_COVERAGE_MAX_PAGES = 10;
    /**
     * An incomplete coverage result for the same request is not rescanned
     * sooner than this. Controllers ask on every client tick; without a floor
     * a book that cannot complete (page cap, transport failure) would be
     * rescanned continuously against the API rate limit.
     */
    static final long INCOMPLETE_COVERAGE_RETRY_MILLIS = 5_000;
    // This is the display window only.  Valuation continues to use the core
    // analyzer's longer completed-sale history; the detail chart promises a
    // literal "LAST 12H" and therefore receives exactly that slice.
    private static final long HISTORY_WINDOW_MILLIS = 12L * 3_600_000L;
    private static final int MAX_HISTORY_POINTS = 180;
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    /**
     * A stable order makes both duplicate resolution and render-thread
     * snapshots independent of map/page iteration order. For a repeated,
     * nonblank listing key, the newest observation sorts first and wins.
     */
    private static final Comparator<Listing> ACTIVE_LISTING_ORDER = Comparator
            .comparingInt((Listing listing) -> normalizedListingKey(listing).isBlank() ? 1 : 0)
            .thenComparing(MarketWatcher::normalizedListingKey)
            .thenComparing(Comparator.comparingLong(Listing::observedAt).reversed())
            .thenComparing(listing -> safe(listing.itemKey()))
            .thenComparing(listing -> safe(listing.itemId()))
            .thenComparingInt(Listing::itemCount)
            .thenComparingLong(Listing::totalPrice)
            .thenComparing(listing -> safe(listing.sellerUuid()))
            .thenComparing(listing -> safe(listing.sellerName()))
            .thenComparing(Listing::timeLeftMillis,
                    Comparator.nullsLast(Comparator.naturalOrder()));

    // Replaced only by applyConfig, which fully stops the daemon first. The
    // read from the daemon thread still needs the volatile publish.
    private volatile DoughBayConfig config;
    // The markets the daemon is currently scanning. Exact coverage can only be
    // proven for one of these, and auto-selection changes the set at runtime.
    private volatile Set<String> scannedItemIds = Set.of();
    private final AtomicReference<Snapshot> latest =
            new AtomicReference<>(Snapshot.idle("Not started"));
    private final Object workMonitor = new Object();
    // Guarded by workMonitor. A request only records intent; all HTTP and
    // SQLite work remains on the watcher daemon thread.
    private CoverageRequest pendingCoverageRequest;
    private CoverageRequest inFlightCoverageRequest;
    private long coverageRequestSequence;
    private volatile boolean running;
    private Thread thread;
    /** Last logged rejection mix, so a steady state is not repeated. */
    private String lastRejectionSummary = "";

    /** Health counters shown on the diagnostics tab. */
    /**
     * @param historyPagesPerPoll transaction pages currently fetched each poll
     * @param historyGapsSuspected polls that returned no overlap at all with
     *        stored history. Each one means completed sales probably scrolled
     *        past the fetch window and are gone: the API serves only the most
     *        recent 1,000, so a missed sale can never be recovered.
     */
    public record Diagnostics(long transactions, long markets, long outliers,
                              long apiRequests, long apiErrors,
                              int requestsLastMinute, int requestBudget,
                              int parseFailures, String apiKeyState,
                              String apiConnectionState,
                              int historyPagesPerPoll, long historyGapsSuspected,
                              long apiCooldownRemainingMillis,
                              long apiLatencyMillis,
                              int apiLastStatusCode,
                              int apiConsecutiveFailures) {
        static Diagnostics empty(String apiKeyState) {
            return new Diagnostics(0, 0, 0, 0, 0, 0, 0, 0, apiKeyState,
                    "idle", STEADY_HISTORY_PAGES, 0, 0, -1, 0, 0);
        }
    }

    /**
     * Immutable exhaustive order-book evidence for exactly one commodity.
     * An incomplete value is informational only and can never prove absence.
     */
    public record ExactListingCoverage(String itemId, List<Listing> listings,
                                       long scanStartedAt, long completedAt,
                                       boolean complete, int parseFailures,
                                       int indistinguishableRows,
                                       int pagesScanned, String detail) {
        public ExactListingCoverage {
            itemId = itemId == null || itemId.isBlank()
                    ? ""
                    : NamespacedId.normalize(itemId);
            listings = List.copyOf(listings == null ? List.of() : listings);
            detail = detail == null ? "" : detail;
            if (scanStartedAt < 0 || completedAt < 0 || parseFailures < 0
                    || indistinguishableRows < 0 || pagesScanned < 0) {
                throw new IllegalArgumentException("coverage values cannot be negative");
            }
            if (completedAt > 0 && completedAt < scanStartedAt) {
                throw new IllegalArgumentException("coverage completes before it starts");
            }
            if (complete && (itemId.isBlank() || scanStartedAt <= 0
                    || completedAt < scanStartedAt || parseFailures != 0
                    || pagesScanned <= 0)) {
                throw new IllegalArgumentException(
                        "complete coverage requires clean, timestamped page evidence");
            }
        }

        public boolean isFreshFor(String requestedItemId, long mustStartAfterMillis) {
            if (!complete || requestedItemId == null || requestedItemId.isBlank()) return false;
            try {
                return itemId.equals(NamespacedId.normalize(requestedItemId))
                        && scanStartedAt > Math.max(0, mustStartAfterMillis);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }

        /**
         * True while an incomplete result for this very request is too recent
         * to rescan. A pending placeholder never completed, so it never applies.
         */
        boolean retryTooSoonFor(String requestedItemId, long mustStartAfterMillis, long now) {
            if (complete || completedAt <= 0
                    || requestedItemId == null || requestedItemId.isBlank()) return false;
            try {
                return itemId.equals(NamespacedId.normalize(requestedItemId))
                        && scanStartedAt > Math.max(0, mustStartAfterMillis)
                        && now - completedAt < INCOMPLETE_COVERAGE_RETRY_MILLIS;
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }

        /** Exact own rows within this targeted coverage result. */
        public List<Listing> exactOwnListings(String sellerUuid, String sellerName,
                                              String itemKey, String itemId,
                                              int itemCount, long totalPrice) {
            return findExactOwnListings(listings, sellerUuid, sellerName,
                    itemKey, itemId, itemCount, totalPrice);
        }

        static ExactListingCoverage unavailable(String detail) {
            return new ExactListingCoverage("", List.of(), 0, 0,
                    false, 0, 0, 0, detail);
        }

        static ExactListingCoverage pending(String itemId, long mustStartAfterMillis) {
            return new ExactListingCoverage(itemId, List.of(), 0, 0,
                    false, 0, 0, 0,
                    "Queued exact active-listing scan after "
                            + Math.max(0, mustStartAfterMillis));
        }

        ExactListingCoverage stale(String reason) {
            if (itemId.isBlank()) return this;
            return new ExactListingCoverage(itemId, listings, scanStartedAt, completedAt,
                    false, parseFailures, indistinguishableRows, pagesScanned,
                    "STALE: " + reason + "; prior result: " + detail);
        }
    }

    /**
     * Immutable view the render thread reads without locking.
     *
     * <p>{@code demo} marks invented sample rows shown before an API key
     * exists. Anything rendering a demo snapshot must label it as such.
     */
    /**
     * @param accountName the signed-in player the balance belongs to, or ""
     * @param accountBalance the player's coins, or -1 when unknown. Unknown is
     *        a real and common state — no username, a failed request, an API
     *        that does not know the player — and must not read as zero.
     */
    public record Snapshot(List<Opportunity> opportunities,
                           List<MarketService.NearMiss> nearMisses,
                           List<MarketStats> markets,
                           List<Listing> activeListings,
                           Map<String, List<Charts.Point>> history,
                           Map<String, VolumeWindow> volume,
                           ExactListingCoverage exactListingCoverage,
                           String accountName, long accountBalance,
                           String status, Diagnostics diagnostics,
                           long activeListingScanStartedAt,
                           long updatedAt, boolean demo) {
        public Snapshot {
            accountName = accountName == null ? "" : accountName;
            opportunities = List.copyOf(opportunities);
            nearMisses = List.copyOf(nearMisses);
            markets = List.copyOf(markets);
            activeListings = List.copyOf(activeListings);
            history = immutableHistory(history);
            volume = Map.copyOf(volume);
            exactListingCoverage = exactListingCoverage == null
                    ? ExactListingCoverage.unavailable("Unavailable")
                    : exactListingCoverage;
        }

        static Snapshot idle(String status) {
            return new Snapshot(List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(),
                    ExactListingCoverage.unavailable("Unavailable while idle"), "", -1, status,
                    Diagnostics.empty("unknown"), 0, 0, false);
        }

        static Snapshot demo(long now) {
            List<Opportunity> opportunities = DemoData.opportunities(now);
            ChartData charts = chartData(DemoData.history(now), now);
            return new Snapshot(opportunities, List.of(), DemoData.markets(now),
                    opportunities.stream().map(Opportunity::listing).toList(),
                    charts.history(), charts.volume(),
                    ExactListingCoverage.unavailable("Unavailable in demo mode"), "", -1,
                    "DEMO DATA — no API key, these numbers are invented",
                    Diagnostics.empty("missing"), now, now, true);
        }

        /**
         * Finds active listings owned by the supplied player that are exactly
         * indistinguishable from one proposed listing in a sale action-bar
         * notice. At least one valid seller identity is required. When both
         * UUID and name are present on both sides, either mismatch rejects the
         * row rather than letting the other field override contradictory data.
         */
        public List<Listing> exactOwnListings(String sellerUuid, String sellerName,
                                              String itemKey, String itemId,
                                              int itemCount, long totalPrice) {
            return exactListingCoverage.exactOwnListings(sellerUuid, sellerName,
                    itemKey, itemId, itemCount, totalPrice);
        }

        /** Price history for a market, oldest first; empty when unknown. */
        public List<Charts.Point> historyFor(String itemKey) {
            return history.getOrDefault(itemKey, List.of());
        }

        /** Exact completed-sale counts for each 30-minute bucket in the display window. */
        public VolumeWindow volumeFor(String itemKey) {
            return volume.getOrDefault(itemKey,
                    VolumeWindow.empty(updatedAt, HISTORY_WINDOW_MILLIS));
        }

        private static Map<String, List<Charts.Point>> immutableHistory(
                Map<String, List<Charts.Point>> source) {
            Map<String, List<Charts.Point>> copy = new HashMap<>();
            source.forEach((key, points) -> copy.put(key, List.copyOf(points)));
            return Map.copyOf(copy);
        }
    }

    /**
     * Immutable count series for one fixed chart window. The list form keeps
     * callers from mutating the shared render-thread snapshot through an
     * exposed primitive array.
     */
    public record VolumeWindow(long windowEnd, long windowSpan, List<Integer> counts) {
        private static final int BUCKET_COUNT = 24;

        public VolumeWindow {
            if (windowSpan <= 0) throw new IllegalArgumentException("windowSpan must be positive");
            counts = List.copyOf(counts);
            if (counts.size() != BUCKET_COUNT) {
                throw new IllegalArgumentException("volume must contain 24 half-hour buckets");
            }
        }

        static VolumeWindow empty(long windowEnd, long windowSpan) {
            return new VolumeWindow(windowEnd, windowSpan,
                    java.util.Collections.nCopies(BUCKET_COUNT, 0));
        }

        /** Defensive primitive copy for the chart renderer. */
        public int[] copyCounts() {
            int[] copy = new int[counts.size()];
            for (int i = 0; i < counts.size(); i++) copy[i] = counts.get(i);
            return copy;
        }
    }

    public MarketWatcher(DoughBayConfig config) {
        this.config = config;
    }

    public Snapshot snapshot() {
        return latest.get();
    }

    public DoughBayConfig config() {
        return config;
    }

    /**
     * Restarts collection against a new configuration, which today only ever
     * differs by API key (see {@link DoughBayConfig#withApiKey(String)}).
     *
     * <p>The running daemon is fully stopped and joined before the swap, so
     * two threads never share one database handle, and any in-flight coverage
     * request is discarded rather than published against the new key.
     */
    public synchronized void applyConfig(DoughBayConfig updated) {
        stop();
        this.config = updated;
        latest.set(Snapshot.idle("Not started"));
        if (updated.collectionEnabled()) {
            start();
        }
    }

    /**
     * Requests exhaustive active-listing coverage for one exact commodity.
     * This method is safe on the Minecraft client thread: it only queues work
     * and wakes the watcher daemon. Network and database access happen later
     * on that daemon.
     *
     * @return {@code true} when fresh evidence already exists or the request
     *         was accepted; {@code false} for an invalid/untracked item or a
     *         watcher that is not running
     */
    public boolean requestExactListingCoverage(String itemId,
                                               long scanMustStartAfterMillis) {
        final String normalizedItemId;
        try {
            normalizedItemId = NamespacedId.normalize(itemId);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        // Checked against the live scanned set, not the default registry:
        // auto-selection tracks markets the defaults never listed, and a
        // candidate from one of those was refused here without a word.
        String refusal = !running ? "watcher is not running"
                : !config.hasApiKey() ? "no API key"
                : !scannedItemIds.contains(normalizedItemId)
                ? normalizedItemId + " is not among the scanned markets"
                : null;
        if (refusal != null) {
            publishRefusedCoverage("Exact scan refused: " + refusal);
            return false;
        }

        long boundary = Math.max(0, scanMustStartAfterMillis);
        synchronized (workMonitor) {
            if (!running) return false;
            ExactListingCoverage current = latest.get().exactListingCoverage();
            if (current.isFreshFor(normalizedItemId, boundary)) return true;
            if (current.retryTooSoonFor(normalizedItemId, boundary,
                    System.currentTimeMillis())) {
                return true;
            }

            CoverageRequest existing = pendingCoverageRequest != null
                    ? pendingCoverageRequest : inFlightCoverageRequest;
            // Controllers may ask on every client tick while waiting. An
            // identical or weaker request is already satisfied by the same
            // pending/in-flight scan and must not bump the generation, or the
            // scan could be invalidated forever.
            if (existing != null
                    && existing.itemId().equals(normalizedItemId)
                    && existing.scanMustStartAfterMillis() >= boundary) {
                return true;
            }
            long coalescedBoundary = existing != null
                    && existing.itemId().equals(normalizedItemId)
                    ? Math.max(existing.scanMustStartAfterMillis(), boundary)
                    : boundary;
            long sequence = ++coverageRequestSequence;
            pendingCoverageRequest = new CoverageRequest(
                    normalizedItemId, coalescedBoundary, sequence);
            latest.updateAndGet(snapshot -> copyWithCoverage(snapshot,
                    ExactListingCoverage.pending(normalizedItemId, coalescedBoundary)));
            workMonitor.notifyAll();
            return true;
        }
    }

    /** Reuses any already-complete coverage for this item, otherwise queues it. */
    /**
     * Shows a refused request on the snapshot. A silently refused request
     * left the last placeholder on screen with nothing to say what was wrong.
     */
    private void publishRefusedCoverage(String detail) {
        synchronized (workMonitor) {
            if (pendingCoverageRequest != null || inFlightCoverageRequest != null) return;
            latest.updateAndGet(snapshot ->
                    snapshot.exactListingCoverage().detail().equals(detail)
                            ? snapshot
                            : copyWithCoverage(snapshot,
                            ExactListingCoverage.unavailable(detail)));
        }
    }

    public boolean requestExactListingCoverage(String itemId) {
        return requestExactListingCoverage(itemId, 0);
    }

    /**
     * Cancels queued/in-flight publication and removes any prior evidence.
     * An HTTP call already in progress cannot be unsent, but its result is
     * generation-checked and therefore cannot republish after this returns.
     */
    public void clearExactListingCoverage() {
        synchronized (workMonitor) {
            coverageRequestSequence++;
            pendingCoverageRequest = null;
            inFlightCoverageRequest = null;
            latest.updateAndGet(snapshot -> copyWithCoverage(snapshot,
                    ExactListingCoverage.unavailable("Exact listing coverage cleared")));
            workMonitor.notifyAll();
        }
    }

    /**
     * Whether this poll probably missed sales.
     *
     * <p>Consecutive polls overlap heavily: the window holds the newest sales,
     * so almost everything in it was already stored last time. Overlap of zero
     * therefore means the window no longer reaches back to the previous poll,
     * and whatever fell between them is unrecoverable — the API serves only
     * the most recent 1,000 sales and offers no way to page further back.
     *
     * <p>Requires a non-empty page: a quiet market that returns nothing has
     * lost nothing.
     */
    static boolean suspectsHistoryGap(MarketService.CollectReport report) {
        return report.recordsSeen() > 0 && report.duplicates() == 0;
    }

    /**
     * The window for the next poll. Doubles when a gap is suspected, up to the
     * API's page ceiling, and otherwise eases back one page at a time toward
     * the cheap steady state.
     *
     * <p>Widening fast and narrowing slowly is deliberate. Missing a sale is
     * permanent, while an extra page costs one request against a budget that
     * currently runs near a tenth of its limit.
     */
    static int nextHistoryPages(int current, MarketService.CollectReport report) {
        return nextHistoryPages(current, report, MAX_HISTORY_PAGES);
    }

    static int nextHistoryPages(int current, MarketService.CollectReport report, int maxPages) {
        if (suspectsHistoryGap(report)) {
            return Math.min(maxPages, Math.max(current + 1, current * 2));
        }
        return Math.max(STEADY_HISTORY_PAGES, current - 1);
    }

    public synchronized void start() {
        if (running) return;
        if (!config.hasApiKey()) {
            // Show the interface populated rather than empty, so it can be
            // reviewed before a key exists. Loudly labelled as invented.
            latest.set(Snapshot.demo(System.currentTimeMillis()));
            DoughBayClient.LOGGER.info(
                    "DoughBay has no API key; showing demo data. Add one on the Automation "
                            + "tab in Observe mode, or write it to {}.",
                    config.directory().resolve("secret.dat"));
            return;
        }
        running = true;
        thread = new Thread(this::run, "doughbay-market-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        synchronized (workMonitor) {
            coverageRequestSequence++;
            pendingCoverageRequest = null;
            inFlightCoverageRequest = null;
            workMonitor.notifyAll();
        }
        Thread current = thread;
        thread = null;
        if (current != null) {
            current.interrupt();
            try {
                // Give the loop a moment to close the database cleanly rather
                // than leaving WAL side files behind on quit.
                current.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!latest.get().demo()) setStatus("STOPPED");
    }

    /**
     * Keeps the watcher alive across a failure. One thrown exception used
     * to end the thread for good: a locked ledger during a restart - two
     * clients migrating and writing at the same instant - left one client
     * trading blind for twenty-five minutes, off the hive roster and off the
     * panel, with nothing in the log after the one stack trace. A watcher is
     * infrastructure; it starts itself again, with a growing wait.
     */
    private void run() {
        int failures = 0;
        try {
            while (running) {
                try {
                    runOnce();
                    return;   // stopped on purpose
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    failures++;
                    long wait = Math.min(120_000L, 15_000L * failures);
                    DoughBayClient.LOGGER.warn("DoughBay market watcher failed ({}); starting it again in {} s",
                            e.toString(), wait / 1000);
                    setStatus("RETRYING in " + wait / 1000 + " s: " + e);
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            running = false;
        }
    }

    private void runOnce() throws Exception {
        try (Database db = new Database(config.databasePath())) {
            DonutApiConfig apiConfig = DonutApiConfig.withDefaults(config.apiBaseUrl(), config.apiKey());
            DonutApiClient client = new DonutApiClient(apiConfig);
            // One key, several clients: the ledger says how many are alive and
            // this one takes its share of the allowance.
            ApiBudget budget = new ApiBudget(config.databasePath(), apiConfig.targetRequestsPerMinute());
            DoughBayClient.setApiBudget(budget);
            // The watch list is a hot setting first, the config file second, so
            // it can be edited in game from the Items tab and take effect on the
            // next scan without a restart. itemSet drops the namespace; the
            // registry wants it back, and every id on this server is vanilla.
            java.util.Set<String> tracked;
            if (Tuning.get("watch.tracked_managed") >= 0.5) {
                // Edited in game: the hot list is the whole truth, even empty.
                tracked = new java.util.HashSet<>();
                for (String b : Tuning.itemSet("watch.tracked")) {
                    tracked.add(b.indexOf(':') >= 0 ? b : "minecraft:" + b);
                }
            } else {
                tracked = config.trackedCommodities();
            }
            CommodityRegistry registry = new CommodityRegistry(
                    pinHeldExposure(tracked), java.util.Set.of(), Tuning.itemSet("items.gear"));
            scannedItemIds = registry.trackedIds();
            MarketService service = new MarketService(
                    AnalyzerConfig.defaults(), config.auctionFees(), config.riskConfig(),
                    registry, client, db);

            setStatus("Bootstrapping transaction history...");
            MarketService.CollectReport bootstrap =
                    service.collectTransactions(1, BOOTSTRAP_PAGES);
            boolean completedHistoryHealthy = bootstrap.parseFailures() == 0;

            // Start on the set chosen last time rather than waiting to derive
            // one. Ranking every market over seven days of a twelve-gigabyte
            // ledger takes about half a minute, and it lands on nearly the same
            // answer every restart; scanning can begin against yesterday's
            // answer while today's is worked out behind it.
            java.util.List<String> remembered = lastMarketSet(config);
            if (config.autoSelectCommodities() && !remembered.isEmpty()) {
                java.util.LinkedHashSet<String> warm =
                        new java.util.LinkedHashSet<>(config.trackedCommodities());
                warm.addAll(remembered);
                service = new MarketService(AnalyzerConfig.defaults(), config.auctionFees(),
                        config.riskConfig(), new CommodityRegistry(
                                Set.copyOf(warm), java.util.Set.of(), Tuning.itemSet("items.gear")),
                        client, db);
                DoughBayClient.LOGGER.info(
                        "DoughBay is starting on the {} markets it chose last time; re-ranking in the background",
                        warm.size());
            }

            long nextFullSweepAt = 0;
            long nextSelectionAt = remembered.isEmpty() ? 0
                    : System.currentTimeMillis() + 3 * 60_000L;
            long nextBalanceAt = 0;
            String accountName = "";
            long accountBalance = -1;
            int historyPages = STEADY_HISTORY_PAGES;
            int historyPageCap = usingProxy(config.apiBaseUrl())
                    ? PROXY_MAX_HISTORY_PAGES : MAX_HISTORY_PAGES;
            long historyGapsSuspected = 0;
            while (running) {
                if (DoughBayClient.multiClient()) {
                    // The signed-in account is known from launch; the one the
                    // balance sweep learns is the same name several minutes
                    // later. Waiting for it left every client nameless in the
                    // hive at startup, which is when it matters most: nobody
                    // can be elected to host, and nobody appears in the roster.
                    String beatAs = accountName.isEmpty() ? signedInPlayerName() : accountName;
                    budget.refresh(apiConfig.targetRequestsPerMinute(), beatAs, DoughBayClient.clientMode());
                    int mine = budget.share();
                    if (DoughBayClient.collector()) {
                        // A scanner's worth is the pages it walks, which cost no
                        // requests; it leaves most of the feed to the trader.
                        mine = Math.max(20, mine * (int) Tuning.get("multi.scan_share_pct") / 100);
                    }
                    client.setRequestBudget(mine, Math.max(mine,
                            apiConfig.hardMaxRequestsPerMinute() * mine
                                    / Math.max(1, apiConfig.targetRequestsPerMinute())));
                } else {
                    client.setRequestBudget(apiConfig.targetRequestsPerMinute(),
                            apiConfig.hardMaxRequestsPerMinute());
                }
                // Exact reconciliation evidence is a separate, prompt work
                // lane. It is serviced before the periodic broad sweep and
                // published immediately, preserving all existing market/UI
                // data in the shared snapshot.
                processRequestedCoverage(service);
                if (!running) break;
                // The lookup answers only for a player who is on the server
                // ("this user is not currently online" otherwise), so it waits
                // for the join and runs a little after it.
                if (nextRankAt > 0 && System.currentTimeMillis() >= nextRankAt) {
                    boolean ok = applyRank(client, signedInPlayerName());
                    nextRankAt = System.currentTimeMillis() + (ok ? RANK_INTERVAL_MILLIS : RANK_RETRY_MILLIS);
                }
                if (!feedProbed) {
                    feedProbed = true;
                    probeListingFeed(client);
                }
                if (hasPendingCoverageRequest()) continue;

                // Re-pick which markets to scan from what history now shows.
                // Done on a slow cadence: the ranking moves over hours, and
                // swapping the scanned set every sweep would keep discarding
                // half-built active books.
                if (config.autoSelectCommodities()
                        && System.currentTimeMillis() >= nextSelectionAt) {
                    service = retargetCommodities(service, config, client, db);
                    scannedItemIds = service.trackedIds();
                    nextSelectionAt = System.currentTimeMillis() + SELECTION_INTERVAL_MILLIS;
                }

                // Somebody walking up is worth knowing about now, not at the
                // next balance sweep several minutes away; its own gap keeps
                // it from crowding the market feed.
                lookUpNearbyPlayers(client);

                if (System.currentTimeMillis() >= nextBalanceAt) {
                    accountName = signedInPlayerName();
                    accountBalance = fetchBalance(client, accountName);
                    if (accountBalance >= 0) {
                        DoughBayClient.LOGGER.info("DoughBay balance: {} ({})", accountBalance, accountName);
                        try {
                            new dev.doughbay.storage.PositionRepository(db).recordBalance(
                                    System.currentTimeMillis(), "REAL", accountBalance, 0);
                        } catch (RuntimeException | java.sql.SQLException e) {
                            DoughBayClient.LOGGER.warn("DoughBay could not record the balance: {}", e.toString());
                        }
                    }
                    nextBalanceAt = System.currentTimeMillis() + BALANCE_INTERVAL_MILLIS;
                }

                long cycleStartedAt = System.currentTimeMillis();
                if (cycleStartedAt < nextFullSweepAt) {
                    awaitWork(nextFullSweepAt);
                    continue;
                }

                long now = System.currentTimeMillis();
                MarketService.CollectReport collected = completedHistoryHealthy
                        ? service.collectTransactions(1, historyPages)
                        : service.collectTransactions(1, BOOTSTRAP_PAGES);
                completedHistoryHealthy = collected.parseFailures() == 0;
                if (suspectsHistoryGap(collected)) {
                    historyGapsSuspected++;
                    DoughBayClient.LOGGER.warn(
                            "DoughBay saw no overlap across {} transaction page(s); "
                                    + "sales may have scrolled past the window. Widening.",
                            historyPages);
                }
                historyPages = nextHistoryPages(historyPages, collected, historyPageCap);
                // This marker is deliberately captured immediately before the
                // active-listing sweep. Only evidence that predates this
                // instant can be reconciled by the completed sweep; an
                // operation begun after the marker must wait for a later scan.
                long activeListingScanStartedAt = System.currentTimeMillis();
                MarketService.ScanReport scan = service.scanCommodityListings();
                // Admission is per book, not all-or-nothing. scanCommodityListings
                // only puts a market into commodityListings() after proving that
                // book complete, so an unprovable one is already absent rather
                // than partially trusted. Gating the whole publication on every
                // book succeeding turned one deep market into a blank screen for
                // all the others, which is a coverage loss with no safety gain.
                boolean valuationHealthy = completedHistoryHealthy;

                // Sized from the allocation the player set aside, not from
                // their balance: the risk rules are percentages of committed
                // capital, and a tenth of total wealth is not a position size.
                //
                // With no allocation configured the bankroll stays nominal so
                // ranking shows everything, which is the shipped behaviour.
                long allocation = config.tradingAllocation();
                OpportunityDetector.Bankroll bankroll = allocation > 0
                        ? new OpportunityDetector.Bankroll(allocation, allocation, 0)
                        : new OpportunityDetector.Bankroll(
                                Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 0);
                MarketService.DetectionReport detection = valuationHealthy
                        ? service.detect(scan.commodityListings(), now, bankroll)
                        : new MarketService.DetectionReport(List.of(), List.of());
                List<Opportunity> found = detection.opportunities();
                logRejectionSummary(detection);
                List<MarketStats> markets = valuationHealthy
                        ? service.marketStatsFromHistory(now, MARKET_DISPLAY_MINIMUM_SALES)
                        : List.of();
                // Market signals sit alongside listing-based opportunities so
                // the tab, the alerts, and the session all see the same set.
                if (valuationHealthy) {
                    // The signal cap grows with the bankroll like the session's
                    // purchase cap does: 8% of the balance, never below config.
                    long signalCap = accountBalance > 0
                            ? Math.max(1_000, Math.round(accountBalance * Tuning.get("buy.purchase_cap_pct") / 100.0))
                            : config.continuousMaxPurchasePrice();
                    List<Opportunity> signals = service.marketSignals(markets, now, signalCap);
                    if (!signals.isEmpty()) {
                        List<Opportunity> merged = new ArrayList<>(found);
                        merged.addAll(signals);
                        found = merged;
                    }
                }
                java.util.Set<String> chartKeys = new HashSet<>();
                for (MarketStats stats : markets) chartKeys.add(stats.itemKey());
                chartKeys.addAll(scan.commodityListings().keySet());
                ChartData charts = collectChartData(service, chartKeys, now);

                DonutApiClient.ApiHealth health = client.health();
                Diagnostics diagnostics = new Diagnostics(
                        service.transactions().count(),
                        service.transactions().countDistinctMarkets(),
                        service.transactions().countOutliers(),
                        health.totalRequests(), health.totalErrors(),
                        health.requestsInLastMinute(), health.budgetPerMinute(),
                        collected.parseFailures() + scan.parseFailures(),
                        "present", health.state().name().toLowerCase(java.util.Locale.ROOT),
                        historyPages, historyGapsSuspected,
                        health.cooldownRemainingMillis(), health.lastLatencyMillis(),
                        health.lastStatusCode(), health.consecutiveFailures());

                List<Listing> activeListings = flattenActiveListings(scan.commodityListings());
                // API parsing can stamp a listing after the loop's analysis
                // timestamp. The containing snapshot is complete only here;
                // using the earlier `now` would make a fresh listing appear to
                // come from the future at the live-execution boundary.
                long completedAt = System.currentTimeMillis();
                String status;
                if (!completedHistoryHealthy) {
                    status = "DEGRADED: completed-sale validation failed; valuations locked";
                } else if (scan.incompleteItems() > 0) {
                    // Informational, not degraded: the markets below are proven.
                    status = "PARTIAL: " + scan.completeItems() + " of "
                            + scan.itemsScanned() + " active book(s) proven"
                            + (found.isEmpty() ? "; no opportunities passed the filters" : "");
                } else if (found.isEmpty()) {
                    status = "No opportunities passed the filters";
                } else {
                    status = "OK";
                }
                List<Opportunity> publishedFound = found;
                List<MarketStats> publishedMarkets = markets;
                List<MarketService.NearMiss> publishedMisses = detection.nearMisses();
                // Both are reassigned on the balance cadence, so the lambda
                // needs its own effectively-final view of them.
                String publishedName = accountName;
                long publishedBalance = accountBalance;
                latest.updateAndGet(current -> new Snapshot(publishedFound, publishedMisses,
                        publishedMarkets, activeListings,
                        charts.history(), charts.volume(), current.exactListingCoverage(),
                        publishedName, publishedBalance,
                        status,
                        diagnostics, activeListingScanStartedAt, completedAt, false));
                // How much of the key we are actually spending. Raising the
                // budget only buys anything if the sweep was hitting it, and
                // the only way to know that from outside is to say so.
                DoughBayClient.LOGGER.info(
                        "DoughBay feed sweep took {} s and spent {} of {} requests a minute"
                                + " ({} total, {} error(s))",
                        (completedAt - activeListingScanStartedAt) / 1000,
                        health.requestsInLastMinute(), health.budgetPerMinute(),
                        health.totalRequests(), health.totalErrors());
                nextFullSweepAt = completedAt + POLL_INTERVAL_MILLIS;
                awaitWork(nextFullSweepAt);
            }
        }
    }

    private void processRequestedCoverage(MarketService service) throws InterruptedException {
        CoverageRequest request;
        synchronized (workMonitor) {
            request = pendingCoverageRequest;
            pendingCoverageRequest = null;
            inFlightCoverageRequest = request;
        }
        if (request == null) return;
        if (!awaitEvidenceBoundary(request)) {
            synchronized (workMonitor) {
                if (request.equals(inFlightCoverageRequest)) {
                    inFlightCoverageRequest = null;
                }
            }
            return;
        }

        // Captured immediately before the service starts page 1. This is the
        // conservative chronology boundary consumers explicitly requested.
        long scanStartedAt = System.currentTimeMillis();
        ExactListingCoverage coverage;
        try {
            MarketService.ExactListingScanReport report =
                    service.scanExactCommodityListings(
                            request.itemId(), EXACT_COVERAGE_MAX_PAGES);
            long completedAt = System.currentTimeMillis();
            // A book is complete once every page was read cleanly. Rows the
            // parser cannot tell apart (same seller, item, count and price;
            // the API publishes no listing id) are collapsed and counted, not
            // treated as failure: execution verifies the exact price on the
            // confirmation screen, and own-row reconciliation keys on the
            // seller, so another seller's collision cannot fake an absence.
            // Gating on zero collisions made busy books unsatisfiable, which
            // re-queued the same scan on every client tick.
            coverage = new ExactListingCoverage(report.itemId(), report.listings(),
                    scanStartedAt, completedAt, report.complete(),
                    report.parseFailures(), report.ambiguousIdentities(),
                    report.pagesScanned(), report.detail());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            long completedAt = System.currentTimeMillis();
            coverage = new ExactListingCoverage(request.itemId(), List.of(),
                    scanStartedAt, completedAt, false, 0, 0, 0,
                    "Exact active-listing scan failed: "
                            + e.getClass().getSimpleName());
            DoughBayClient.LOGGER.warn(
                    "Exact active-listing coverage failed for {}",
                    request.itemId(), e);
        }

        synchronized (workMonitor) {
            if (request.equals(inFlightCoverageRequest)) {
                inFlightCoverageRequest = null;
            }
            // A clear or newer request invalidates an in-flight result. This
            // check and publication share the request lock, so a stale result
            // cannot win the race after clearExactListingCoverage returns.
            if (running && coverageRequestSequence == request.sequence()) {
                ExactListingCoverage completedCoverage = coverage;
                latest.updateAndGet(snapshot ->
                        copyWithCoverage(snapshot, completedCoverage));
            }
        }
    }

    /** Waits honestly until a scan can begin strictly after the caller's boundary. */
    private boolean awaitEvidenceBoundary(CoverageRequest request)
            throws InterruptedException {
        synchronized (workMonitor) {
            while (running && coverageRequestSequence == request.sequence()) {
                long remaining = request.scanMustStartAfterMillis()
                        - System.currentTimeMillis();
                if (remaining < 0) return true;
                workMonitor.wait(Math.min(1_000, remaining + 1));
            }
            return false;
        }
    }

    /** Sleeps until the periodic sweep is due, but wakes promptly for exact work. */
    private void awaitWork(long nextFullSweepAt) throws InterruptedException {
        synchronized (workMonitor) {
            if (!running || pendingCoverageRequest != null) return;
            long remaining = nextFullSweepAt - System.currentTimeMillis();
            if (remaining > 0) workMonitor.wait(remaining);
        }
    }

    private boolean hasPendingCoverageRequest() {
        synchronized (workMonitor) {
            return pendingCoverageRequest != null;
        }
    }

    private static Snapshot copyWithCoverage(Snapshot snapshot,
                                             ExactListingCoverage coverage) {
        return new Snapshot(snapshot.opportunities(), snapshot.nearMisses(), snapshot.markets(),
                snapshot.activeListings(), snapshot.history(), snapshot.volume(),
                coverage, snapshot.accountName(), snapshot.accountBalance(),
                snapshot.status(), snapshot.diagnostics(),
                snapshot.activeListingScanStartedAt(), snapshot.updatedAt(),
                snapshot.demo());
    }

    /**
     * Recent unit prices per market for the charts. Capped per market so the
     * snapshot the render thread reads every frame stays small.
     */
    /**
     * Rebuilds the service around the markets history says are worth scanning.
     *
     * <p>Returns the existing service untouched when the selection is empty or
     * fails: a scanner pointed at the configured set is strictly better than
     * one pointed at nothing.
     */
    /** Where the last chosen market set is kept, so a restart need not re-derive it. */
    private static java.nio.file.Path marketSetFile(DoughBayConfig config) {
        return config.directory().resolve("markets-last.txt");
    }

    /**
     * Writes the set that was just chosen.
     *
     * <p>Choosing it costs about half a minute: seven days of curves and a rank
     * over every market the ledger has ever seen, against a database now twelve
     * gigabytes deep. The answer barely moves between restarts, and paying that
     * before the first trade of every session is the bulk of a slow start.
     */
    private static void rememberMarketSet(DoughBayConfig config, java.util.Collection<String> scanned) {
        try {
            java.nio.file.Files.write(marketSetFile(config), scanned);
        } catch (Exception e) {
            DoughBayClient.LOGGER.debug("DoughBay could not remember the market set: {}", e.toString());
        }
    }

    /**
     * The set last chosen, if it was chosen recently enough to still be a fair
     * guess. Used to start scanning at once; the real ranking runs behind it and
     * replaces this on its own schedule.
     */
    private static java.util.List<String> lastMarketSet(DoughBayConfig config) {
        try {
            java.nio.file.Path file = marketSetFile(config);
            if (!java.nio.file.Files.exists(file)) return java.util.List.of();
            long age = System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(file).toMillis();
            if (age > 24 * 3_600_000L) return java.util.List.of();
            return java.nio.file.Files.readAllLines(file).stream()
                    .map(String::strip).filter(l -> !l.isEmpty()).toList();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /**
     * Markets whose recent settled trades add up to a loss, to keep out of the
     * auto-picked pool for now. Judged only on the last {@code prune.lookback_days}
     * so a market is never banned for good: an old loss ages out of the window,
     * and a dropped market - which stops trading - loses its recent trades from
     * the window until too few remain to judge it, at which point it flows back
     * in for a fresh try. Off unless {@code prune.enabled}; failure here is never
     * fatal - the worst case is scanning a market that was going to be dropped,
     * which is exactly today's behaviour.
     */
    private static java.util.Set<String> losingMarkets(Database db) {
        if (Tuning.get("prune.enabled") < 0.5) return java.util.Set.of();
        try {
            int lookbackDays = Math.max(1, (int) Math.round(Tuning.get("prune.lookback_days")));
            long since = System.currentTimeMillis() - lookbackDays * 24L * 3_600_000L;
            double floor = -Math.abs(Tuning.get("prune.min_loss"));
            int minTrades = (int) Math.round(Tuning.get("prune.min_trades"));
            java.util.List<dev.doughbay.core.performance.MarketProfit> pnl =
                    new dev.doughbay.storage.PositionRepository(db).realizedProfitByItemSince(since);
            java.util.Set<String> losers =
                    dev.doughbay.core.analysis.LosingMarketFilter.losers(pnl, minTrades, floor);
            if (!losers.isEmpty()) {
                DoughBayClient.LOGGER.info(
                        "DoughBay: pruning {} market(s) down more than {} over the last {} day(s); they get a fresh try once "
                                + "their recent trades age out: {}",
                        losers.size(), (long) Math.abs(Tuning.get("prune.min_loss")), lookbackDays,
                        String.join(", ", losers));
            }
            return losers;
        } catch (Exception e) {
            DoughBayClient.LOGGER.debug("DoughBay could not compute losing markets: {}", e.toString());
            return java.util.Set.of();
        }
    }

    /**
     * Each market's sales by hour of day over the last two weeks, for weighting
     * the current hour in selection. Empty (inert) unless {@code
     * demand.time_of_day}; a failure here simply leaves scoring as it was.
     */
    private static java.util.Map<String, int[]> hourlyDemand(Database db) {
        if (Tuning.get("demand.time_of_day") < 0.5) return java.util.Map.of();
        try {
            long since = System.currentTimeMillis() - 14L * 24 * 3_600_000L;
            return new dev.doughbay.storage.TransactionRepository(db).hourOfDaySalesByItem(since);
        } catch (Exception e) {
            DoughBayClient.LOGGER.debug("DoughBay could not compute hourly demand: {}", e.toString());
            return java.util.Map.of();
        }
    }

    private static MarketService retargetCommodities(MarketService current,
                                                     DoughBayConfig config,
                                                     DonutApiClient client,
                                                     Database db) {
        try {
            // Selection must obey the same thresholds the detector does.
            // Holding it to a stricter bar meant loosening the risk config had
            // no effect on which markets were even looked at.
            // The cap has to bound the whole scanned set, not just the auto
            // picks. Pinned markets were added on top of it, so pinning
            // fifty-eight turned a cap of ninety into a hundred and
            // twenty-nine: one sweep then takes longer than the rate limit
            // allows, the completed-sale poll is squeezed out behind it, and
            // the session refuses to trade on a stale snapshot. Pinned keep
            // priority; the auto picks get whatever room is left.
            int room = Math.max(0, config.maxScannedMarkets() - config.trackedCommodities().size());
            java.util.Set<String> losers = losingMarkets(db);
            java.util.Map<String, int[]> demand = hourlyDemand(db);
            List<String> chosen = room == 0 ? List.of() : current.selectTrackedCommodities(
                    System.currentTimeMillis(), room,
                    config.riskConfig().minimumConfidence(),
                    config.riskConfig().minimumSamples(),
                    losers, demand);
            if (room == 0) {
                DoughBayClient.LOGGER.warn(
                        "DoughBay: {} pinned markets already fill the cap of {}; no room for the auto picks",
                        config.trackedCommodities().size(), config.maxScannedMarkets());
            }
            if (chosen.isEmpty() && room > 0) {
                // Silence here read as "auto-selection is working" while it was
                // in fact finding nothing and quietly keeping the old set.
                DoughBayClient.LOGGER.warn(
                        "DoughBay found no market clearing {}% confidence with {}+ sales; "
                                + "keeping the configured set",
                        Math.round(config.riskConfig().minimumConfidence() * 100),
                        config.riskConfig().minimumSamples());
                return current;
            }
            // Markets named in the config are pinned: auto-selection fills
            // the remaining slots around them rather than replacing them.
            java.util.LinkedHashSet<String> scanned = new java.util.LinkedHashSet<>(
                    config.trackedCommodities());
            scanned.addAll(chosen);
            // A market the session is still holding stock in stays scanned no
            // matter what auto-selection picked, so the relist can always get
            // its exact-book coverage instead of deadlocking.
            java.util.Set<String> held = safeHeldExposure();
            scanned.addAll(held);
            DoughBayClient.LOGGER.info("DoughBay is scanning {} markets ({} pinned, {} auto-selected, {} held): {}",
                    scanned.size(), config.trackedCommodities().size(), chosen.size(), held.size(),
                    String.join(", ", scanned));
            rememberMarketSet(config, scanned);
            return new MarketService(AnalyzerConfig.defaults(), config.auctionFees(),
                    config.riskConfig(), new CommodityRegistry(
                            Set.copyOf(scanned), java.util.Set.of(), Tuning.itemSet("items.gear")),
                    client, db);
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn(
                    "DoughBay could not re-select markets; keeping the configured set: {}",
                    e.toString());
            return current;
        }
    }

    /**
     * Base markets the automation session is holding an unlisted position in.
     * Never throws: the session lock or a half-built state must not take the
     * whole scan down, so any failure yields an empty set and scanning goes on
     * without the pin for that cycle.
     */
    private static java.util.Set<String> safeHeldExposure() {
        try {
            java.util.Set<String> held = DoughBayClient.heldExposureBaseIds();
            return held == null ? java.util.Set.of() : held;
        } catch (RuntimeException e) {
            return java.util.Set.of();
        }
    }

    /**
     * The given markets plus any the session is holding stock in, so a bought
     * market cannot be rotated out from under an open position and deadlock the
     * relist on refused exact-book coverage.
     */
    private static java.util.Set<String> pinHeldExposure(java.util.Set<String> markets) {
        java.util.Set<String> held = safeHeldExposure();
        if (held.isEmpty()) return markets;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(markets);
        out.addAll(held);
        return out;
    }

    /**
     * The signed-in player's name, or "" when there is none.
     *
     * <p>Read from the client rather than configured: the account playing is
     * the account whose balance matters, and asking the player to type their
     * own username is a step that can only be got wrong.
     */
    private static String signedInPlayerName() {
        try {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client == null || client.getUser() == null) return "";
            String name = client.getUser().getName();
            return name != null && PLAYER_NAME.matcher(name).matches() ? name : "";
        } catch (Throwable t) {
            // Reading client state off the game thread must never take the
            // collector down; an unknown balance is a perfectly fine outcome.
            return "";
        }
    }

    private static volatile long nextPlayerLookupAt;

    /**
     * Asks the server about the players standing near us.
     *
     * <p>Their gear the client already has; what it cannot see is whether the
     * stranger twenty blocks away is a fighter. One profile at a time, at most
     * one every few seconds, and only for somebody actually nearby, so the
     * sales and listing feeds never wait behind curiosity.
     */
    private void lookUpNearbyPlayers(DonutApiClient client) {
        if (Tuning.get("intel.enabled") < 0.5) return;
        long now = System.currentTimeMillis();
        if (now < nextPlayerLookupAt) return;
        java.util.List<String> want = PlayerIntel.profilesWanted();
        if (want.isEmpty()) return;
        nextPlayerLookupAt = now + Math.round(Tuning.get("intel.lookup_gap_sec") * 1000);
        String name = want.get(0);
        try {
            PlayerIntel.record(name, client.fetchPlayerStats(name));
            DoughBayClient.LOGGER.info("DoughBay intel: read the profile of {}", name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            PlayerIntel.record(name, null);   // asked, nothing came back; do not ask again soon
        }
    }

    private static volatile long nextRankAt;
    private static volatile boolean feedProbed;

    /**
     * One request at start-up that answers a standing question: does the
     * listing feed fill {@code enchants.enchantments.levels} for enchanted
     * items? The sales feed never has. Elytra are always enchanted, so the
     * first rows of an elytra search are the test; the raw rows go to the
     * log, where the answer can be read without anyone handling the key.
     */
    private static void probeListingFeed(DonutApiClient client) {
        try {
            var page = client.fetchListings(1, "elytra", "recently_listed");
            int shown = 0;
            for (var p : page.records()) {
                String raw = p.rawJson();
                DoughBayClient.LOGGER.info("DoughBay feed probe (elytra, recently listed): {}",
                        raw.length() > 600 ? raw.substring(0, 600) + "…" : raw);
                if (++shown >= 3) break;
            }
            if (shown == 0) DoughBayClient.LOGGER.info("DoughBay feed probe: no elytra listings on page 1 ({} parse failures)", page.failures().size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay feed probe failed: {}", e.getMessage());
        }
    }

    /** A join: the server can answer the lookup once the player is on it. */
    public static void requestRankLookup() {
        nextRankAt = System.currentTimeMillis() + 20_000;
    }
    private static final long RANK_INTERVAL_MILLIS = 30 * 60_000L;
    private static final long RANK_RETRY_MILLIS = 5 * 60_000L;

    /**
     * Reads the player's rank and sets the auction-slot count from it: 18
     * with no rank, 45 with one plus, 90 with two or more. A rank the table
     * does not know leaves the setting alone.
     */
    private static boolean applyRank(DonutApiClient client, String username) {
        if (username.isEmpty()) return false;
        try {
            String rank = client.fetchRank(username);
            DoughBayClient.setRank(rank);
            int slots = slotsForRank(rank);
            if (slots > 0 && Math.round(Tuning.get("slots.max")) != slots) {
                Tuning.set("slots.max", slots);
                DoughBayClient.LOGGER.info("DoughBay rank {} for {}: {} auction slots",
                        rank.isBlank() ? "(none)" : rank, username, slots);
            } else {
                DoughBayClient.LOGGER.info("DoughBay rank {} for {} (slots unchanged at {})",
                        rank.isBlank() ? "(none)" : rank, username, Math.round(Tuning.get("slots.max")));
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay could not read the rank for {}: {}", username, e.getMessage());
            return false;
        }
    }

    /** Slots for a rank string, or 0 when the rank is not one the table knows. */
    static int slotsForRank(String rank) {
        if (rank == null) return 0;
        String r = rank.toLowerCase(java.util.Locale.ROOT).strip();
        int plus = 0;
        for (char c : r.toCharArray()) if (c == '+') plus++;
        if (plus == 0) {
            int i = 0;
            while ((i = r.indexOf("plus", i)) >= 0) {
                plus++;
                i += 4;
            }
        }
        if (plus >= 2) return 90;
        if (plus == 1) return 45;
        if (r.isEmpty() || r.equals("default") || r.equals("member") || r.equals("none") || r.equals("player")) return 18;
        return 0;
    }

    /** @return the balance, or -1 when it cannot be determined */
    private static long fetchBalance(DonutApiClient client, String username) {
        if (username.isEmpty()) return -1;
        try {
            java.util.Map<String, String> stats = client.fetchPlayerStats(username);
            java.util.OptionalLong balance = DonutApiClient.balanceFrom(stats);
            if (balance.isEmpty()) {
                // Reached the API and got a profile back, but no readable money
                // field. That is a shape problem, not a missing player, so name
                // what did come back rather than only what did not.
                DoughBayClient.LOGGER.warn(
                        "DoughBay found no usable balance for {}. Profile fields: {}",
                        username, stats);
                return -1;
            }
            return balance.getAsLong();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            // Visible in the header as "balance unknown", so the reason has to
            // be somewhere the player can actually find it. The client already
            // redacts the key from its messages.
            DoughBayClient.LOGGER.warn("DoughBay could not read the balance for {}: {}",
                    username, e.getMessage());
            return -1;
        }
    }

    /**
     * One line naming which rules are doing the blocking.
     *
     * <p>Counted by reason rather than listed per market: eighty rows saying
     * the same thing is not information, and the question this answers is
     * which threshold to move. Written only when the mix changes, so a stable
     * configuration does not fill the log.
     */
    private void logRejectionSummary(MarketService.DetectionReport detection) {
        if (detection.nearMisses().isEmpty()) {
            lastRejectionSummary = "";
            return;
        }
        Map<String, Integer> byRule = new HashMap<>();
        for (MarketService.NearMiss miss : detection.nearMisses()) {
            for (String blocker : miss.blockers()) {
                // Count the rule, not its numbers: "ROI 4.2% (need 12%)" and
                // "ROI 8.1% (need 12%)" are the same finding.
                byRule.merge(ruleName(blocker), 1, Integer::sum);
            }
        }
        String summary = byRule.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> e.getKey() + " " + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        if (summary.equals(lastRejectionSummary)) return;
        lastRejectionSummary = summary;
        DoughBayClient.LOGGER.info(
                "DoughBay passed {} and rejected {} candidate market(s): {}",
                detection.opportunities().size(), detection.nearMisses().size(), summary);
    }

    /** The rule behind a blocker message, without its specific numbers. */
    private static String ruleName(String blocker) {
        int cut = blocker.indexOf(' ');
        String head = cut < 0 ? blocker : blocker.substring(0, cut);
        return switch (head) {
            case "Only" -> "samples";
            case "Last" -> "stale";
            case "Volatility" -> "volatility";
            case "Falling" -> "falling";
            case "Profit" -> "profit";
            case "ROI" -> "roi";
            case "Hold" -> "hold";
            case "Confidence" -> "confidence";
            case "Too" -> "position-size";
            case "Not" -> "capital";
            case "All" -> "position-slots";
            default -> head.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static ChartData collectChartData(
            MarketService service, java.util.Set<String> itemKeys, long now) throws java.sql.SQLException {
        long since = now - HISTORY_WINDOW_MILLIS;
        Map<String, List<Charts.Point>> allPoints = new HashMap<>();
        // One read for every sparkline rather than one per market. This window
        // is twelve hours, not the analyzer's seven days, so it stays bounded
        // by recent volume instead of by archive size.
        for (dev.doughbay.core.model.Sale sale : service.transactions().findAllSince(since)) {
            if (!itemKeys.contains(sale.itemKey())) continue;
            // Repository reads are completed transactions only. Keep the chart
            // fail-closed if a malformed or future row ever reaches storage
            // despite ingestion validation.
            if (sale.isValid() && sale.soldAt() >= since && sale.soldAt() <= now
                    && Double.isFinite(sale.unitPrice())) {
                allPoints.computeIfAbsent(sale.itemKey(), k -> new ArrayList<>())
                        .add(new Charts.Point(sale.soldAt(), sale.unitPrice()));
            }
        }
        return chartData(allPoints, now);
    }

    /**
     * Builds a bounded price line and full-volume bins from the same completed
     * sale observations. Downsampling never affects the count histogram.
     */
    private static ChartData chartData(Map<String, List<Charts.Point>> allPoints, long windowEnd) {
        long windowStart = windowEnd - HISTORY_WINDOW_MILLIS;
        Map<String, List<Charts.Point>> history = new HashMap<>();
        Map<String, VolumeWindow> volume = new HashMap<>();

        for (Map.Entry<String, List<Charts.Point>> entry : allPoints.entrySet()) {
            List<Charts.Point> visible = new ArrayList<>();
            int[] counts = new int[VolumeWindow.BUCKET_COUNT];
            for (Charts.Point point : entry.getValue()) {
                if (point.at() < windowStart || point.at() > windowEnd
                        || !Double.isFinite(point.value())) {
                    continue;
                }
                visible.add(point);
                long offset = point.at() - windowStart;
                int bucket = offset == HISTORY_WINDOW_MILLIS
                        ? counts.length - 1
                        : (int) (offset * counts.length / HISTORY_WINDOW_MILLIS);
                counts[Math.max(0, Math.min(counts.length - 1, bucket))]++;
            }
            if (visible.isEmpty()) continue;

            visible.sort(java.util.Comparator.comparingLong(Charts.Point::at));
            history.put(entry.getKey(), downsample(visible));
            List<Integer> immutableCounts = new ArrayList<>(counts.length);
            for (int count : counts) immutableCounts.add(count);
            volume.put(entry.getKey(),
                    new VolumeWindow(windowEnd, HISTORY_WINDOW_MILLIS, immutableCounts));
        }
        return new ChartData(Map.copyOf(history), Map.copyOf(volume));
    }

    /** Evenly retains the first/last observations while enforcing a hard cap. */
    private static List<Charts.Point> downsample(List<Charts.Point> points) {
        if (points.size() <= MAX_HISTORY_POINTS) return List.copyOf(points);
        List<Charts.Point> sampled = new ArrayList<>(MAX_HISTORY_POINTS);
        for (int i = 0; i < MAX_HISTORY_POINTS; i++) {
            int index = (int) Math.round(
                    (double) i * (points.size() - 1) / (MAX_HISTORY_POINTS - 1));
            sampled.add(points.get(index));
        }
        return List.copyOf(sampled);
    }

    private record ChartData(Map<String, List<Charts.Point>> history,
                             Map<String, VolumeWindow> volume) {
    }

    private record CoverageRequest(String itemId, long scanMustStartAfterMillis,
                                   long sequence) {
    }

    /**
     * Flattens one complete commodity scan into a deterministic immutable list.
     * Blank listing keys are retained because they still represent active
     * supply (and may make an own-listing reconciliation ambiguous); only a
     * nonblank identity can safely collapse overlapping scan results.
     */
    private static List<Listing> flattenActiveListings(
            Map<String, List<Listing>> commodityListings) {
        if (commodityListings == null || commodityListings.isEmpty()) return List.of();

        List<Listing> flattened = new ArrayList<>();
        for (List<Listing> listings : commodityListings.values()) {
            if (listings == null) continue;
            for (Listing listing : listings) {
                if (listing != null) flattened.add(listing);
            }
        }
        flattened.sort(ACTIVE_LISTING_ORDER);

        Set<String> seenKeys = new HashSet<>();
        List<Listing> deduplicated = new ArrayList<>(flattened.size());
        for (Listing listing : flattened) {
            String key = normalizedListingKey(listing);
            if (key.isBlank() || seenKeys.add(key)) {
                deduplicated.add(listing);
            }
        }
        return List.copyOf(deduplicated);
    }

    private static List<Listing> findExactOwnListings(
            List<Listing> source, String sellerUuid, String sellerName,
            String itemKey, String itemId, int itemCount, long totalPrice) {
        String expectedUuid = normalizedUuid(sellerUuid);
        String expectedName = normalizedPlayerName(sellerName);
        if ((expectedUuid.isBlank() && expectedName.isBlank())
                || itemKey == null || itemKey.isBlank()
                || itemId == null || itemId.isBlank()
                || itemCount <= 0 || totalPrice <= 0) {
            return List.of();
        }

        List<Listing> matches = new ArrayList<>();
        for (Listing listing : source) {
            if (!itemKey.equals(listing.itemKey())
                    || !itemId.equals(listing.itemId())
                    || itemCount != listing.itemCount()
                    || totalPrice != listing.totalPrice()
                    || !sameSeller(expectedUuid, expectedName, listing)) {
                continue;
            }
            matches.add(listing);
        }
        return List.copyOf(matches);
    }

    private static boolean sameSeller(String expectedUuid, String expectedName,
                                      Listing listing) {
        String listedUuid = normalizedUuid(listing.sellerUuid());
        String listedName = normalizedPlayerName(listing.sellerName());

        if (!expectedUuid.isBlank() && !listedUuid.isBlank()
                && !expectedUuid.equals(listedUuid)) {
            return false;
        }
        if (!expectedName.isBlank() && !listedName.isBlank()
                && !expectedName.equals(listedName)) {
            return false;
        }
        return (!expectedUuid.isBlank() && expectedUuid.equals(listedUuid))
                || (!expectedName.isBlank() && expectedName.equals(listedName));
    }

    private static String normalizedUuid(String value) {
        if (value == null) return "";
        String compact = value.strip().toLowerCase(java.util.Locale.ROOT)
                .replace("-", "");
        return compact.matches("[0-9a-f]{32}") ? compact : "";
    }

    private static String normalizedPlayerName(String value) {
        if (value == null) return "";
        String stripped = value.strip();
        return PLAYER_NAME.matcher(stripped).matches()
                ? stripped.toLowerCase(java.util.Locale.ROOT)
                : "";
    }

    private static String normalizedListingKey(Listing listing) {
        return listing == null || listing.listingKey() == null
                ? ""
                : listing.listingKey().strip();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private void setStatus(String status) {
        latest.updateAndGet(current -> new Snapshot(current.opportunities(), current.nearMisses(), current.markets(),
                current.activeListings(), current.history(), current.volume(),
                current.exactListingCoverage().stale("watcher status changed to " + status),
                current.accountName(), current.accountBalance(),
                status, current.diagnostics(), current.activeListingScanStartedAt(),
                current.updatedAt(), current.demo()));
    }
}
