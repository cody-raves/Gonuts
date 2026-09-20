package dev.doughbay.server;

import dev.doughbay.api.DonutApiClient;
import dev.doughbay.cli.AppConfig;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.engine.MarketService;
import dev.doughbay.api.ResponseParser;
import dev.doughbay.storage.Database;
import dev.doughbay.storage.ListingRepository;
import dev.doughbay.storage.MarketStatsRepository;

import java.nio.file.Path;
import java.util.List;

/**
 * Polls the Donut feeds on their own thread and keeps the derived market
 * statistics fresh. Sales are cheap (a page or two every few seconds), the
 * listing sweep is expensive (one request per commodity page) and runs on
 * a slower cadence; both go through the API client's rate limiter, which
 * never exceeds the configured budget.
 */
public final class Collector implements Runnable {
    private final AppConfig config;
    private final DonutApiClient client;
    private final Path databasePath;
    private final long salesEveryMillis;
    private final long listingsEveryMillis;
    private final long statsEveryMillis;
    private final Thread thread = new Thread(this, "doughbay-collector");
    private volatile boolean running = true;

    private volatile List<MarketStats> latestStats = List.of();
    /** When snapshots were last thinned out; hourly is often enough. */
    private volatile long lastPruneAt;

    private volatile long lastSalesPollAt;
    private volatile long lastListingsSweepAt;
    private volatile long lastStatsAt;
    private volatile long salesCollected;
    private volatile long newSales;
    private volatile String lastError = "";
    private volatile int lastPagesRead;
    private volatile long deepPolls;
    /** Enough pages to cover a very busy minute; beyond this the interval is too long. */
    private static final int MAX_PAGES_PER_POLL = 10;

    public int lastPagesRead() {
        return lastPagesRead;
    }

    /** The live lane: page one of Recently Listed every few seconds, each listing stored once. */
    private volatile long liveEveryMillis = 5_000;
    private volatile long lastLivePollAt;
    private volatile long newListings;
    private final java.util.LinkedHashSet<String> liveKeys = new java.util.LinkedHashSet<>();

    public void setLiveEveryMillis(long millis) {
        liveEveryMillis = Math.max(1_000, millis);
    }

    public long lastLivePollAt() {
        return lastLivePollAt;
    }

    public long newListings() {
        return newListings;
    }

    public long deepPolls() {
        return deepPolls;
    }
    private final long startedAt = System.currentTimeMillis();
    /** DOUGHBAY_NO_POLL=1 serves the database without touching Donut: local mock-ups and demos. */
    private final boolean polling = !"1".equals(System.getenv("DOUGHBAY_NO_POLL"));

