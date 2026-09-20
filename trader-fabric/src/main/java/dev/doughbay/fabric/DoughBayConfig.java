package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.doughbay.core.analysis.CommodityRegistry;
import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.analysis.RiskConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mod configuration, stored under {@code .minecraft/config/doughbay/}.
 *
 * <p>The API key lives in {@code secret.dat}, separate from {@code config.json},
 * so the settings file can be shared or pasted for support without leaking the
 * key. Neither file is ever written to logs.
 */
public final class DoughBayConfig {

    /** Guards against a pasted file or log being written as a "key". */
    private static final int MAX_API_KEY_LENGTH = 512;
    /**
     * Hard ceiling on scanned markets. At roughly one request per market
     * per sweep and two sweeps a minute, this already sits above the
     * limiter's 180-per-minute pace, so anything larger only queues.
     */
    /** Each scanned market costs listing requests; the budget runs near a tenth of its limit at 80. */
    private static final int MAX_SCANNED_MARKETS = 200;
    private static final long MAX_CONFIGURED_MONEY = 1_000_000_000_000_000L;
    private static final int MAX_CONTINUOUS_TRADES = 100_000;
    private static final int MAX_CONTINUOUS_COOLDOWN_SECONDS = 3_600;
    private static final int MAX_CONTINUOUS_HOLD_MINUTES = 10_080;
    private static final double MAX_FEE_PERCENT = 100.0;
    private static final List<String> DEFAULT_NOTIFICATION_SERVERS = List.of(
            "donutsmp.net", "play.donutsmp.net");
    private static final List<String> REQUIRED_CONTINUOUS_FIELDS = List.of(
            "continuousAutomationEnabled",
            "continuousAutomationServers",
            "continuousMaxPurchasePrice",
            "continuousMaxSessionSpend",
            "continuousMaxTradesPerSession",
            "continuousMinimumProfit",
            "continuousMinimumRoiPercent",
            "continuousMinimumConfidencePercent",
            "continuousCooldownSeconds",
            "continuousReservedHotbarSlot",
            "continuousMaximumHoldMinutes");
    private static final List<String> REQUIRED_CONFIRMED_FEE_FIELDS = List.of(
            "auctionFeesConfirmed",
            "listingFeeFlat",
            "listingFeePercent",
            "saleTaxPercent");

    /** The addresses a player joins DonutSMP through; GoNuts trades nowhere else. */
    static final List<String> DONUT_SERVERS = List.of("donutsmp.net", "play.donutsmp.net");

    /**
     * What a fresh install starts with: everything set for DonutSMP and tuned
     * from a bot that has run there for weeks, so the only thing left between a
     * new player and a running trader is the Enable button on the Autopilot tab.
     *
     * <p>Live trading still ships switched off - the two {@code ...Enabled}
     * flags are the one deliberate opt-in. The allowlists, spend caps and trade
     * filters are already filled in, and the fee policy is confirmed at zero
     * because DonutSMP charges no auction fee or sale tax: the listed price is
     * the price received.
     */
    static final String DEFAULT_CONFIG_JSON = """
            {
              "collectionEnabled": true,
              "authorizedExecutionEnabled": false,
              "authorizedServers": ["donutsmp.net", "play.donutsmp.net"],
              "notificationServers": ["donutsmp.net", "play.donutsmp.net"],
              "preflightEnabled": false,
              "preflightServers": [],
              "continuousAutomationEnabled": false,
              "continuousAutomationServers": ["donutsmp.net", "play.donutsmp.net"],
              "continuousMaxPurchasePrice": 5000000,
              "continuousMaxSessionSpend": 75000000,
              "continuousMaxTradesPerSession": 50000,
              "continuousMinimumProfit": 350,
              "continuousMinimumRoiPercent": 2,
              "continuousMinimumConfidencePercent": 10,
              "continuousCooldownSeconds": 2,
              "continuousReservedHotbarSlot": 8,
              "continuousMaximumHoldMinutes": 240,
              "trackedCommodities": "focused",
              "autoSelectCommodities": true,
              "maxScannedMarkets": 90,
              "tradingAllocation": 0,
              "risk": {
                "minimumConfidencePercent": 10,
                "maximumVolatilityPercent": 60,
                "minimumRoiPercent": 2,
                "minimumProfit": 500,
                "minimumSamples": 8,
                "maximumHoldHours": 4,
                "maximumSaleAgeMinutes": 120,
                "maximumPositionPercent": 50,
                "maximumConcurrentPositions": 3,
                "skipFallingMarkets": true
              },
              "auctionFeesConfirmed": true,
              "listingFeeFlat": 0,
              "listingFeePercent": 0.0,
              "saleTaxPercent": 0.0,
              "startingPaperBalance": 2000000
            }
            """;

    /**
     * The trade filters the Enable button fills in, keyed by config field.
     * An existing config keeps any value its owner chose; only a missing field,
     * or one still sitting at the old shipped default (which allowed a single
     * trade per session), is brought up to these.
     */
    private static final java.util.Map<String, long[]> CONTINUOUS_RECOMMENDED = java.util.Map.of(
            // field -> { recommended, old shipped default }
            "continuousMaxTradesPerSession", new long[] {50_000, 1},
            "continuousMinimumProfit", new long[] {350, 5_000},
            "continuousMinimumRoiPercent", new long[] {2, 12},
            "continuousMinimumConfidencePercent", new long[] {10, 80},
            "continuousCooldownSeconds", new long[] {2, 10},
            "continuousReservedHotbarSlot", new long[] {8, 8},
            "continuousMaximumHoldMinutes", new long[] {240, 120});

