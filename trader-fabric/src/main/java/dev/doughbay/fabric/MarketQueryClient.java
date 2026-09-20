package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.StackBucket;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only lookups against the DoughBay site's public routes, for the in-game
 * search box.
 *
 * <p>The mod otherwise only ever writes to the VPS - the order ingest. This is
 * the other direction: the same query API the website reads from
 * ({@code /v1/markets}, {@code /v1/seller}, {@code /v1/rivals}), served by the
 * same host as {@code apiBaseUrl} and needing no token. The routes are rate
 * limited per address, so the two list routes are cached for a minute and a
 * {@code 429} backs the client off; matching runs on the cached rows, so a
 * keystroke never touches the network.
 *
 * <p>Every fetch runs on this client's own single thread, never the render
 * thread that calls in. The screen reads whatever is cached now and the cache
 * fills in behind it; the first frame after a cold open shows nothing and the
 * one a moment later shows the rows.
 */
public final class MarketQueryClient {
    private static final long LIST_CACHE_MILLIS = 60_000L;
    private static final long BACKOFF_MILLIS = 60_000L;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final int SELLER_LIMIT = 500;

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DoughBay-market-query");
        t.setDaemon(true);
        return t;
    });

    /** One tracked market's statistics, and the seller count the list route also carries. */
    public record Market(MarketStats stats, int uniqueSellers) {
    }

    /** A search result that resolves to an item: its id and the name shown in the list. */
    public record ItemMatch(String itemId, String displayName) {
    }

    /** One seller in the last-24h leaderboard from {@code /v1/rivals}. */
    public record Rival(String seller, int sales, long revenue, int items,
                        int activeHours, long lastSaleAt, int botScore) {
    }

    /** One completed sale in a seller's history. */
    public record Sale(long soldAt, String sellerName, String itemId,
                       int itemCount, long totalPrice) {
        public long unitPrice() {
            return itemCount > 0 ? totalPrice / itemCount : totalPrice;
        }
    }

    /** A seller lookup in flight or done; the screen reads this each frame. */
    public static final class SellerView {
        final String name;
        volatile boolean loading = true;
        volatile String error;
        volatile List<Sale> sales = List.of();

        SellerView(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public boolean loading() {
            return loading;
        }

        public String error() {
            return error;
        }

        public List<Sale> sales() {
            return sales;
        }

        public long revenue() {
            long sum = 0;
            for (Sale s : sales) sum += s.totalPrice();
            return sum;
        }
    }

    private volatile List<Market> markets = List.of();
    private volatile long marketsAt;
    private final AtomicBoolean marketsFetching = new AtomicBoolean(false);

    private volatile List<Rival> rivals = List.of();
    private volatile long rivalsAt;
    private final AtomicBoolean rivalsFetching = new AtomicBoolean(false);

    private volatile long blockedUntil;

    private volatile SellerView seller;

    /**
     * When true the lookups read the DoughBay site's routes over HTTP; when
     * false they read this client's own local database instead. A build pointed
     * straight at DonutSMP's API has no DoughBay site to ask, so it searches the
     * sales it has collected itself - the same store the rest of the mod values
     * from - and never calls out to anything but DonutSMP.
     */
    private final boolean useServer;

    public MarketQueryClient(String apiBaseUrl, boolean useServer) {
        String base = apiBaseUrl == null ? "" : apiBaseUrl.strip();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.baseUrl = base;
        this.useServer = useServer;
    }

    private boolean blocked() {
        return System.currentTimeMillis() < blockedUntil;
    }

    // ---- markets ------------------------------------------------------------

    /** The cached market rows, kicking a refresh if they are stale. Never blocks. */
    public List<Market> markets() {
        if (!useServer) return localMarkets();
        long now = System.currentTimeMillis();
        if (!blocked() && now - marketsAt > LIST_CACHE_MILLIS && marketsFetching.compareAndSet(false, true)) {
            worker.submit(this::fetchMarkets);
        }
        return markets;
    }

    /** The markets this build has valued from its own collected sales. */
    private List<Market> localMarkets() {
        MarketWatcher watcher = DoughBayClient.watcher();
        if (watcher == null) return List.of();
        List<Market> out = new ArrayList<>();
        for (MarketStats s : watcher.snapshot().markets()) {
            if (s == null || s.itemKey() == null) continue;
            out.add(new Market(s, s.uniqueSellers()));
        }
        return out;
    }

    private void fetchMarkets() {
        try {
            String body = get("/v1/markets");
            List<Market> parsed = parseMarkets(body);
            if (parsed != null) {
                markets = parsed;
                marketsAt = System.currentTimeMillis();
            }
        } catch (RuntimeException e) {
            DoughBayClient.LOGGER.debug("DoughBay market query /v1/markets failed: {}", e.getMessage());
        } finally {
            marketsFetching.set(false);
        }
    }

    private List<Market> parseMarkets(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isArray()) return null;
            List<Market> out = new ArrayList<>(root.size());
            for (JsonNode row : root) {
                MarketStats stats = statsFrom(row);
                if (stats == null) continue;
                out.add(new Market(stats, row.path("uniqueSellers").asInt(0)));
            }
            return out;
        } catch (Exception e) {
            DoughBayClient.LOGGER.debug("DoughBay could not parse /v1/markets: {}", e.getMessage());
            return null;
        }
    }

    private static MarketStats statsFrom(JsonNode row) {
        String itemKey = row.path("itemKey").asText(null);
        if (itemKey == null || itemKey.isBlank()) return null;
        StackBucket bucket;
        try {
            bucket = StackBucket.valueOf(row.path("bucket").asText("X1"));
        } catch (IllegalArgumentException e) {
            bucket = StackBucket.OTHER;
        }
        return new MarketStats(
                itemKey,
                bucket,
                row.path("windowMillis").asLong(0),
                row.path("sampleCount").asInt(0),
                row.path("outlierCount").asInt(0),
                row.path("uniqueSellers").asInt(0),
                row.path("newestSaleAt").asLong(0),
                row.path("lowerBound").asDouble(Double.NaN),
                row.path("quickSalePrice").asDouble(Double.NaN),
                row.path("weightedMedian").asDouble(Double.NaN),
                row.path("patientSalePrice").asDouble(Double.NaN),
                row.path("upperBound").asDouble(Double.NaN),
                row.path("salesPerHour").asDouble(0),
                row.path("robustVolatility").asDouble(0),
                row.path("trend").asDouble(0),
                row.path("confidence").asDouble(0),
                row.path("calculatedAt").asLong(0));
    }

    // ---- item matching (identical rules to the website) ---------------------

    /** Bare item id: the item key with any {@code #component} suffix removed. */
    public static String baseId(String itemKey) {
        if (itemKey == null) return "";
        int hash = itemKey.indexOf('#');
        return hash >= 0 ? itemKey.substring(0, hash) : itemKey;
    }

    /** Lowercase, drop a leading {@code minecraft:}, spaces to underscores. */
    static String normalizeQuery(String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.startsWith("minecraft:")) q = q.substring("minecraft:".length());
        return q.replace(' ', '_');
    }

    private static String afterNamespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** Id without namespace, underscores to spaces, each word capitalised. */
    public static String displayName(String itemId) {
        String name = afterNamespace(itemId).replace('_', ' ');
        StringBuilder out = new StringBuilder(name.length());
        boolean start = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ';
        }
        return out.toString();
    }

    /** Up to 8 items whose id contains the query, best match first, deduped on bare id. */
    public List<ItemMatch> matchItems(String query, int limit) {
        String needle = normalizeQuery(query);
        if (needle.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (Market m : markets()) {
            String id = baseId(m.stats().itemKey());
            if (afterNamespace(id).contains(needle)) seen.add(id);
        }
        List<String> ids = new ArrayList<>(seen);
        ids.sort(Comparator
                .comparingInt((String id) -> afterNamespace(id).indexOf(needle))
                .thenComparingInt(String::length)
                .thenComparing(Comparator.naturalOrder()));
        List<ItemMatch> out = new ArrayList<>();
        for (String id : ids) {
            if (out.size() >= limit) break;
            out.add(new ItemMatch(id, displayName(id)));
        }
        return out;
    }

    /** The most representative cached market row for an item id (most samples), or null. */
    public MarketStats statsFor(String itemId) {
        MarketStats best = null;
        for (Market m : markets()) {
            if (!baseId(m.stats().itemKey()).equals(itemId)) continue;
            if (best == null || m.stats().sampleCount() > best.sampleCount()) best = m.stats();
        }
        return best;
    }

    // ---- rivals (top sellers) -----------------------------------------------

    /** The cached last-24h seller leaderboard, kicking a refresh if stale. Never blocks. */
    public List<Rival> rivals() {
        if (!useServer) return localRivals();
        long now = System.currentTimeMillis();
        if (!blocked() && now - rivalsAt > LIST_CACHE_MILLIS && rivalsFetching.compareAndSet(false, true)) {
            worker.submit(this::fetchRivals);
        }
        return rivals;
    }

    /** The top sellers this build has reconstructed locally from its own sales feed. */
    private List<Rival> localRivals() {
        RivalIntel intel = DoughBayClient.rivalIntel();
        if (intel == null) return List.of();
        List<Rival> out = new ArrayList<>();
        for (RivalIntel.Rival r : intel.snapshot().rivals()) {
            out.add(new Rival(r.name(), r.sales(), r.revenue(), r.items(),
                    r.activeHours(), r.lastSaleAt(), r.botScore()));
        }
        return out;
    }

    private void fetchRivals() {
        try {
            String body = get("/v1/rivals");
            JsonNode root = mapper.readTree(body);
            if (root.isArray()) {
                List<Rival> out = new ArrayList<>(root.size());
                for (JsonNode row : root) {
                    String seller = row.path("seller").asText(null);
                    if (seller == null || seller.isBlank()) continue;
                    out.add(new Rival(seller,
                            row.path("sales").asInt(0),
                            row.path("revenue").asLong(0),
                            row.path("items").asInt(0),
                            row.path("activeHours").asInt(0),
                            row.path("lastSaleAt").asLong(0),
                            row.path("botScore").asInt(0)));
                }
                rivals = out;
                rivalsAt = System.currentTimeMillis();
            }
        } catch (Exception e) {
            DoughBayClient.LOGGER.debug("DoughBay market query /v1/rivals failed: {}", e.getMessage());
        } finally {
            rivalsFetching.set(false);
        }
    }

    // ---- seller lookup ------------------------------------------------------

    /** Starts a seller lookup over the given window; the view fills in behind it. */
    public SellerView requestSeller(String name, long sinceMillis) {
        SellerView view = new SellerView(name);
        seller = view;
        worker.submit(() -> {
            if (useServer) fetchSeller(view, sinceMillis);
            else fetchSellerLocal(view, sinceMillis);
        });
        return view;
    }

    /** A seller's sales read from the local transactions table, newest first, case-insensitive. */
    private void fetchSellerLocal(SellerView view, long sinceMillis) {
        DoughBayConfig config = DoughBayClient.activeConfig();
        if (config == null) {
            view.error = "no local database";
            view.loading = false;
            return;
        }
        List<Sale> out = new ArrayList<>();
        try (dev.doughbay.storage.Database db = new dev.doughbay.storage.Database(config.databasePath());
             java.sql.PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT item_key, item_count, total_price, sold_at FROM transactions "
                             + "WHERE seller_name = ? COLLATE NOCASE AND sold_at > ? "
                             + "ORDER BY sold_at DESC LIMIT ?")) {
            ps.setString(1, view.name);
            ps.setLong(2, sinceMillis);
            ps.setInt(3, SELLER_LIMIT);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String itemId = baseId(rs.getString(1));
                    if (itemId.isBlank()) continue;
                    out.add(new Sale(rs.getLong(4), view.name, itemId, rs.getInt(2), rs.getLong(3)));
                }
            }
            view.sales = out;
            view.loading = false;
        } catch (Exception e) {
            view.error = e.getMessage();
            view.loading = false;
        }
    }

    /** The current seller lookup, or null if none has been asked for. */
    public SellerView seller() {
        return seller;
    }

    public void clearSeller() {
        seller = null;
    }

    private void fetchSeller(SellerView view, long sinceMillis) {
        try {
            String path = "/v1/seller/" + encode(view.name)
                    + "?since=" + sinceMillis + "&limit=" + SELLER_LIMIT;
            String body = get(path);
            JsonNode root = mapper.readTree(body);
            List<Sale> out = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode row : root) {
                    String itemId = baseId(row.path("itemId").asText(
                            row.path("itemKey").asText("")));
                    if (itemId.isBlank()) continue;
                    out.add(new Sale(
                            row.path("soldAt").asLong(0),
                            row.path("sellerName").asText(view.name),
                            itemId,
                            row.path("itemCount").asInt(1),
                            row.path("totalPrice").asLong(0)));
                }
            }
            view.sales = out;
            view.loading = false;
        } catch (Exception e) {
            view.error = e.getMessage();
            view.loading = false;
        }
    }

    // ---- transport ----------------------------------------------------------

    private String get(String path) {
        if (baseUrl.isEmpty()) throw new IllegalStateException("no API base URL");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        int code = response.statusCode();
        if (code == 429) {
            blockedUntil = System.currentTimeMillis() + BACKOFF_MILLIS;
            throw new RuntimeException("rate limited (429); backing off a minute");
        }
        if (code >= 500) {
            throw new RuntimeException("server " + code + " (site restarting?)");
        }
        if (code != 200) {
            throw new RuntimeException("HTTP " + code);
        }
        return response.body();
    }

    /** Percent-encode a single path segment; names and ids are plain, but encode anyway. */
    private static String encode(String segment) {
        StringBuilder out = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.forDigit((c >> 4) & 0xF, 16))
                        .append(Character.forDigit(c & 0xF, 16));
            }
        }
        return out.toString();
    }
}