    Collector(AppConfig config, DonutApiClient client, Path databasePath,
              long salesEveryMillis, long listingsEveryMillis, long statsEveryMillis) {
        this.config = config;
        this.client = client;
        this.databasePath = databasePath;
        this.salesEveryMillis = salesEveryMillis;
        this.listingsEveryMillis = listingsEveryMillis;
        this.statsEveryMillis = statsEveryMillis;
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    void stop() {
        running = false;
        thread.interrupt();
    }

    public List<MarketStats> latestStats() {
        return latestStats;
    }

    public long startedAt() {
        return startedAt;
    }

    public long lastSalesPollAt() {
        return lastSalesPollAt;
    }

    public long lastListingsSweepAt() {
        return lastListingsSweepAt;
    }

    public long lastStatsAt() {
        return lastStatsAt;
    }

    public long salesCollected() {
        return salesCollected;
    }

    public long newSales() {
        return newSales;
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public void run() {
        long nextSales = 0;
        long nextListings = System.currentTimeMillis() + 15_000;
        long nextStats = System.currentTimeMillis() + 5_000;
        long nextLive = System.currentTimeMillis() + 20_000;
        while (running) {
            long now = System.currentTimeMillis();
            try (Database db = new Database(databasePath)) {
                MarketService service = ServerMain.service(config, client, db);
                if (polling && now >= nextSales) {
                    // Read pages until one overlaps what is already stored: then
                    // nothing between this poll and the last can have scrolled past,
                    // whatever the sale rate. A page of duplicates is the overlap.
                    int page = 1;
                    int pagesRead = 0;
                    while (page <= MAX_PAGES_PER_POLL) {
                        MarketService.CollectReport report = service.collectTransactions(page, page);
                        pagesRead++;
                        salesCollected += report.recordsSeen();
                        newSales += report.newRecords();
                        if (report.duplicates() > 0 || report.recordsSeen() == 0) break;
                        page++;
                    }
                    if (pagesRead >= MAX_PAGES_PER_POLL) {
                        deepPolls++;
                        System.err.println("collector: no overlap within " + MAX_PAGES_PER_POLL
                                + " pages; the feed outran this poll interval");
                    }
                    lastPagesRead = pagesRead;
                    lastSalesPollAt = now;
                    nextSales = now + salesEveryMillis;
                }
                if (polling && now >= nextLive) {
                    // Newest listings first, across every item. A listing key
                    // repeats while the listing stays up, so each is stored once,
                    // at the moment it was first seen: a true "new listings" stream.
                    ResponseParser.ParseResult<ResponseParser.ParsedListing> page =
                            client.fetchListings(1, "", "recently_listed");
                    ListingRepository listings = new ListingRepository(db);
                    for (ResponseParser.ParsedListing p : page.records()) {
                        boolean fresh;
                        synchronized (liveKeys) {
                            fresh = liveKeys.add(p.listing().listingKey());
                            if (liveKeys.size() > 20_000) {
                                java.util.Iterator<String> oldest = liveKeys.iterator();
                                oldest.next();
                                oldest.remove();
                            }
                        }
                        if (!fresh) continue;
                        listings.insertSnapshot(p.listing(), p.rawJson());
                        newListings++;
                    }
                    lastLivePollAt = now;
                    nextLive = now + liveEveryMillis;
                    pruneSnapshots(db, now);
                }
                if (polling && now >= nextListings) {
                    service.scanCommodityListings(10);
                    lastListingsSweepAt = now;
                    nextListings = now + listingsEveryMillis;
                }
                if (now >= nextStats) {
                    List<MarketStats> stats = service.marketStatsFromHistory(now, config.risk().minimumSamples());
                    MarketStatsRepository repo = new MarketStatsRepository(db);
                    for (MarketStats s : stats) repo.upsert(s);
                    latestStats = List.copyOf(stats);
                    lastStatsAt = now;
                    nextStats = now + statsEveryMillis;
                }
                lastError = "";
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                lastError = e.toString();
                System.err.println("collector: " + e);
                nextSales = Math.max(nextSales, now + 15_000);
            }
            if (!polling) {
                nextSales = now + salesEveryMillis;
                nextListings = now + listingsEveryMillis;
                nextLive = now + liveEveryMillis;
            }
            long sleep = Math.max(500, Math.min(Math.min(nextSales, nextLive), Math.min(nextListings, nextStats)) - System.currentTimeMillis());
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
    /**
     * Throws away listing snapshots nobody will read again.
     *
     * <p>Every listing on the recently-listed page is written down on every
     * sweep and nothing ever deleted one. Eight days of that came to twenty-six
     * million rows and fourteen of the sixteen gigabytes in the file, growing a
     * couple of gigabytes a day with no end to it.
     *
     * <p>Nothing reads the old ones. Every query against the table asks for
     * recent rows - what a rival listed lately, the last stretch of a chart -
     * so the far end is not history anybody consults. It is also the feed the
     * trading side is told not to trust for decisions; the sales table is the
     * one that matters, and that one is kept in full.
     *
     * <p>Hourly rather than every sweep, because a delete that finds nothing
     * still walks the index - and the space is not handed back until somebody
     * runs VACUUM anyway, which rewrites the whole file and is worth doing
     * once by hand rather than on a timer.
     */
    private void pruneSnapshots(Database db, long now) {
        long keepDays = snapshotRetentionDays();
        if (keepDays <= 0) return;
        if (now - lastPruneAt < 3_600_000L) return;
        lastPruneAt = now;
        try {
            int gone = new ListingRepository(db).pruneSnapshotsBefore(now - keepDays * 86_400_000L);
            if (gone > 0) {
                System.out.printf("[collector] pruned %,d listing snapshot(s) older than %d day(s)%n",
                        gone, keepDays);
            }
        } catch (Exception e) {
            System.out.println("[collector] snapshot prune failed: " + e);
        }
    }

    /** Days of snapshots to keep. DOUGHBAY_SNAPSHOT_DAYS=0 keeps everything, as before. */
    private static long snapshotRetentionDays() {
        String configured = System.getenv("DOUGHBAY_SNAPSHOT_DAYS");
        if (configured != null && !configured.isBlank()) {
            try {
                return Long.parseLong(configured.strip());
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return 3;
    }

}