    private final Path directory;
    /** An explicit shared database path from config.json, or null to use this client's own. */
    private final String databasePathConfig;
    private final Set<String> trackedCommodities;
    private final boolean autoSelectCommodities;
    private final int maxScannedMarkets;
    private final long tradingAllocation;
    private final RiskConfig riskConfig;
    private final String apiKey;
    /**
     * Where the auction data comes from.
     *
     * <p>DonutSMP's own API by default, and it will stay that way for anyone
     * with their own key. But a key is the thing standing between somebody
     * else and running this at all - you cannot hand a friend a mod and a
     * shrug about how to get one.
     *
     * <p>So the address is a setting. Point it at a service that answers in
     * the same shape and the mod cannot tell the difference: the parser is
     * the same, the medians are the same, every decision downstream is the
     * same. What changes is only who holds the key, and that can be one
     * person for everybody.
     */
    private final String apiBaseUrl;
    private final boolean collectionEnabled;
    private final boolean authorizedExecutionEnabled;
    private final List<String> authorizedServers;
    private final List<String> notificationServers;
    private final boolean preflightEnabled;
    private final List<String> preflightServers;
    private final boolean continuousAutomationEnabled;
    private final List<String> continuousAutomationServers;
    private final long continuousMaxPurchasePrice;
    private final long continuousMaxSessionSpend;
    private final int continuousMaxTradesPerSession;
    private final long continuousMinimumProfit;
    private final double continuousMinimumRoiPercent;
    private final double continuousMinimumConfidencePercent;
    private final int continuousCooldownSeconds;
    private final int continuousReservedHotbarSlot;
    private final int continuousMaximumHoldMinutes;
    private final int continuousMaxOpenListings;
    private final boolean auctionFeesConfirmed;
    private final FeeConfig auctionFees;
    private final long startingPaperBalance;

    private DoughBayConfig(Path directory, Set<String> trackedCommodities,
                           boolean autoSelectCommodities, int maxScannedMarkets,
                           long tradingAllocation, RiskConfig riskConfig,
                           String apiKey, String apiBaseUrl, boolean collectionEnabled,
                           boolean authorizedExecutionEnabled, List<String> authorizedServers,
                           List<String> notificationServers, boolean preflightEnabled,
                           List<String> preflightServers,
                           boolean continuousAutomationEnabled,
                           List<String> continuousAutomationServers,
                           long continuousMaxPurchasePrice,
                           long continuousMaxSessionSpend,
                           int continuousMaxTradesPerSession,
                           long continuousMinimumProfit,
                           double continuousMinimumRoiPercent,
                           double continuousMinimumConfidencePercent,
                           int continuousCooldownSeconds,
                           int continuousReservedHotbarSlot,
                           int continuousMaximumHoldMinutes,
                           int continuousMaxOpenListings,
                           boolean auctionFeesConfirmed,
                           FeeConfig auctionFees,
                           long startingPaperBalance,
                           String databasePathConfig) {
        this.directory = directory;
        this.databasePathConfig = databasePathConfig;
        this.trackedCommodities = Set.copyOf(trackedCommodities);
        this.autoSelectCommodities = autoSelectCommodities;
        this.maxScannedMarkets = maxScannedMarkets;
        this.tradingAllocation = tradingAllocation;
        this.riskConfig = riskConfig;
        this.apiKey = apiKey;
        this.apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? dev.doughbay.api.DonutApiConfig.DEFAULT_BASE_URL : apiBaseUrl.strip();
        this.collectionEnabled = collectionEnabled;
        this.authorizedExecutionEnabled = authorizedExecutionEnabled;
        this.authorizedServers = List.copyOf(authorizedServers);
        this.notificationServers = List.copyOf(notificationServers);
        this.preflightEnabled = preflightEnabled;
        this.preflightServers = List.copyOf(preflightServers);
        this.continuousAutomationEnabled = continuousAutomationEnabled;
        this.continuousAutomationServers = List.copyOf(continuousAutomationServers);
        this.continuousMaxPurchasePrice = continuousMaxPurchasePrice;
        this.continuousMaxSessionSpend = continuousMaxSessionSpend;
        this.continuousMaxTradesPerSession = continuousMaxTradesPerSession;
        this.continuousMinimumProfit = continuousMinimumProfit;
        this.continuousMinimumRoiPercent = continuousMinimumRoiPercent;
        this.continuousMinimumConfidencePercent = continuousMinimumConfidencePercent;
        this.continuousCooldownSeconds = continuousCooldownSeconds;
        this.continuousReservedHotbarSlot = continuousReservedHotbarSlot;
        this.continuousMaximumHoldMinutes = continuousMaximumHoldMinutes;
        this.continuousMaxOpenListings = continuousMaxOpenListings;
        this.auctionFeesConfirmed = auctionFeesConfirmed;
        this.auctionFees = auctionFees;
        this.startingPaperBalance = startingPaperBalance;
    }

