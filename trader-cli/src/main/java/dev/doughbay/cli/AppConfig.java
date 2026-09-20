package dev.doughbay.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.doughbay.api.DonutApiConfig;
import dev.doughbay.core.analysis.AnalyzerConfig;
import dev.doughbay.core.analysis.CommodityRegistry;
import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.analysis.RiskConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Loads settings from an optional doughbay.json next to the working
 * directory, with the API key coming from DONUT_API_KEY or the config file.
 * The key is never logged and must never be committed.
 */
public record AppConfig(
        DonutApiConfig api,
        AnalyzerConfig analyzer,
        FeeConfig fees,
        RiskConfig risk,
        CommodityRegistry commodities,
        Path databasePath
) {
    /**
     * Commands that only read the local database — {@code report}, {@code import} —
     * must not demand an API key. Losing access to your own trade history
     * because a key expired would be absurd, so those load with a placeholder
     * and never make a request.
     */
    private static final String NO_API_KEY_NEEDED = "local-only";

    public static AppConfig loadForLocalCommand(Path configFile) throws Exception {
        try {
            return load(configFile);
        } catch (IllegalArgumentException missingKey) {
            return load(configFile, NO_API_KEY_NEEDED);
        }
    }

    public static AppConfig load(Path configFile) throws Exception {
        return load(configFile, null);
    }

    private static AppConfig load(Path configFile, String fallbackApiKey) throws Exception {
        JsonNode root = null;
        if (configFile != null && Files.exists(configFile)) {
            root = new ObjectMapper().readTree(Files.readString(configFile));
        }

        String apiKey = System.getenv("DONUT_API_KEY");
        if ((apiKey == null || apiKey.isBlank()) && root != null && root.hasNonNull("apiKey")) {
            apiKey = root.get("apiKey").asText();
        }
        String baseUrl = root != null && root.hasNonNull("baseUrl")
                ? root.get("baseUrl").asText() : DonutApiConfig.DEFAULT_BASE_URL;

        DonutApiConfig api = new DonutApiConfig(
                baseUrl,
                apiKey == null || apiKey.isBlank()
                        ? (fallbackApiKey == null ? "" : fallbackApiKey) : apiKey,
                intOr(root, "targetRequestsPerMinute", 180),
                intOr(root, "hardMaxRequestsPerMinute", 220),
                intOr(root, "requestTimeoutSeconds", 20));

        FeeConfig fees = new FeeConfig(
                longOr(root, "listingFeeFlat", 0),
                doubleOr(root, "listingFeePercent", 0.0),
                doubleOr(root, "saleTaxPercent", 0.0));

        RiskConfig defaults = RiskConfig.defaults();
        RiskConfig risk = new RiskConfig(
                intOr(root, "minimumSamples", defaults.minimumSamples()),
                longOr(root, "minimumProfit", defaults.minimumProfit()),
                doubleOr(root, "minimumRoiPercent", defaults.minimumRoiPercent()),
                doubleOr(root, "maximumPositionPercent", defaults.maximumPositionPercent()),
                doubleOr(root, "bankrollReservePercent", defaults.bankrollReservePercent()),
                intOr(root, "maximumConcurrentPositions", defaults.maximumConcurrentPositions()),
                boolOr(root, "skipFallingMarkets", defaults.skipFallingMarkets()),
                boolOr(root, "commoditiesOnly", defaults.commoditiesOnly()),
                doubleOr(root, "maximumVolatility", defaults.maximumVolatility()),
                longOr(root, "maximumNewestSaleAgeMillis", defaults.maximumNewestSaleAgeMillis()),
                doubleOr(root, "maximumExpectedHoldHours", defaults.maximumExpectedHoldHours()),
                doubleOr(root, "minimumConfidence", defaults.minimumConfidence()),
                longOr(root, "undercutAmount", defaults.undercutAmount()));

        CommodityRegistry commodities;
        if (root != null && root.has("commodities") && root.get("commodities").isArray()) {
            Set<String> ids = new HashSet<>();
            root.get("commodities").forEach(n -> ids.add(n.asText()));
            commodities = new CommodityRegistry(ids);
        } else {
            commodities = CommodityRegistry.focusedDefaults();
        }

        Path db = Path.of(root != null && root.hasNonNull("databasePath")
                ? root.get("databasePath").asText() : "data/doughbay.db");

        return new AppConfig(api, AnalyzerConfig.defaults(), fees, risk, commodities, db);
    }

    private static int intOr(JsonNode root, String field, int fallback) {
        return root != null && root.hasNonNull(field) ? root.get(field).asInt(fallback) : fallback;
    }

    private static long longOr(JsonNode root, String field, long fallback) {
        return root != null && root.hasNonNull(field) ? root.get(field).asLong(fallback) : fallback;
    }

    private static double doubleOr(JsonNode root, String field, double fallback) {
        return root != null && root.hasNonNull(field) ? root.get(field).asDouble(fallback) : fallback;
    }

    private static boolean boolOr(JsonNode root, String field, boolean fallback) {
        return root != null && root.hasNonNull(field) ? root.get(field).asBoolean(fallback) : fallback;
    }
}
