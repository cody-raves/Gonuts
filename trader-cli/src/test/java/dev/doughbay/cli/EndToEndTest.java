package dev.doughbay.cli;

import dev.doughbay.engine.MarketService;
import com.sun.net.httpserver.HttpServer;
import dev.doughbay.api.DonutApiClient;
import dev.doughbay.api.DonutApiConfig;
import dev.doughbay.core.analysis.AnalyzerConfig;
import dev.doughbay.core.analysis.CommodityRegistry;
import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.analysis.OpportunityDetector;
import dev.doughbay.core.analysis.RiskConfig;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.paper.PaperConfig;
import dev.doughbay.paper.PaperTradingEngine;
import dev.doughbay.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full pipeline against a local mock of the Donut API: collect transactions,
 * scan listings, detect the underpriced pearl stack, and paper-trade it
 * through to LIKELY_SOLD. No network, no game, no real API.
 */
class EndToEndTest {

    private HttpServer server;
    private Database db;
    private final AtomicReference<String> transactionsBody = new AtomicReference<>();
    private final AtomicReference<String> listingsBody = new AtomicReference<>();

    private static final long NOW = System.currentTimeMillis();

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auction/transactions/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean pageOne = path.endsWith("/1");
            respond(exchange, pageOne ? transactionsBody.get() : "{\"status\":200,\"result\":[]}");
        });
        server.createContext("/v1/auction/list/", exchange -> {
            boolean pageOne = exchange.getRequestURI().getPath().endsWith("/1");
            respond(exchange, pageOne ? listingsBody.get() : "{\"status\":200,\"result\":[]}");
        });
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        db.close();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private AppConfig config() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new AppConfig(
                new DonutApiConfig(baseUrl, "test-key", 180, 220, 5),
                AnalyzerConfig.defaults(),
                FeeConfig.zero(),
                RiskConfig.defaults(),
                new CommodityRegistry(Set.of("minecraft:ender_pearl")),
                null);
    }

    private static String transactionsJson() {
        StringBuilder sb = new StringBuilder("{\"status\":200,\"result\":[");
        // 30 recent x16 pearl sales around 9200-9500 from 6 sellers.
        for (int i = 0; i < 30; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(
                    "{\"unixMillisDateSold\":%d,\"price\":%d," +
                    "\"seller\":{\"uuid\":\"seller-%d\",\"name\":\"S%d\"}," +
                    "\"item\":{\"id\":\"minecraft:ender_pearl\",\"count\":16}}",
                    NOW - (i + 1) * 2 * 60_000L, 9_200 + (i % 4) * 100, i % 6, i % 6));
        }
        // One troll sale that must be filtered as an outlier.
        sb.append(String.format(
                ",{\"unixMillisDateSold\":%d,\"price\":99000000," +
                "\"seller\":{\"uuid\":\"troll\",\"name\":\"T\"}," +
                "\"item\":{\"id\":\"minecraft:ender_pearl\",\"count\":16}}", NOW - 90_000L));
        return sb.append("]}").toString();
    }

    private static String listingsJson(boolean includeCheapListing) {
        StringBuilder sb = new StringBuilder("{\"status\":200,\"result\":[");
        if (includeCheapListing) {
            sb.append(String.format(
                    "{\"price\":4100,\"seller\":{\"uuid\":\"cheap-seller\",\"name\":\"C\"}," +
                    "\"item\":{\"id\":\"minecraft:ender_pearl\",\"count\":16}},"));
        }
        // Normal competition at 9200 and 9600, plus an enchanted pearl-named
        // item that must be ignored by the commodity gate.
        sb.append(String.format(
                "{\"price\":9200,\"seller\":{\"uuid\":\"comp-1\",\"name\":\"A\"}," +
                "\"item\":{\"id\":\"minecraft:ender_pearl\",\"count\":16}}," +
                "{\"price\":9600,\"seller\":{\"uuid\":\"comp-2\",\"name\":\"B\"}," +
                "\"item\":{\"id\":\"minecraft:ender_pearl\",\"count\":16}}," +
                "{\"price\":50,\"seller\":{\"uuid\":\"weird\",\"name\":\"W\"}," +
                "\"item\":{\"id\":\"minecraft:ender_pearl\",\"count\":16," +
                "\"enchants\":{\"minecraft:unbreaking\":1}}}"));
        return sb.append("]}").toString();
    }

    @Test
    void collectScanDetectAndPaperTradeEndToEnd() throws Exception {
        transactionsBody.set(transactionsJson());
        listingsBody.set(listingsJson(true));

        AppConfig config = config();
        DonutApiClient client = new DonutApiClient(config.api());
        MarketService service = new MarketService(config.analyzer(), config.fees(), config.risk(),
                config.commodities(), client, db);

        // --- Collect: dedup works across repeated polls -------------------
        MarketService.CollectReport first = service.collectTransactions(1, 2);
        assertEquals(31, first.newRecords());
        MarketService.CollectReport again = service.collectTransactions(1, 2);
        assertEquals(0, again.newRecords());
        assertEquals(31, again.duplicates());

        // --- Scan: only the plain commodity listings survive ---------------
        MarketService.ScanReport scan = service.scanCommodityListings();
        assertEquals(1, scan.completeItems());
        assertEquals(0, scan.incompleteItems());
        assertEquals(3, scan.listingsSeen(), "enchanted pearl must be gated out");

        // --- Detect: the 4100 listing is the opportunity -------------------
        OpportunityDetector.Bankroll bankroll =
                new OpportunityDetector.Bankroll(2_000_000, 2_000_000, 0);
        List<Opportunity> found = service.detectOpportunities(
                scan.commodityListings(), NOW, bankroll);
        assertEquals(1, found.size());
        Opportunity best = found.get(0);
        assertEquals(4_100, best.buyPrice());
        // Conservative resale: capped by history and the 9200 competitor.
        assertTrue(best.recommendedSellPrice() <= 9_200);
        assertTrue(best.recommendedSellPrice() > 8_000);
        assertTrue(best.expectedNetProfit() > 4_000);
        assertFalse(best.reasons().isEmpty());

        // --- Paper trade it through to LIKELY_SOLD -------------------------
        PaperTradingEngine engine = new PaperTradingEngine(
                new PaperConfig(2_000_000, 15_000, 12L * 3600_000L, FeeConfig.zero()));
        engine.onOpportunity(best, NOW);

        Map<String, Listing> book = new HashMap<>();
        scan.commodityListings().values().forEach(l -> l.forEach(x -> book.put(x.listingKey(), x)));
        engine.tick(NOW + 16_000, book);
        Position p = engine.positions().iterator().next();
        assertEquals(PositionStatus.SIMULATED_LISTED, p.status());
        assertEquals(2_000_000 - 4_100, engine.balance());

        // A later comparable sale at our target resolves the flip.
        engine.onSales(List.of(new dev.doughbay.core.model.Sale(
                "resolve", NOW + 120_000, "buyer-side-seller", "X",
                "minecraft:ender_pearl", "minecraft:ender_pearl", 16,
                best.recommendedSellPrice())), NOW + 120_000);
        p = engine.positions().iterator().next();
        assertEquals(PositionStatus.LIKELY_SOLD, p.status());
        assertTrue(engine.balance() > 2_000_000);
    }

    @Test
    void vanishedDealBecomesMissedNotProfit() throws Exception {
        transactionsBody.set(transactionsJson());
        listingsBody.set(listingsJson(true));

        AppConfig config = config();
        DonutApiClient client = new DonutApiClient(config.api());
        MarketService service = new MarketService(config.analyzer(), config.fees(), config.risk(),
                config.commodities(), client, db);
        service.collectTransactions(1, 1);
        MarketService.ScanReport scan = service.scanCommodityListings();
        List<Opportunity> found = service.detectOpportunities(scan.commodityListings(), NOW,
                new OpportunityDetector.Bankroll(2_000_000, 2_000_000, 0));
        assertEquals(1, found.size());

        PaperTradingEngine engine = new PaperTradingEngine(
                new PaperConfig(2_000_000, 15_000, 12L * 3600_000L, FeeConfig.zero()));
        engine.onOpportunity(found.get(0), NOW);

        // Next scan: the cheap listing is gone before our latency elapsed.
        listingsBody.set(listingsJson(false));
        MarketService.ScanReport rescan = service.scanCommodityListings();
        Map<String, Listing> book = new HashMap<>();
        rescan.commodityListings().values().forEach(l -> l.forEach(x -> book.put(x.listingKey(), x)));
        engine.tick(NOW + 16_000, book);

        Position p = engine.positions().iterator().next();
        assertEquals(PositionStatus.MISSED_BEFORE_PURCHASE, p.status());
        assertEquals(2_000_000, engine.balance());
    }
}