    public static DoughBayConfig load() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("doughbay");
        String key = "";
        boolean collect = true;
        // Live commands and clicks are opt-in. Missing, malformed, or older
        // config files therefore remain analysis-only.
        CommandPermission authorizedPermission = CommandPermission.defaults();
        // Sale-message parsing is passive, but still server-scoped so another
        // server cannot imitate Donut's action-bar wording and earn a green
        // DoughBay confirmation. These are notification-only and never grant
        // execution permission.
        List<String> notificationServers = DEFAULT_NOTIFICATION_SERVERS;
        // The one-shot auction-search preflight is independently gated. It is
        // disabled with an empty allowlist by default, and it never inherits
        // permission from either notification or live-execution settings.
        CommandPermission preflightPermission = CommandPermission.defaults();
        // Continuous automation can issue both commands and clicks, so it has
        // its own opt-in and exact-server allowlist in addition to the live
        // execution gates. Zero spend caps make an older or partial config
        // unable to start a session.
        ContinuousPolicy continuousPolicy = ContinuousPolicy.defaults();
        // A literal zero-fee policy is valid only when the player explicitly
        // confirms it. Missing or malformed fee data therefore cannot make a
        // live trade look more profitable than it really is.
        FeePolicy feePolicy = FeePolicy.defaults();
        Set<String> trackedCommodities = CommodityRegistry.DEFAULT_FOCUSED_COMMODITIES;
        boolean autoSelect = false;
        int scanBudget = 0;
        long allocation = 0L;
        RiskConfig risk = RiskConfig.defaults();
        long balance = 2_000_000L;
        String apiBaseUrl = dev.doughbay.api.DonutApiConfig.DEFAULT_BASE_URL;
        String databasePathConfig = null;
        try {
            Files.createDirectories(dir);

            Path secret = dir.resolve("secret.dat");
            if (Files.exists(secret)) {
                key = Files.readString(secret).strip();
            } else {
                // Create an empty placeholder so the player knows where it goes.
                Files.writeString(secret, "");
            }
            // A per-client key in this client's own game folder wins over the
            // shared one. Two clients of a hive junction to a single config
            // directory, so they read the same secret.dat and the same token -
            // which the proxy then rate-limits as one, starving both. A token
            // dropped in the game folder (which is a client's own, never
            // shared) lets each hold a separate token and get its own budget.
            try {
                Path own = FabricLoader.getInstance().getGameDir().resolve("secret.dat");
                if (Files.exists(own)) {
                    String ownKey = Files.readString(own).strip();
                    if (!ownKey.isBlank()) {
                        key = ownKey;
                        DoughBayClient.LOGGER.info(
                                "DoughBay: using this client's own key from the game folder, not the shared one");
                    }
                }
            } catch (Exception ignored) {
                // no game-dir override; the shared key stands
            }
            // An environment variable wins over both, which keeps dev setups off disk.
            String fromEnv = System.getenv("DONUT_API_KEY");
            if (fromEnv != null && !fromEnv.isBlank()) {
                key = fromEnv.strip();
            }

            Path configFile = dir.resolve("config.json");
            // A fresh install writes the DonutSMP-ready defaults and then reads
            // them like any other config. Writing without reading used to leave
            // the very first launch on the hardcoded fallbacks, so a new player
            // saw different behaviour until they happened to restart.
            if (!Files.exists(configFile)) {
                Files.createDirectories(dir);
                Files.writeString(configFile, DEFAULT_CONFIG_JSON);
            }
            if (Files.exists(configFile)) {
                JsonNode root = new ObjectMapper().readTree(
                        stripByteOrderMark(Files.readString(configFile)));
                if (root.hasNonNull("collectionEnabled")) {
                    collect = root.get("collectionEnabled").asBoolean(true);
                }
                if (root.hasNonNull("apiBaseUrl")) {
                    String configured = root.get("apiBaseUrl").asText("").strip();
                    if (!configured.isBlank()) {
                        apiBaseUrl = configured;
                        DoughBayClient.LOGGER.info("DoughBay: auction data comes from {}", apiBaseUrl);
                    }
                }
                if (root.hasNonNull("databasePath")) {
                    String configured = root.get("databasePath").asText("").strip();
                    if (!configured.isBlank()) {
                        databasePathConfig = configured;
                        DoughBayClient.LOGGER.info("DoughBay: sharing the database at {}", databasePathConfig);
                    }
                }
                authorizedPermission = parseCommandPermission(root,
                        "authorizedExecutionEnabled", "authorizedServers");
                if (!authorizedPermission.valid()) {
                    DoughBayClient.LOGGER.warn(
                            "Authorized execution config is invalid and has been locked");
                }
                notificationServers = parseNotificationServers(root);
                preflightPermission = parseCommandPermission(root,
                        "preflightEnabled", "preflightServers");
                if (!preflightPermission.valid()) {
                    DoughBayClient.LOGGER.warn(
                            "Preflight config is invalid and has been locked");
                }
                continuousPolicy = parseContinuousPolicy(root);
                if (!continuousPolicy.valid()) {
                    DoughBayClient.LOGGER.warn(
                            "Continuous automation policy is invalid and has been locked");
                }
                feePolicy = parseFeePolicy(root);
                if (!feePolicy.valid()) {
                    DoughBayClient.LOGGER.warn(
                            "Auction fee policy is invalid and live execution has been locked");
                }
                trackedCommodities = parseTrackedCommodities(root, trackedCommodities);
                risk = parseRiskConfig(root, risk);
                if (root.hasNonNull("autoSelectCommodities")) {
                    autoSelect = root.get("autoSelectCommodities").asBoolean(false);
                }
                if (root.hasNonNull("maxScannedMarkets")) {
                    int parsed = root.get("maxScannedMarkets").asInt(0);
                    scanBudget = parsed > 0 && parsed <= MAX_SCANNED_MARKETS ? parsed : 0;
                    if (scanBudget != parsed) {
                        DoughBayClient.LOGGER.warn(
                                "maxScannedMarkets must be 1..{}; falling back to the tracked list size",
                                MAX_SCANNED_MARKETS);
                    }
                }
                if (root.hasNonNull("tradingAllocation")) {
                    long parsed = root.get("tradingAllocation").asLong(0L);
                    // Zero means "do not size positions", which is the shipped
                    // behaviour; a negative value is meaningless, not a limit.
                    allocation = parsed > 0 && parsed <= MAX_CONFIGURED_MONEY ? parsed : 0L;
                    if (allocation != parsed) {
                        DoughBayClient.LOGGER.warn(
                                "tradingAllocation is out of range; position sizing stays off");
                    }
                }
                if (root.hasNonNull("startingPaperBalance")) {
                    balance = root.get("startingPaperBalance").asLong(balance);
                }
            }
        } catch (Exception e) {
            // Never preserve a partially parsed permission if any later field
            // or filesystem operation fails. Startup authorization is atomic
            // and fail-closed for both independent command boundaries.
            authorizedPermission = CommandPermission.locked();
            preflightPermission = CommandPermission.locked();
            continuousPolicy = ContinuousPolicy.locked();
            feePolicy = FeePolicy.locked();
            DoughBayClient.LOGGER.warn("Could not read DoughBay config: {}", e.getMessage());
        }
        return new DoughBayConfig(dir, trackedCommodities, autoSelect, scanBudget,
                allocation, risk,
                key, apiBaseUrl, collect, authorizedPermission.enabled(),
                authorizedPermission.servers(), notificationServers,
                preflightPermission.enabled(), preflightPermission.servers(),
                continuousPolicy.enabled(),
                continuousPolicy.servers(), continuousPolicy.maxPurchasePrice(),
                continuousPolicy.maxSessionSpend(), continuousPolicy.maxTradesPerSession(),
                continuousPolicy.minimumProfit(), continuousPolicy.minimumRoiPercent(),
                continuousPolicy.minimumConfidencePercent(), continuousPolicy.cooldownSeconds(),
                continuousPolicy.reservedHotbarSlot(), continuousPolicy.maximumHoldMinutes(),
                continuousPolicy.maxOpenListings(),
                feePolicy.confirmed(), feePolicy.fees(),
                balance, databasePathConfig);
    }

    /**
     * The scanned commodity set: {@code "focused"} (default, ten markets),
     * {@code "all"} (every known commodity, roughly eight times the request
     * traffic), or an explicit array of namespaced item ids.
     *
     * <p>Unlike the execution gates, a malformed value here is not a security
     * question — it only widens or narrows read-only collection. It therefore
     * falls back to the focused set with a warning rather than locking.
     */
    static Set<String> parseTrackedCommodities(JsonNode root, Set<String> fallback) {
        JsonNode node = root == null ? null : root.get("trackedCommodities");
        if (node == null || node.isNull()) return fallback;

        if (node.isTextual()) {
            String mode = node.textValue().strip().toLowerCase(Locale.ROOT);
            if (mode.equals("focused")) return CommodityRegistry.DEFAULT_FOCUSED_COMMODITIES;
            if (mode.equals("all")) return CommodityRegistry.DEFAULT_COMMODITIES;
            DoughBayClient.LOGGER.warn(
                    "Unknown trackedCommodities mode '{}'; using the focused set", mode);
            return fallback;
        }
        if (node.isArray()) {
            List<String> ids = new ArrayList<>();
            for (JsonNode entry : node) {
                if (!entry.isTextual() || entry.textValue().isBlank()) {
                    DoughBayClient.LOGGER.warn(
                            "trackedCommodities holds a non-text entry; using the focused set");
                    return fallback;
                }
                ids.add(entry.textValue().strip());
            }
            if (ids.isEmpty()) {
                DoughBayClient.LOGGER.warn(
                        "trackedCommodities is empty; using the focused set");
                return fallback;
            }
            try {
                // Let the registry normalize and validate the ids: an unusable
                // entry must not silently drop a market the player asked for.
                return new CommodityRegistry(Set.copyOf(ids)).trackedIds();
            } catch (RuntimeException e) {
                DoughBayClient.LOGGER.warn(
                        "trackedCommodities contains an invalid item id; using the focused set");
                return fallback;
            }
        }
        DoughBayClient.LOGGER.warn(
                "trackedCommodities must be \"focused\", \"all\", or an array of item ids");
        return fallback;
    }

    /**
     * Opportunity thresholds from the optional {@code risk} object.
     *
     * <p>Confidence and volatility are written as percentages here because
     * that is how they are read on screen; the analyzer works in fractions.
     *
     * <p>A bad value falls back to its default with a warning rather than
     * locking anything. These are not security gates: a nonsensical ROI floor
     * produces bad suggestions, never unauthorized actions, so refusing to
     * start would be a worse failure than carrying on with the shipped number.
     */
    static RiskConfig parseRiskConfig(JsonNode root, RiskConfig fallback) {
        JsonNode node = root == null ? null : root.get("risk");
        if (node == null || node.isNull()) return fallback;
        if (!node.isObject()) {
            DoughBayClient.LOGGER.warn("risk must be an object; using default thresholds");
            return fallback;
        }

        int samples = (int) bounded(node, "minimumSamples",
                fallback.minimumSamples(), 1, 10_000);
        long profit = (long) bounded(node, "minimumProfit",
                fallback.minimumProfit(), 0, MAX_CONFIGURED_MONEY);
        double roi = bounded(node, "minimumRoiPercent",
                fallback.minimumRoiPercent(), 0, 100_000);
        double position = bounded(node, "maximumPositionPercent",
                fallback.maximumPositionPercent(), 0.01, 100);
        double reserve = bounded(node, "bankrollReservePercent",
                fallback.bankrollReservePercent(), 0, 99);
        int concurrent = (int) bounded(node, "maximumConcurrentPositions",
                fallback.maximumConcurrentPositions(), 1, 1_000);
        double volatility = bounded(node, "maximumVolatilityPercent",
                fallback.maximumVolatility() * 100, 0, 100_000) / 100.0;
        long saleAge = (long) bounded(node, "maximumSaleAgeMinutes",
                fallback.maximumNewestSaleAgeMillis() / 60_000.0, 1, 10_080) * 60_000L;
        double hold = bounded(node, "maximumHoldHours",
                fallback.maximumExpectedHoldHours(), 0.01, 168);
        double confidence = bounded(node, "minimumConfidencePercent",
                fallback.minimumConfidence() * 100, 0, 100) / 100.0;
        long undercut = (long) bounded(node, "undercutAmount",
                fallback.undercutAmount(), 0, MAX_CONFIGURED_MONEY);

        boolean skipFalling = booleanOr(node, "skipFallingMarkets",
                fallback.skipFallingMarkets());
        boolean commoditiesOnly = booleanOr(node, "commoditiesOnly",
                fallback.commoditiesOnly());

        return new RiskConfig(samples, profit, roi, position, reserve, concurrent,
                skipFalling, commoditiesOnly, volatility, saleAge, hold, confidence,
                undercut);
    }

    /** A finite number inside the range, or the fallback with a warning. */
    private static double bounded(JsonNode parent, String field,
                                  double fallback, double min, double max) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isNumber()) {
            DoughBayClient.LOGGER.warn("risk.{} is not a number; using {}", field, fallback);
            return fallback;
        }
        double parsed = value.doubleValue();
        if (!Double.isFinite(parsed) || parsed < min || parsed > max) {
            DoughBayClient.LOGGER.warn("risk.{} must be between {} and {}; using {}",
                    field, min, max, fallback);
            return fallback;
        }
        return parsed;
    }

    private static boolean booleanOr(JsonNode parent, String field, boolean fallback) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) return fallback;
        if (!value.isBoolean()) {
            DoughBayClient.LOGGER.warn("risk.{} is not a boolean; using {}", field, fallback);
            return fallback;
        }
        return value.booleanValue();
    }

    /** Pure strict parser for one command/click permission and its allowlist. */
    static CommandPermission parseCommandPermission(
            JsonNode root, String enabledField, String serversField) {
        if (root == null || !root.isObject()) return CommandPermission.locked();

        boolean enabled = false;
        JsonNode enabledNode = root.get(enabledField);
        if (enabledNode != null) {
            if (!enabledNode.isBoolean()) return CommandPermission.locked();
            enabled = enabledNode.booleanValue();
        }

        List<String> servers = List.of();
        JsonNode serversNode = root.get(serversField);
        if (serversNode != null) {
            if (!serversNode.isArray()) return CommandPermission.locked();
            List<String> parsed = new ArrayList<>();
            for (JsonNode entry : serversNode) {
                String identity = normalizedExactServerIdentity(entry);
                if (identity == null) return CommandPermission.locked();
                if (!parsed.contains(identity)) parsed.add(identity);
            }
            servers = List.copyOf(parsed);
        }
        return new CommandPermission(true, enabled, servers);
    }

    /**
     * Parses passive-notification trust independently. Invalid entries are
     * discarded; an explicitly malformed list shape trusts no servers.
     */
    static List<String> parseNotificationServers(JsonNode root) {
        if (root == null || !root.isObject()) return List.of();
        JsonNode serversNode = root.get("notificationServers");
        if (serversNode == null) return DEFAULT_NOTIFICATION_SERVERS;
        if (!serversNode.isArray()) return List.of();

        List<String> parsed = new ArrayList<>();
        for (JsonNode entry : serversNode) {
            String identity = normalizedExactServerIdentity(entry);
            if (identity != null && !parsed.contains(identity)) parsed.add(identity);
        }
        return List.copyOf(parsed);
    }

    private static String normalizedExactServerIdentity(JsonNode entry) {
        if (entry == null || !entry.isTextual()) return null;
        String raw = entry.textValue();
        if (raw == null) return null;
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 255
                || normalized.indexOf('*') >= 0 || normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0
                || containsWhitespaceOrControl(normalized)) {
            return null;
        }
        return normalized;
    }

    /** Pure, all-or-nothing parser kept package-private for focused tests. */
    static ContinuousPolicy parseContinuousPolicy(JsonNode root) {
        if (root == null || !root.isObject()) return ContinuousPolicy.locked();
        ContinuousPolicy defaults = ContinuousPolicy.defaults();

        JsonNode enabledNode = root.get("continuousAutomationEnabled");
        boolean enabled = defaults.enabled();
        if (enabledNode != null) {
            if (!enabledNode.isBoolean()) return ContinuousPolicy.locked();
            enabled = enabledNode.booleanValue();
        }

        ParsedValue<List<String>> servers = exactServersOr(
                root, "continuousAutomationServers", defaults.servers());
        ParsedValue<Long> maxPurchase = integralLongOr(root,
                "continuousMaxPurchasePrice", defaults.maxPurchasePrice(),
                0L, MAX_CONFIGURED_MONEY);
        ParsedValue<Long> maxSession = integralLongOr(root,
                "continuousMaxSessionSpend", defaults.maxSessionSpend(),
                0L, MAX_CONFIGURED_MONEY);
        ParsedValue<Integer> maxTrades = integralIntOr(root,
                "continuousMaxTradesPerSession", defaults.maxTradesPerSession(),
                1, MAX_CONTINUOUS_TRADES);
        ParsedValue<Long> minimumProfit = integralLongOr(root,
                "continuousMinimumProfit", defaults.minimumProfit(),
                1L, MAX_CONFIGURED_MONEY);
        ParsedValue<Double> minimumRoi = finiteDoubleOr(root,
                "continuousMinimumRoiPercent", defaults.minimumRoiPercent(),
                0.0, 100_000.0, true);
        ParsedValue<Double> minimumConfidence = finiteDoubleOr(root,
                "continuousMinimumConfidencePercent", defaults.minimumConfidencePercent(),
                0.0, 100.0, true);
        ParsedValue<Integer> cooldown = integralIntOr(root,
                "continuousCooldownSeconds", defaults.cooldownSeconds(),
                1, MAX_CONTINUOUS_COOLDOWN_SECONDS);
        ParsedValue<Integer> reservedSlot = integralIntOr(root,
                "continuousReservedHotbarSlot", defaults.reservedHotbarSlot(), 0, 8);
        ParsedValue<Integer> maximumHold = integralIntOr(root,
                "continuousMaximumHoldMinutes", defaults.maximumHoldMinutes(),
                1, MAX_CONTINUOUS_HOLD_MINUTES);
        // Optional: how many listings may be up at once before the session
        // waits for a sale. The auction house allows 45, or 90 with the top
        // rank; the live "slots.max" setting is what the session actually uses.
        ParsedValue<Integer> maxOpen = integralIntOr(root,
                "continuousMaxOpenListings", defaults.maxOpenListings(), 1, 90);

        if (!servers.valid() || !maxPurchase.valid() || !maxSession.valid()
                || !maxTrades.valid() || !minimumProfit.valid() || !minimumRoi.valid()
                || !minimumConfidence.valid() || !cooldown.valid()
                || !reservedSlot.valid() || !maximumHold.valid() || !maxOpen.valid()) {
            return ContinuousPolicy.locked();
        }

        if (enabled) {
            for (String field : REQUIRED_CONTINUOUS_FIELDS) {
                if (!root.has(field)) return ContinuousPolicy.locked();
            }
            if (servers.value().isEmpty()
                    || maxPurchase.value() <= 0L
                    || maxSession.value() <= 0L) {
                return ContinuousPolicy.locked();
            }
        }

        return new ContinuousPolicy(true, enabled, servers.value(),
                maxPurchase.value(), maxSession.value(), maxTrades.value(),
                minimumProfit.value(), minimumRoi.value(), minimumConfidence.value(),
                cooldown.value(), reservedSlot.value(), maximumHold.value(),
                maxOpen.value());
    }

    /**
     * Strict, all-or-nothing auction-cost policy for live decisions. A
     * confirmed zero is different from a missing value: confirmation requires
     * every field to be present and correctly typed.
     */
    static FeePolicy parseFeePolicy(JsonNode root) {
        if (root == null || !root.isObject()) return FeePolicy.locked();

        JsonNode confirmedNode = root.get("auctionFeesConfirmed");
        boolean confirmed = false;
        if (confirmedNode != null) {
            if (!confirmedNode.isBoolean()) return FeePolicy.locked();
            confirmed = confirmedNode.booleanValue();
        }

        ParsedValue<Long> flat = integralLongOr(
                root, "listingFeeFlat", 0L, 0L, MAX_CONFIGURED_MONEY);
        ParsedValue<Double> listingPercent = finiteDoubleOr(
                root, "listingFeePercent", 0.0, 0.0, MAX_FEE_PERCENT, false);
        ParsedValue<Double> saleTaxPercent = finiteDoubleOr(
                root, "saleTaxPercent", 0.0, 0.0, MAX_FEE_PERCENT, false);
        if (!flat.valid() || !listingPercent.valid() || !saleTaxPercent.valid()) {
            return FeePolicy.locked();
        }

        if (confirmed) {
            for (String field : REQUIRED_CONFIRMED_FEE_FIELDS) {
                if (!root.has(field)) return FeePolicy.locked();
            }
            // One sale cannot consume 100% or more in percentage costs and
            // still support a meaningful positive-profit live strategy.
            if (listingPercent.value() + saleTaxPercent.value() >= MAX_FEE_PERCENT) {
                return FeePolicy.locked();
            }
        }

        return new FeePolicy(true, confirmed,
                new FeeConfig(flat.value(), listingPercent.value(), saleTaxPercent.value()));
    }

    private static ParsedValue<List<String>> exactServersOr(
            JsonNode root, String field, List<String> fallback) {
        JsonNode values = root.get(field);
        if (values == null) return ParsedValue.valid(fallback);
        if (!values.isArray()) return ParsedValue.invalid();

        List<String> servers = new ArrayList<>();
        for (JsonNode server : values) {
            if (!server.isTextual()) return ParsedValue.invalid();
            String raw = server.textValue();
            if (raw == null || raw.isBlank() || raw.length() > 255
                    || !raw.equals(raw.strip())
                    || !raw.equals(raw.toLowerCase(Locale.ROOT))
                    || raw.indexOf('*') >= 0 || raw.indexOf('/') >= 0
                    || raw.indexOf('\\') >= 0 || containsWhitespaceOrControl(raw)
                    || servers.contains(raw)) {
                return ParsedValue.invalid();
            }
            servers.add(raw);
        }
        return ParsedValue.valid(List.copyOf(servers));
    }

    private static boolean containsWhitespaceOrControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                return true;
            }
        }
        return false;
    }

    private static ParsedValue<Long> integralLongOr(
            JsonNode root, String field, long fallback, long minimum, long maximum) {
        JsonNode value = root.get(field);
        if (value == null) return ParsedValue.valid(fallback);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            return ParsedValue.invalid();
        }
        long parsed = value.longValue();
        return parsed >= minimum && parsed <= maximum
                ? ParsedValue.valid(parsed) : ParsedValue.invalid();
    }

    private static ParsedValue<Integer> integralIntOr(
            JsonNode root, String field, int fallback, int minimum, int maximum) {
        JsonNode value = root.get(field);
        if (value == null) return ParsedValue.valid(fallback);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            return ParsedValue.invalid();
        }
        int parsed = value.intValue();
        return parsed >= minimum && parsed <= maximum
                ? ParsedValue.valid(parsed) : ParsedValue.invalid();
    }

    private static ParsedValue<Double> finiteDoubleOr(
            JsonNode root, String field, double fallback, double minimum,
            double maximum, boolean minimumExclusive) {
        JsonNode value = root.get(field);
        if (value == null) return ParsedValue.valid(fallback);
        if (!value.isNumber()) return ParsedValue.invalid();
        double parsed = value.doubleValue();
        boolean aboveMinimum = minimumExclusive ? parsed > minimum : parsed >= minimum;
        return Double.isFinite(parsed) && aboveMinimum && parsed <= maximum
                ? ParsedValue.valid(parsed) : ParsedValue.invalid();
    }

    private record ParsedValue<T>(boolean valid, T value) {
        private static <T> ParsedValue<T> valid(T value) {
            return new ParsedValue<>(true, value);
        }

        private static <T> ParsedValue<T> invalid() {
            return new ParsedValue<>(false, null);
        }
    }

    record CommandPermission(boolean valid, boolean enabled, List<String> servers) {
        CommandPermission {
            servers = List.copyOf(servers);
        }

        private static CommandPermission defaults() {
            return new CommandPermission(true, false, List.of());
        }

        private static CommandPermission locked() {
            return new CommandPermission(false, false, List.of());
        }
    }

    record ContinuousPolicy(
            boolean valid,
            boolean enabled,
            List<String> servers,
            long maxPurchasePrice,
            long maxSessionSpend,
            int maxTradesPerSession,
            long minimumProfit,
            double minimumRoiPercent,
            double minimumConfidencePercent,
            int cooldownSeconds,
            int reservedHotbarSlot,
            int maximumHoldMinutes,
            int maxOpenListings) {

        private static ContinuousPolicy defaults() {
            return new ContinuousPolicy(true, false, List.of(),
                    0L, 0L, 1, 5_000L, 12.0, 80.0, 10, 8, 120, 5);
        }

        private static ContinuousPolicy locked() {
            return new ContinuousPolicy(false, false, List.of(),
                    0L, 0L, 0, 0L, 0.0, 0.0, 0, 0, 0, 0);
        }
    }

    record FeePolicy(boolean valid, boolean confirmed, FeeConfig fees) {
        FeePolicy {
            fees = fees == null ? FeeConfig.zero() : fees;
        }

        private static FeePolicy defaults() {
            return new FeePolicy(true, false, FeeConfig.zero());
        }

        private static FeePolicy locked() {
            return new FeePolicy(false, false, FeeConfig.zero());
        }
    }

    public Path directory() {
        return directory;
    }

    /**
     * Commodities whose active books are scanned each sweep.
     *
     * <p>Every extra commodity is at least one more API request per sweep, so
     * this is a budget decision rather than a claim about what is profitable.
     * The focused ten fit comfortably inside the request budget; the full set
     * is roughly eight times the traffic and will be paced by the rate limiter.
     */
    public Set<String> trackedCommodities() {
        return trackedCommodities;
    }

    /**
     * Thresholds an opportunity must clear.
     *
     * <p>Tuning, not permission: these decide what DoughBay is willing to
     * <em>suggest</em>, and loosening them cannot make it act. Every execution
     * gate remains separate and fail-closed.
     */
    public RiskConfig riskConfig() {
        return riskConfig;
    }

    /**
     * How many markets to fetch active listings for each sweep.
     *
     * <p>This is the API budget, expressed in markets. Each one costs roughly
     * one request per sweep, and there are two sweeps a minute, against a
     * limiter that paces at 180 requests a minute. Scanning more markets is
     * the only way to find more opportunities, because a listing has to be
     * fetched before it can be judged underpriced.
     *
     * <p>Defaults to the size of the configured list, which is what it was
     * implicitly before this became its own setting.
     */
    public int maxScannedMarkets() {
        return maxScannedMarkets > 0 ? maxScannedMarkets : trackedCommodities.size();
    }

    /** Whether the scanned set is re-chosen from observed history. */
    public boolean autoSelectCommodities() {
        return autoSelectCommodities;
    }

    /**
     * Capital deliberately committed to trading, or zero for none.
     *
     * <p>Deliberately not the account balance. Every risk rule is a percentage
     * of this number — a tenth per position, a quarter held in reserve — and
     * those only mean something against money set aside on purpose. Sizing
     * against total wealth would let one trade consume a share of funds that
     * were never meant to be at risk, and would move whenever the balance did.
     */
    public long tradingAllocation() {
        return tradingAllocation;
    }

    public Path databasePath() {
        // A shared database lets several clients pool one price history and
        // record each sale once, without junctioning their whole config folder:
        // each keeps its own key and settings and points here. Relative paths
        // resolve against this client's config folder.
        if (databasePathConfig != null && !databasePathConfig.isBlank()) {
            Path p = Path.of(databasePathConfig.strip());
            return p.isAbsolute() ? p : directory.resolve(p);
        }
        return directory.resolve("doughbay.db");
    }

    public boolean hasApiKey() {
        return !apiKey.isBlank();
    }

    public String apiKey() {
        return apiKey;
    }

    /** The auction API this client talks to; DonutSMP's unless told otherwise. */
    public String apiBaseUrl() {
        return apiBaseUrl;
    }

    /** Whether the data is coming from somewhere other than DonutSMP itself. */
    public boolean usesOwnApi() {
        return !dev.doughbay.api.DonutApiConfig.DEFAULT_BASE_URL.equals(apiBaseUrl);
    }

    /**
     * A copy that differs only by API key.
     *
     * <p>Permissions, allowlists, fee policy, and continuous limits are
     * carried over verbatim rather than reread from disk. Rereading
     * {@code config.json} here would let an in-game key change silently
     * hot-apply edited execution permissions, which are deliberately
     * startup-only.
     */
    public DoughBayConfig withApiKey(String key) {
        return new DoughBayConfig(directory, trackedCommodities, autoSelectCommodities,
                maxScannedMarkets, tradingAllocation, riskConfig,
                key == null ? "" : key.strip(), apiBaseUrl, collectionEnabled, authorizedExecutionEnabled, authorizedServers,
                notificationServers, preflightEnabled, preflightServers,
                continuousAutomationEnabled, continuousAutomationServers,
                continuousMaxPurchasePrice, continuousMaxSessionSpend,
                continuousMaxTradesPerSession, continuousMinimumProfit,
                continuousMinimumRoiPercent, continuousMinimumConfidencePercent,
                continuousCooldownSeconds, continuousReservedHotbarSlot,
                continuousMaximumHoldMinutes, continuousMaxOpenListings,
                auctionFeesConfirmed, auctionFees,
                startingPaperBalance, databasePathConfig);
    }

    /** A copy that differs only by which auction API it talks to. */
    public DoughBayConfig withApiBaseUrl(String url) {
        String u = url == null || url.isBlank()
                ? dev.doughbay.api.DonutApiConfig.DEFAULT_BASE_URL : url.strip();
        return new DoughBayConfig(directory, trackedCommodities, autoSelectCommodities,
                maxScannedMarkets, tradingAllocation, riskConfig,
                apiKey, u, collectionEnabled, authorizedExecutionEnabled, authorizedServers,
                notificationServers, preflightEnabled, preflightServers,
                continuousAutomationEnabled, continuousAutomationServers,
                continuousMaxPurchasePrice, continuousMaxSessionSpend,
                continuousMaxTradesPerSession, continuousMinimumProfit,
                continuousMinimumRoiPercent, continuousMinimumConfidencePercent,
                continuousCooldownSeconds, continuousReservedHotbarSlot,
                continuousMaximumHoldMinutes, continuousMaxOpenListings,
                auctionFeesConfirmed, auctionFees,
                startingPaperBalance, databasePathConfig);
    }

    /**
     * Persists the auction data source into {@code config.json}, leaving every
     * other setting in the file untouched. Only this one field is rewritten, so
     * switching sources in game can never hot-apply an edited execution
     * permission - those are read once at startup, exactly as before.
     */
    public static void saveApiBaseUrl(Path directory, String url) throws IOException {
        Files.createDirectories(directory);
        Path cfg = directory.resolve("config.json");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root;
        if (Files.exists(cfg)) {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(Files.readString(cfg));
            root = node != null && node.isObject()
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) node : mapper.createObjectNode();
        } else {
            root = mapper.createObjectNode();
        }
        String u = url == null || url.isBlank()
                ? dev.doughbay.api.DonutApiConfig.DEFAULT_BASE_URL : url.strip();
        root.put("apiBaseUrl", u);
        Files.writeString(cfg, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    /**
     * Turns live trading on or off for DonutSMP in one step: the in-game
     * counterpart of hand-editing five separate gates in this file.
     *
     * <p>Enabling writes both command allowlists and the notification list
     * (plus the exact address the player joined through, when it is a DonutSMP
     * address), confirms the fee policy at DonutSMP's zero, sets the two spend
     * caps, and fills any trade filter that is missing or still sitting at the
     * old one-trade-per-session default. Anything the owner already chose is
     * kept. Disabling only clears the two enable flags, so switching back on
     * later restores the same setup.
     *
     * <p>Called only from the Autopilot card after an explicit confirm: the one
     * place the game may widen what GoNuts is allowed to do. Every other
     * in-game path still leaves permissions exactly as they were loaded.
     */
    public static void saveDonutAutomation(Path directory, boolean enabled, long maxPerTrade,
                                           long maxPerSession, String joinedAddress) throws IOException {
        if (enabled) {
            if (maxPerTrade <= 0 || maxPerSession <= 0) {
                throw new IllegalArgumentException("Set both spend limits above zero");
            }
            if (maxPerTrade > maxPerSession) {
                throw new IllegalArgumentException("The per-trade limit can't be above the per-session limit");
            }
            if (maxPerSession > MAX_CONFIGURED_MONEY) {
                throw new IllegalArgumentException("That spend limit is too large");
            }
        }
        Files.createDirectories(directory);
        Path cfg = directory.resolve("config.json");
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root;
        JsonNode node = Files.exists(cfg)
                ? mapper.readTree(stripByteOrderMark(Files.readString(cfg)))
                : mapper.readTree(DEFAULT_CONFIG_JSON);
        root = node != null && node.isObject()
                ? (com.fasterxml.jackson.databind.node.ObjectNode) node
                : (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(DEFAULT_CONFIG_JSON);
        if (enabled) {
            List<String> servers = new ArrayList<>(DONUT_SERVERS);
            String joined = joinedAddress == null
                    ? "" : joinedAddress.strip().toLowerCase(java.util.Locale.ROOT);
            if (joined.contains("donutsmp") && !servers.contains(joined)) servers.add(joined);
            mergeServers(root, "authorizedServers", servers);
            mergeServers(root, "continuousAutomationServers", servers);
            mergeServers(root, "notificationServers", servers);
            root.put("continuousMaxPurchasePrice", maxPerTrade);
            root.put("continuousMaxSessionSpend", maxPerSession);
            for (var e : CONTINUOUS_RECOMMENDED.entrySet()) {
                JsonNode current = root.get(e.getKey());
                if (current == null || !current.isNumber() || current.asLong() == e.getValue()[1]) {
                    root.put(e.getKey(), e.getValue()[0]);
                }
            }
            root.put("auctionFeesConfirmed", true);
            if (!root.has("listingFeeFlat")) root.put("listingFeeFlat", 0);
            if (!root.has("listingFeePercent")) root.put("listingFeePercent", 0.0);
            if (!root.has("saleTaxPercent")) root.put("saleTaxPercent", 0.0);
        }
        root.put("authorizedExecutionEnabled", enabled);
        root.put("continuousAutomationEnabled", enabled);
        Files.writeString(cfg, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }

    /** Adds {@code add} to a server list in place, keeping existing entries and order. */
    private static void mergeServers(com.fasterxml.jackson.databind.node.ObjectNode root,
                                     String field, List<String> add) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        JsonNode existing = root.get(field);
        if (existing != null && existing.isArray()) {
            for (JsonNode s : existing) {
                if (s.isTextual() && !s.asText().isBlank()) {
                    merged.add(s.asText().strip().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        merged.addAll(add);
        com.fasterxml.jackson.databind.node.ArrayNode array = root.putArray(field);
        merged.forEach(array::add);
    }

    /**
     * Whether {@code DONUT_API_KEY} is set in the environment.
     *
     * <p>{@link #load()} lets the environment win over {@code secret.dat}, so
     * a key typed in game would appear to save and then be ignored. The UI
     * shows this instead of silently disagreeing with itself.
     */
    public static boolean apiKeyOverriddenByEnvironment() {
        String fromEnv = System.getenv("DONUT_API_KEY");
        return fromEnv != null && !fromEnv.isBlank();
    }

    /**
     * Persists the key to {@code secret.dat} so it survives a restart.
     *
     * <p>The value itself is never returned, logged, or included in the
     * failure text — callers only learn whether the write succeeded.
     */
    public static void saveApiKey(Path directory, String key) throws IOException {
        String trimmed = key == null ? "" : key.strip();
        if (trimmed.length() > MAX_API_KEY_LENGTH) {
            throw new IOException("That value is too long to be a Donut API key");
        }
        if (trimmed.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
            // A pasted log line or file fragment would otherwise be written
            // verbatim and then fail much later as a confusing auth error.
            throw new IOException("The key contains line breaks or control characters");
        }
        Files.createDirectories(directory);
        Path secret = directory.resolve("secret.dat");
        Files.writeString(secret, trimmed);
        restrictToOwner(secret);
    }

    /**
     * Removes a leading UTF-8 byte order mark.
     *
     * <p>Windows editors and PowerShell's {@code Out-File} write one by
     * default, and Jackson rejects it as an unexpected character. Without this
     * the whole file fails to parse, which silently reverts every analysis
     * setting to its default and locks every permission — a config that looks
     * correct on screen but does nothing.
     */
    static String stripByteOrderMark(String json) {
        return json != null && !json.isEmpty() && json.charAt(0) == '﻿'
                ? json.substring(1) : json;
    }

    /**
     * Best-effort owner-only permissions. Windows uses ACLs rather than POSIX
     * bits, so an unsupported filesystem is an expected no-op, not an error.
     */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // Nothing to do: the key is stored in plaintext by design here.
        }
    }


    public boolean collectionEnabled() {
        return collectionEnabled;
    }

    /**
     * Whether private/authorized live execution was explicitly opted into.
     * The default is false and a config read failure never enables it.
     */
    public boolean authorizedExecutionEnabled() {
        return authorizedExecutionEnabled;
    }

    /**
     * Exact lower-case server addresses explicitly authorized for execution.
     * Use "singleplayer" to permit an integrated private test world.
     */
    public List<String> authorizedServers() {
        return authorizedServers;
    }

    /** Exact lower-case servers trusted to emit Donut sale action-bar messages. */
    public List<String> notificationServers() {
        return notificationServers;
    }

    /**
     * Whether the one-shot, search-only auction preflight was explicitly
     * enabled. This permission cannot enable purchases, clicks, or listings.
     */
    public boolean preflightEnabled() {
        return preflightEnabled;
    }

    /**
     * Exact lower-case server addresses permitted for the search-only
     * preflight. This is intentionally separate from both other allowlists.
     */
    public List<String> preflightServers() {
        return preflightServers;
    }

    /**
     * Independent opt-in for a bounded continuous trading session. A session
     * must additionally pass the ordinary live-execution gates.
     */
    public boolean continuousAutomationEnabled() {
        return continuousAutomationEnabled;
    }

    /** Exact lower-case servers permitted to run continuous automation. */
    public List<String> continuousAutomationServers() {
        return continuousAutomationServers;
    }

    /** Per-trade purchase ceiling. Zero intentionally blocks session start. */
    public long continuousMaxPurchasePrice() {
        return continuousMaxPurchasePrice;
    }

    /** Total purchase ceiling for one session. Zero blocks session start. */
    public long continuousMaxSessionSpend() {
        return continuousMaxSessionSpend;
    }

    public int continuousMaxTradesPerSession() {
        return continuousMaxTradesPerSession;
    }

    public long continuousMinimumProfit() {
        return continuousMinimumProfit;
    }

    public double continuousMinimumRoiPercent() {
        return continuousMinimumRoiPercent;
    }

    public double continuousMinimumConfidencePercent() {
        return continuousMinimumConfidencePercent;
    }

    public int continuousCooldownSeconds() {
        return continuousCooldownSeconds;
    }

    /** Zero-based slot index; the default 8 is the player's ninth hotbar slot. */
    public int continuousReservedHotbarSlot() {
        return continuousReservedHotbarSlot;
    }

    public int continuousMaximumHoldMinutes() {
        return continuousMaximumHoldMinutes;
    }

    public int continuousMaxOpenListings() {
        return continuousMaxOpenListings;
    }

    /** Whether auction costs were explicitly checked, including a real zero. */
    public boolean auctionFeesConfirmed() {
        return auctionFeesConfirmed;
    }

    /** Strictly parsed listing fee and sale-tax values used by live valuation. */
    public FeeConfig auctionFees() {
        return auctionFees;
    }

    public long startingPaperBalance() {
        return startingPaperBalance;
    }
}
