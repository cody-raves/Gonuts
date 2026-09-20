package dev.doughbay.cli;

import dev.doughbay.engine.MarketService;
import com.sun.net.httpserver.HttpServer;
import dev.doughbay.api.DonutApiClient;
import dev.doughbay.api.DonutApiConfig;
import dev.doughbay.core.analysis.AnalyzerConfig;
import dev.doughbay.core.analysis.CommodityRegistry;
import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.analysis.RiskConfig;
import dev.doughbay.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The doctor must catch broken data, not just rubber-stamp whatever arrives. */
class DoctorTest {

    private HttpServer server;
    private Database db;
    private final AtomicReference<String> transactions = new AtomicReference<>();
    private final AtomicReference<String> listings = new AtomicReference<>();
    private static final long NOW = System.currentTimeMillis();

    @BeforeEach
    void setUp() throws Exception {
        db = Database.inMemory();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auction/transactions/", ex -> respond(ex, transactions.get()));
        server.createContext("/v1/auction/list/", ex -> respond(ex, listings.get()));
        server.start();
        listings.set("{\"status\":200,\"result\":[{\"price\":4100," +
                "\"seller\":{\"uuid\":\"s\",\"name\":\"S\"}," +
                "\"item\":{\"id\":\"minecraft:ender_pearl\",\"count\":16}}]}");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        db.close();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String runDoctor() throws Exception {
        AppConfig config = new AppConfig(
                new DonutApiConfig("http://127.0.0.1:" + server.getAddress().getPort(),
                        "test-key", 180, 220, 5),
                AnalyzerConfig.defaults(), FeeConfig.zero(), RiskConfig.defaults(),
                new CommodityRegistry(Set.of("minecraft:ender_pearl")), null);
        MarketService service = new MarketService(config.analyzer(), config.fees(), config.risk(),
                config.commodities(), new DonutApiClient(config.api()), db);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            exitCode = new Doctor(service).run();
        } finally {
            System.setOut(original);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private int exitCode;

    private static String goodPage() {
        StringBuilder sb = new StringBuilder("{\"status\":200,\"result\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(
                "{\"unixMillisDateSold\":%d,\"price\":%d," +
                "\"seller\":{\"uuid\":\"seller-%d\",\"name\":\"S%d\"}," +
                "\"item\":{\"id\":\"minecraft:ender_pearl\",\"count\":16}}",
                NOW - (i + 1) * 60_000L, 9000 + i * 10, i % 5, i % 5));
        }
        return sb.append("]}").toString();
    }

    @Test
    void healthyDataPasses() throws Exception {
        transactions.set(goodPage());
        String output = runDoctor();
        assertTrue(output.contains("PASS  API fetch"), output);
        assertTrue(output.contains("PASS  Transaction parsing"), output);
        assertTrue(output.contains("PASS  Hash stability"), output);
        assertTrue(output.contains("PASS  Database dedup"), output);
        assertTrue(output.contains("0 failures"), output);
        // Warns about thin history, which is correct on 20 records.
        assertEquals(1, exitCode, output);
    }

    @Test
    void unparseableResponseShapeFails() throws Exception {
        // Right envelope, completely different field names: the exact scenario
        // where the real API differs from our assumptions.
        transactions.set("{\"status\":200,\"result\":[{\"when\":123,\"cost\":5,\"thing\":\"x\"}]}");
        String output = runDoctor();
        assertTrue(output.contains("FAIL  Transaction parsing"), output);
        assertEquals(2, exitCode, output);
    }

    @Test
    void secondsInsteadOfMillisTimestampsAreCaught() throws Exception {
        // A classic unit bug: seconds where millis are expected.
        StringBuilder sb = new StringBuilder("{\"status\":200,\"result\":[");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(
                "{\"unixMillisDateSold\":%d,\"price\":9000," +
                "\"seller\":{\"uuid\":\"s%d\",\"name\":\"S\"}," +
                "\"item\":{\"id\":\"minecraft:ender_pearl\",\"count\":16}}",
                (NOW - i * 60_000L) / 1000, i % 5));
        }
        transactions.set(sb.append("]}").toString());
        String output = runDoctor();
        assertTrue(output.contains("FAIL  Timestamps plausible"), output);
        assertEquals(2, exitCode, output);
    }

    @Test
    void apiDownFailsFast() throws Exception {
        server.stop(0);
        String output = runDoctor();
        assertTrue(output.contains("FAIL  API fetch"), output);
        assertEquals(2, exitCode, output);
    }
}
