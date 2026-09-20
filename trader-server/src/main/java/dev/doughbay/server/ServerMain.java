package dev.doughbay.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.doughbay.api.DonutApiClient;
import dev.doughbay.cli.AppConfig;
import dev.doughbay.engine.MarketService;
import dev.doughbay.storage.Database;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The always-on DoughBay data server: one Donut API key polls the sales and
 * listing feeds around the clock into the same database schema the mod
 * uses, and a small HTTP API serves that history and the derived market
 * statistics to any number of clients under their own keys.
 *
 * <p>Configuration is {@code doughbay-server.json} (the same keys as the
 * CLI's {@code doughbay.json}, plus {@code port} and {@code adminToken}), or
 * the environment variables {@code DONUT_API_KEY}, {@code PORT} and
 * {@code DOUGHBAY_ADMIN_TOKEN}.
 */
public final class ServerMain {
    private ServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(args.length > 0 ? args[0] : "doughbay-server.json");
        AppConfig config = AppConfig.load(configFile);
        JsonNode root = Files.exists(configFile)
                ? new ObjectMapper().readTree(Files.readString(configFile)) : null;
        int port = envInt("PORT", root != null && root.hasNonNull("port") ? root.get("port").asInt() : 8787);
        String adminToken = System.getenv("DOUGHBAY_ADMIN_TOKEN");
        if ((adminToken == null || adminToken.isBlank()) && root != null && root.hasNonNull("adminToken")) {
            adminToken = root.get("adminToken").asText();
        }
        if (adminToken == null || adminToken.isBlank()) {
            System.err.println("Set adminToken in the config or DOUGHBAY_ADMIN_TOKEN; it is needed to mint API keys.");
            System.exit(2);
            return;
        }
        long salesEvery = envLong("SALES_POLL_SECONDS", longOr(root, "salesPollSeconds", 10));
        long listingsEvery = envLong("LISTINGS_POLL_SECONDS", longOr(root, "listingsPollSeconds", 300));
        long statsEvery = envLong("STATS_SECONDS", longOr(root, "statsSeconds", 60));

        Path databasePath = config.databasePath();
        try (Database db = new Database(databasePath)) {
            ServerStore.ensureTables(db);
        }
        DonutApiClient client = new DonutApiClient(config.api());
        Collector collector = new Collector(config, client, databasePath,
                salesEvery * 1000, listingsEvery * 1000, statsEvery * 1000);
        collector.setLiveEveryMillis(envLong("LIVE_LISTINGS_SECONDS", longOr(root, "liveListingsSeconds", 5)) * 1000);
        collector.start();
        Api api = new Api(port, databasePath, collector, client, config, adminToken);
        api.start();
        System.out.printf("DoughBay server on port %d, database %s, polling sales every %ds, listings every %ds%n",
                port, databasePath, salesEvery, listingsEvery);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.stop();
            collector.stop();
        }));
        Thread.currentThread().join();
    }

    static MarketService service(AppConfig config, DonutApiClient client, Database db) {
        return new MarketService(config.analyzer(), config.fees(), config.risk(),
                config.commodities(), client, db);
    }

    private static int envInt(String name, int fallback) {
        String v = System.getenv(name);
        try {
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long envLong(String name, long fallback) {
        String v = System.getenv(name);
        try {
            return v == null || v.isBlank() ? fallback : Long.parseLong(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longOr(JsonNode root, String field, long fallback) {
        return root != null && root.hasNonNull(field) ? root.get(field).asLong() : fallback;
    }
}
