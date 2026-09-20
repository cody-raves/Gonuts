package dev.doughbay.cli;

import dev.doughbay.engine.MarketService;
import dev.doughbay.api.DonutApiClient;
import dev.doughbay.core.analysis.OpportunityDetector;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Position;
import dev.doughbay.paper.PaperConfig;
import dev.doughbay.paper.PaperMetrics;
import dev.doughbay.paper.PaperTradingEngine;
import dev.doughbay.storage.Database;
import dev.doughbay.storage.PositionRepository;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DoughBay POC CLI. Observation and simulation only: it reads the auction
 * API, analyzes markets, and paper-trades a simulated bankroll. It performs
 * no in-game actions whatsoever.
 *
 * <pre>
 *   java -jar doughbay.jar collect [--pages N] [--loop]
 *   java -jar doughbay.jar scan
 *   java -jar doughbay.jar paper --balance 2000000 [--cycles N]
 * </pre>
 */
public final class Main {

    private static final int BOOTSTRAP_PAGES = 10;
    private static final long POLL_INTERVAL_MILLIS = 30_000;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        // report and import read only the local database, so they must work
        // without a key; everything else talks to the API and needs one.
        boolean localOnly = args[0].equals("report") || args[0].equals("import");
        AppConfig config;
        try {
            config = localOnly
                    ? AppConfig.loadForLocalCommand(Path.of("doughbay.json"))
                    : AppConfig.load(Path.of("doughbay.json"));
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        int exitCode = 0;
        try (Database db = new Database(config.databasePath())) {
            DonutApiClient client = new DonutApiClient(config.api());
            MarketService service = new MarketService(config.analyzer(), config.fees(), config.risk(),
                config.commodities(), client, db);
            switch (args[0]) {
                case "doctor" -> exitCode = new Doctor(service).run();
                case "import" -> importHistory(db, args);
                case "collect" -> collect(service, args);
                case "scan" -> scan(service, config);
                case "paper" -> paper(service, config, db, args);
                case "report" -> new PerformanceReport(new PositionRepository(db), config.risk())
                        .print(reportMode(args));
                default -> usage();
            }
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void usage() {
        System.out.println("""
                DoughBay — auction market analysis & paper trading (observation only)

                Commands:
                  doctor                         Validate the data pipeline (run this first!)
                  import <file>                  Import completed sales from CSV/JSON into history
                  collect [--pages N] [--loop]   Download completed transactions into SQLite
                  scan                           Scan commodity listings and rank opportunities
                  paper --balance N [--cycles N] Run the paper-trading loop against live data
                  report [--paper]               How trading actually went: profit, win rate, by market

                The API key is read from DONUT_API_KEY or doughbay.json (never commit it).
                """);
    }

    // ------------------------------------------------------------------ report

    /** Real positions by default; paper only when explicitly asked for. */
    private static dev.doughbay.core.performance.PerformanceMode reportMode(String[] args) {
        return hasFlag(args, "--paper")
                ? dev.doughbay.core.performance.PerformanceMode.PAPER
                : dev.doughbay.core.performance.PerformanceMode.REAL;
    }

    // ------------------------------------------------------------------ import

    private static void importHistory(Database db, String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: import <file.csv|file.json>");
            return;
        }
        Path file = Path.of(args[1]);
        Importer importer = new Importer(new dev.doughbay.storage.TransactionRepository(db));
        Importer.Report report = importer.importFile(file, file.getFileName().toString());
        System.out.printf(Locale.ROOT,
                "read=%d imported=%d duplicates=%d rejected=%d%n",
                report.read(), report.imported(), report.duplicates(), report.rejected());
        if (!report.problems().isEmpty()) {
            System.out.println("First problems:");
            report.problems().forEach(p -> System.out.println("  - " + p));
        }
        if (report.imported() > 0) {
            System.out.println("Imported history is marked by its source; run `doctor` to sanity-check it.");
        }
    }

    // ------------------------------------------------------------------ collect

    private static void collect(MarketService service, String[] args) throws Exception {
        int pages = intFlag(args, "--pages", BOOTSTRAP_PAGES);
        boolean loop = hasFlag(args, "--loop");

        MarketService.CollectReport report = service.collectTransactions(1, pages);
        printCollectReport(report, service);

        while (loop) {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            // Page 1 frequently; page 2 rides along every other cycle via pages=2.
            MarketService.CollectReport poll = service.collectTransactions(1, 2);
            printCollectReport(poll, service);
        }
    }

    private static void printCollectReport(MarketService.CollectReport report, MarketService service)
            throws Exception {
        DonutApiClient.ApiHealth health = service.client().health();
        System.out.printf(Locale.ROOT,
                "collected=%d new=%d duplicates=%d parseFailures=%d | totalStored=%d markets=%d | api %d req (%d/min of %d budget), %d errors%n",
                report.recordsSeen(), report.newRecords(), report.duplicates(), report.parseFailures(),
                service.transactions().count(), service.transactions().countDistinctMarkets(),
                health.totalRequests(), health.requestsInLastMinute(), health.budgetPerMinute(),
                health.totalErrors());
    }

    // ------------------------------------------------------------------ scan

    private static void scan(MarketService service, AppConfig config) throws Exception {
        long now = System.currentTimeMillis();
        MarketService.ScanReport scan = service.scanCommodityListings();
        System.out.printf(Locale.ROOT,
                "Scanned %d commodity markets (%d complete, %d suppressed), %d eligible listings, %d parse failures%n",
                scan.itemsScanned(), scan.completeItems(), scan.incompleteItems(),
                scan.listingsSeen(), scan.parseFailures());

        // A pure scan has no bankroll context; use a nominal one so bankroll
        // rules don't reject everything, and surface the raw ranking.
        OpportunityDetector.Bankroll bankroll =
                new OpportunityDetector.Bankroll(Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, 0);
        List<Opportunity> found = service.detectOpportunities(scan.commodityListings(), now, bankroll);

        if (found.isEmpty()) {
            System.out.println("No opportunities passed the filters.");
            return;
        }
        System.out.printf(Locale.ROOT, "%n%-28s %6s %12s %12s %10s %7s %8s %6s%n",
                "ITEM", "COUNT", "BUY", "SELL@", "PROFIT", "ROI", "HOLD", "CONF");
        for (Opportunity o : found) {
            System.out.printf(Locale.ROOT, "%-28s %6d %,12d %,12d %,10.0f %6.0f%% %6.0fmin %5.0f%%%n",
                    o.listing().itemId(), o.listing().itemCount(),
                    o.buyPrice(), o.recommendedSellPrice(),
                    o.expectedNetProfit(), o.expectedRoiPercent(),
                    o.estimatedHoldHours() * 60, o.confidence() * 100);
        }
        System.out.println();
        Opportunity best = found.get(0);
        System.out.println("WHY THE TOP OPPORTUNITY WAS FLAGGED");
        best.reasons().forEach(r -> System.out.println("  - " + r));
    }

    // ------------------------------------------------------------------ paper

    private static void paper(MarketService service, AppConfig config, Database db, String[] args)
            throws Exception {
        long balance = longFlag(args, "--balance", 2_000_000L);
        int cycles = intFlag(args, "--cycles", Integer.MAX_VALUE);

        PaperConfig paperConfig = new PaperConfig(
                balance,
                PaperConfig.defaults(balance).executionDelayMillis(),
                PaperConfig.defaults(balance).listingDurationMillis(),
                config.fees());
        PaperTradingEngine engine = new PaperTradingEngine(paperConfig);

        PositionRepository positionRepo = new PositionRepository(db);
        Map<Long, Long> localToDbId = new HashMap<>();
        engine.setPositionListener(p -> persistPosition(positionRepo, localToDbId, p));

        Set<String> signaledListingKeys = new HashSet<>();
        System.out.printf(Locale.ROOT, "Paper trading with simulated bankroll %,d (no live actions)%n", balance);

        // Bootstrap history before the first decision.
        MarketService.CollectReport bootstrap =
                service.collectTransactions(1, BOOTSTRAP_PAGES);
        boolean completedHistoryHealthy = bootstrap.parseFailures() == 0;

        for (int cycle = 0; cycle < cycles; cycle++) {
            MarketService.CollectReport collected = completedHistoryHealthy
                    ? service.collectTransactions(1, 2)
                    : service.collectTransactions(1, BOOTSTRAP_PAGES);
            completedHistoryHealthy = collected.parseFailures() == 0;
            MarketService.ScanReport scan = service.scanCommodityListings();
            boolean activeBooksHealthy = scan.completeForValuation();
            // The cycle's as-of time is captured only after all HTTP reads.
            // A sale observed during those reads is therefore never "future"
            // relative to the paper decision, and it will not be lost merely
            // because transaction deduplication suppresses it next cycle.
            long now = System.currentTimeMillis();

            if (completedHistoryHealthy && activeBooksHealthy) {
                Map<String, Listing> activeByKey = new HashMap<>();
                scan.commodityListings().values().forEach(list ->
                        list.forEach(l -> activeByKey.put(l.listingKey(), l)));

                // Order matters for chronology: new sales first resolve existing
                // simulated listings, then the tick advances signals, and only
                // afterwards may new signals be taken at "now".
                engine.onSales(collected.newSales(), now);
                engine.tick(now, activeByKey);

                OpportunityDetector.Bankroll bankroll = new OpportunityDetector.Bankroll(
                        engine.balance() + engine.inventoryValue(),
                        engine.balance(),
                        engine.openPositionCount());
                List<Opportunity> found = service.detectOpportunities(
                        scan.commodityListings(), now, bankroll);
                for (Opportunity o : found) {
                    if (signaledListingKeys.add(o.listing().listingKey())) {
                        engine.onOpportunity(o, now);
                        System.out.printf(Locale.ROOT,
                                "SIGNAL %s x%d buy %,d -> sell %,d (profit %,.0f, %s)%n",
                                o.listing().itemId(), o.listing().itemCount(), o.buyPrice(),
                                o.recommendedSellPrice(), o.expectedNetProfit(),
                                String.join("; ", o.reasons().subList(
                                        0, Math.min(2, o.reasons().size()))));
                    }
                }
            } else if (cycle == 0 || cycle % 10 == 0) {
                System.err.printf(Locale.ROOT,
                        "DATA DEGRADED — paper decisions paused (completedSales=%s, completeBooks=%d/%d, parseFailures=%d)%n",
                        completedHistoryHealthy ? "clean" : "invalid",
                        scan.completeItems(), scan.itemsScanned(),
                        collected.parseFailures() + scan.parseFailures());
            }

            positionRepo.recordBalance(now, PaperTradingEngine.MODE, engine.balance(), engine.inventoryValue());

            if (cycle % 10 == 0) {
                PaperMetrics metrics = engine.metrics(now);
                System.out.println();
                System.out.print(metrics.render());
                List<Long> equity = engine.balanceHistory().stream()
                        .map(p -> p.balance() + p.inventoryValue()).toList();
                System.out.println("Simulated equity over time:");
                System.out.print(AsciiChart.render(equity, 60, 8));
                System.out.println();
            }

            if (cycle + 1 < cycles) {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        }

        PaperMetrics metrics = engine.metrics(System.currentTimeMillis());
        System.out.print(metrics.render());
    }

    private static void persistPosition(PositionRepository repo, Map<Long, Long> localToDbId,
                                        Position p) {
        try {
            Long dbId = localToDbId.get(p.positionId());
            if (dbId == null) {
                Position stored = repo.insert(p);
                localToDbId.put(p.positionId(), stored.positionId());
            } else {
                repo.update(new Position(dbId, p.mode(), p.itemKey(), p.bucket(), p.quantity(),
                        p.purchasePrice(), p.targetPrice(), p.purchasedAt(), p.listedAt(),
                        p.closedAt(), p.salePrice(), p.realizedProfit(), p.status()));
            }
        } catch (Exception e) {
            System.err.println("Failed to persist position: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ flags

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) if (a.equals(flag)) return true;
        return false;
    }

    private static int intFlag(String[] args, String flag, int fallback) {
        return (int) longFlag(args, flag, fallback);
    }

    private static long longFlag(String[] args, String flag, long fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                try {
                    return Long.parseLong(args[i + 1]);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(flag + " requires a number, got " + args[i + 1]);
                }
            }
        }
        return fallback;
    }
}
