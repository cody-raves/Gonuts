package dev.doughbay.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.doughbay.api.DonutApiClient;
import dev.doughbay.cli.AppConfig;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Sale;
import dev.doughbay.engine.MarketService;
import dev.doughbay.storage.Database;
import dev.doughbay.storage.TransactionRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * The HTTP face of the data server. Every request but the health check and
 * the dashboard page needs an {@code X-Api-Key} header (or {@code ?key=}),
 * and each key is rate limited on a sliding minute. Reads open their own
 * short-lived database connection; WAL mode lets them run beside the
 * collector's writes.
 *
 * <pre>
 * GET /v1/health                          liveness, feed status, API budget
 * GET /v1/markets                         current statistics for every tracked market
 * GET /v1/opportunities?cap=              buy signals from those statistics
 * GET /v1/sales?since=&limit=             completed sales since a timestamp (max 5000)
 * GET /v1/market/{itemKey}?since=&limit=  one item's page: value, change, ranges, lowest asks, sales with components
 * GET /v1/rivals                          sellers of the last 24 hours, ranked
 * GET /v1/listings?since=&limit=          newest listings seen by the live lane, with the market beside each
 * GET /v1/admin/keys?name=  (X-Admin-Token) mint a key;  /v1/admin/keys lists;  /v1/admin/revoke?key=
 * GET /                                   dashboard
 * </pre>
 */
public final class Api {
    private static final int REQUESTS_PER_MINUTE = 120;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpServer server;
    private final Path databasePath;
    private final Collector collector;
    private final DonutApiClient client;
    private final AppConfig config;
    private final String adminToken;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingCounts = new ConcurrentHashMap<>();
    private final String dashboard;

    Api(int port, Path databasePath, Collector collector, DonutApiClient client, AppConfig config,
        String adminToken) throws IOException {
        this.databasePath = databasePath;
        this.collector = collector;
        this.client = client;
        this.config = config;
        this.adminToken = adminToken;
        this.dashboard = loadResource("dashboard.html");
        // The item pages look sales, asks and observations up by item id; the
        // schema indexes by item key only.
        try (Database db = new Database(databasePath); java.sql.Statement st = db.connection().createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_transactions_item_id_sold_at ON transactions(item_id, sold_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_listing_snapshots_item_id_observed_at ON listing_snapshots(item_id, observed_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_component_listings_item_id_observed_at ON component_listings(item_id, observed_at)");
        } catch (Exception e) {
            System.err.println("api: could not create item indexes: " + e);
        }
        server = HttpServer.create(new InetSocketAddress(port), 64);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", this::handle);
    }

    void start() {
        server.start();
    }

    void stop() {
        server.stop(1);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI());
            if (path.equals("/") || path.equals("/index.html")) {
                send(ex, 200, "text/html; charset=utf-8", dashboard.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/assets/") && !path.contains("..")) {
                try (InputStream in = Api.class.getClassLoader().getResourceAsStream(path.substring(1))) {
                    if (in == null) {
                        send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    byte[] body = in.readAllBytes();
                    ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
                    send(ex, 200, path.endsWith(".png") ? "image/png" : "application/octet-stream", body);
                }
                return;
            }
            if (path.equals("/v1/health")) {
                json(ex, 200, health());
                return;
            }
            if (path.startsWith("/v1/admin/")) {
                String token = ex.getRequestHeaders().getFirst("X-Admin-Token");
                if (token == null || !token.equals(adminToken)) {
                    json(ex, 403, Map.of("error", "admin token required"));
                    return;
                }
                admin(ex, path, q);
                return;
            }
            String key = ex.getRequestHeaders().getFirst("X-Api-Key");
            if (key == null || key.isBlank()) key = q.get("key");
            try (Database db = new Database(databasePath)) {
                if (!ServerStore.valid(db, key)) {
                    json(ex, 401, Map.of("error", "a valid X-Api-Key is required"));
                    return;
                }
                if (!allow(key)) {
                    json(ex, 429, Map.of("error", "rate limit: " + REQUESTS_PER_MINUTE + " requests per minute"));
                    return;
                }
                pendingCounts.merge(key, 1L, Long::sum);
                if (pendingCounts.get(key) >= 25) {
                    ServerStore.countRequests(db, key, pendingCounts.remove(key));
                }
                route(ex, db, path, q);
            }
        } catch (Exception e) {
            json(ex, 500, Map.of("error", e.toString()));
        }
    }

    private void route(HttpExchange ex, Database db, String path, Map<String, String> q) throws Exception {
        long now = System.currentTimeMillis();
        switch (path) {
            case "/v1/markets" -> json(ex, 200, collector.latestStats());
            case "/v1/opportunities" -> {
                long cap = longParam(q, "cap", 1_000_000_000L);
                MarketService service = ServerMain.service(config, client, db);
                List<Opportunity> signals = service.marketSignals(collector.latestStats(), now, cap);
                json(ex, 200, signals);
            }
            case "/v1/sales" -> {
                long since = longParam(q, "since", now - 3_600_000L);
                int limit = (int) Math.min(5000, Math.max(1, longParam(q, "limit", 1000)));
                List<Sale> sales = new TransactionRepository(db).findAllSince(since);
                sales.sort(Comparator.comparingLong(Sale::soldAt));
                json(ex, 200, sales.size() > limit ? sales.subList(0, limit) : sales);
            }
            case "/v1/rivals" -> json(ex, 200, rivals(db, now));
            case "/v1/listings" -> json(ex, 200, listings(db, now, longParam(q, "since", now - 3_600_000L),
                    (int) Math.min(500, Math.max(1, longParam(q, "limit", 150)))));
            case "/v1/overview" -> json(ex, 200, overview(db, now));
            case "/v1/trends" -> {
                // 12 hourly buckets per requested item: sale count and median unit price,
                // for the small trend bars the GUI draws beside every market.
                long since = now - 12 * 3_600_000L;
                Set<String> wanted = new HashSet<>();
                for (String k : (q.getOrDefault("items", "")).split(",")) if (!k.isBlank()) wanted.add(k.strip());
                Map<String, List<List<Double>>> buckets = new HashMap<>();
                for (Sale s : new TransactionRepository(db).findAllSince(since)) {
                    String base = s.itemKey().indexOf('#') >= 0 ? s.itemKey().substring(0, s.itemKey().indexOf('#')) : s.itemKey();
                    if (!wanted.contains(base) && !wanted.contains(s.itemKey())) continue;
                    List<List<Double>> hours = buckets.computeIfAbsent(base, k -> {
                        List<List<Double>> h = new ArrayList<>();
                        for (int i = 0; i < 12; i++) h.add(new ArrayList<>());
                        return h;
                    });
                    int b = (int) Math.min(11, Math.max(0, (s.soldAt() - since) / 3_600_000L));
                    hours.get(b).add((double) s.totalPrice() / Math.max(1, s.itemCount()));
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, List<List<Double>>> e : buckets.entrySet()) {
                    long[] counts = new long[12];
                    double[] medians = new double[12];
                    for (int i = 0; i < 12; i++) {
                        List<Double> v = e.getValue().get(i);
                        counts[i] = v.size();
                        if (!v.isEmpty()) {
                            java.util.Collections.sort(v);
                            medians[i] = v.get(v.size() / 2);
                        }
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("counts", counts);
                    row.put("medians", medians);
                    out.put(e.getKey(), row);
                }
                json(ex, 200, out);
            }
            default -> {
                if (path.startsWith("/v1/components/")) {
                    // What clients have seen attached to this item on the page: enchantments,
                    // effects, trims, box contents, with asking prices. The feed hides all of it.
                    String itemId = URLDecoder.decode(path.substring("/v1/components/".length()), StandardCharsets.UTF_8);
                    long since = longParam(q, "since", now - 7 * 24 * 3_600_000L);
                    int limit = (int) Math.min(200, Math.max(1, longParam(q, "limit", 40)));
                    List<Map<String, Object>> rows = new ArrayList<>();
                    try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                            "SELECT observed_at, count, total_price, seller_name, descriptor_key, descriptor_json "
                                    + "FROM component_listings WHERE item_id = ? AND observed_at > ? "
                                    + "ORDER BY observed_at DESC LIMIT ?")) {
                        ps.setString(1, itemId);
                        ps.setLong(2, since);
                        ps.setInt(3, limit);
                        try (java.sql.ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("observedAt", rs.getLong(1));
                                row.put("count", rs.getInt(2));
                                row.put("price", rs.getLong(3));
                                row.put("seller", rs.getString(4));
                                row.put("key", rs.getString(5));
                                row.put("descriptor", JSON.readTree(rs.getString(6)));
                                rows.add(row);
                            }
                        }
                    }
                    json(ex, 200, rows);
                    return;
                }
                if (path.startsWith("/v1/seller/")) {
                    String seller = URLDecoder.decode(path.substring("/v1/seller/".length()), StandardCharsets.UTF_8);
                    long since = longParam(q, "since", now - 24 * 3_600_000L);
                    List<Sale> sales = new ArrayList<>();
                    for (Sale sale : new TransactionRepository(db).findAllSince(since)) {
                        if (seller.equalsIgnoreCase(sale.sellerName())) sales.add(sale);
                    }
                    sales.sort(Comparator.comparingLong(Sale::soldAt).reversed());
                    json(ex, 200, sales.size() > 500 ? sales.subList(0, 500) : sales);
                    return;
                }
                if (path.startsWith("/v1/market/")) {
                    String itemKey = URLDecoder.decode(path.substring("/v1/market/".length()), StandardCharsets.UTF_8);
                    long since = longParam(q, "since", now - 24 * 3_600_000L);
                    int limit = (int) Math.min(5000, Math.max(1, longParam(q, "limit", 2000)));
                    json(ex, 200, market(db, itemKey, since, limit, now));
                    return;
                }
                json(ex, 404, Map.of("error", "unknown endpoint"));
            }
        }
    }

    private void admin(HttpExchange ex, String path, Map<String, String> q) throws Exception {
        try (Database db = new Database(databasePath)) {
            switch (path) {
                case "/v1/admin/keys" -> {
                    if (q.containsKey("name")) {
                        json(ex, 200, ServerStore.create(db, q.get("name")));
                    } else {
                        json(ex, 200, ServerStore.list(db));
                    }
                }
                case "/v1/admin/revoke" -> json(ex, 200, Map.of("revoked", ServerStore.revoke(db, q.get("key"))));
                default -> json(ex, 404, Map.of("error", "unknown admin endpoint"));
            }
        }
    }

    private Map<String, Object> health() {
        long now = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("uptimeSeconds", (now - collector.startedAt()) / 1000);
        out.put("lastSalesPollSecondsAgo", collector.lastSalesPollAt() == 0 ? null : (now - collector.lastSalesPollAt()) / 1000);
        out.put("lastListingsSweepSecondsAgo", collector.lastListingsSweepAt() == 0 ? null : (now - collector.lastListingsSweepAt()) / 1000);
        out.put("lastStatsSecondsAgo", collector.lastStatsAt() == 0 ? null : (now - collector.lastStatsAt()) / 1000);
        out.put("salesSeen", collector.salesCollected());
        out.put("newSales", collector.newSales());
        out.put("markets", collector.latestStats().size());
        out.put("lastError", collector.lastError());
        out.put("pagesLastPoll", collector.lastPagesRead());
        out.put("pollsThatHitPageLimit", collector.deepPolls());
        try {
            DonutApiClient.ApiHealth h = client.health();
            out.put("donutRequestsLastMinute", h.requestsInLastMinute());
            out.put("donutBudgetPerMinute", h.budgetPerMinute());
            out.put("donutErrors", h.totalErrors());
        } catch (RuntimeException ignored) {
            // health is best effort
        }
        return out;
    }

    /** The overview page in one call: totals, sales and revenue per hour, hottest markets, movers. */
    private Map<String, Object> overview(Database db, long now) throws Exception {
        long dayAgo = now - 24 * 3_600_000L;
        List<Sale> sales = new TransactionRepository(db).findAllSince(dayAgo);
        long[] countByHour = new long[24];
        long[] revenueByHour = new long[24];
        long lastHourSales = 0;
        long lastHourRevenue = 0;
        Set<String> sellersLastHour = new HashSet<>();
        Map<String, long[]> byItem = new HashMap<>();
        for (Sale s : sales) {
            int bucket = (int) Math.min(23, Math.max(0, (s.soldAt() - dayAgo) / 3_600_000L));
            countByHour[bucket]++;
            revenueByHour[bucket] += s.totalPrice();
            if (now - s.soldAt() <= 3_600_000L) {
                lastHourSales++;
                lastHourRevenue += s.totalPrice();
                if (s.sellerName() != null && !s.sellerName().isBlank()) sellersLastHour.add(s.sellerName());
            }
            long[] agg = byItem.computeIfAbsent(s.itemKey(), k -> new long[2]);
            agg[0]++;
            agg[1] += s.totalPrice();
        }
        List<Map<String, Object>> hottest = new ArrayList<>();
        byItem.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(12)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("itemKey", e.getKey());
                    row.put("sales", e.getValue()[0]);
                    row.put("revenue", e.getValue()[1]);
                    hottest.add(row);
                });
        // Where the money is: markets by revenue, and the flow by ticket size.
        List<Map<String, Object>> byRevenue = new ArrayList<>();
        byItem.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(15)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("itemKey", e.getKey());
                    row.put("sales", e.getValue()[0]);
                    row.put("revenue", e.getValue()[1]);
                    row.put("averageTicket", e.getValue()[1] / Math.max(1, e.getValue()[0]));
                    byRevenue.add(row);
                });
        long[][] tierAgg = new long[5][3]; // count, revenue, distinct markets filled below
        String[] tierNames = {"under $10k", "$10k to $100k", "$100k to $1m", "$1m to $10m", "$10m and up"};
        Map<Integer, Set<String>> tierMarkets = new HashMap<>();
        for (Sale s : sales) {
            long p = s.totalPrice();
            int t = p < 10_000 ? 0 : p < 100_000 ? 1 : p < 1_000_000 ? 2 : p < 10_000_000 ? 3 : 4;
            tierAgg[t][0]++;
            tierAgg[t][1] += p;
            tierMarkets.computeIfAbsent(t, k -> new HashSet<>()).add(s.itemKey());
        }
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (int t = 0; t < 5; t++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tier", tierNames[t]);
            row.put("sales", tierAgg[t][0]);
            row.put("revenue", tierAgg[t][1]);
            row.put("markets", tierMarkets.getOrDefault(t, Set.of()).size());
            tiers.add(row);
        }
        // Edge: the gap between the quick-sale and patient-sale bands is the margin a
        // flipper can take; times the sale rate it is profit per hour on offer.
        List<Map<String, Object>> edge = new ArrayList<>();
        collector.latestStats().stream()
                .filter(st -> st.hasPrices() && st.sampleCount() >= 15 && st.quickSalePrice() > 0 && st.salesPerHour() > 0)
                .map(st -> {
                    double spread = st.patientSalePrice() - st.quickSalePrice();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("itemKey", st.itemKey());
                    row.put("bucket", st.bucket().name());
                    row.put("quick", st.quickSalePrice());
                    row.put("patient", st.patientSalePrice());
                    row.put("spreadPercent", spread / st.quickSalePrice() * 100.0);
                    row.put("salesPerHour", st.salesPerHour());
                    row.put("profitPerHour", spread * st.salesPerHour());
                    row.put("confidence", st.confidence());
                    return row;
                })
                .filter(row -> (double) row.get("spreadPercent") >= 3.0)
                .sorted((a, b) -> Double.compare((double) b.get("profitPerHour"), (double) a.get("profitPerHour")))
                .limit(15)
                .forEach(edge::add);
        List<Map<String, Object>> movers = new ArrayList<>();
        collector.latestStats().stream()
                .filter(st -> st.sampleCount() >= 20 && Double.isFinite(st.trend()) && Math.abs(st.trend()) >= 0.02)
                .sorted((a, b) -> Double.compare(Math.abs(b.trend()), Math.abs(a.trend())))
                .limit(12)
                .forEach(st -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("itemKey", st.itemKey());
                    row.put("bucket", st.bucket().name());
                    row.put("median", st.weightedMedian());
                    row.put("trend", st.trend());
                    row.put("salesPerHour", st.salesPerHour());
                    movers.add(row);
                });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", dayAgo);
        out.put("sales24h", sales.size());
        out.put("lastHourSales", lastHourSales);
        out.put("lastHourRevenue", lastHourRevenue);
        out.put("sellersLastHour", sellersLastHour.size());
        out.put("markets", collector.latestStats().size());
        out.put("countByHour", countByHour);
        out.put("revenueByHour", revenueByHour);
        out.put("hottest", hottest);
        out.put("byRevenue", byRevenue);
        out.put("tiers", tiers);
        out.put("edge", edge);
        // The biggest tickets of the day, with what a client saw on the listing.
        List<Map<String, Object>> biggest = new ArrayList<>();
        List<Sale> byPrice = new ArrayList<>(sales);
        byPrice.sort(Comparator.comparingLong(Sale::totalPrice).reversed());
        for (Sale s : byPrice.subList(0, Math.min(10, byPrice.size()))) {
            Map<String, Object> row = saleRow(s);
            Object[] o = observedFor(db.connection(), s);
            if (o != null) attach(row, o);
            biggest.add(row);
        }
        out.put("biggest", biggest);
        out.put("movers", movers);
        return out;
    }

    private static final long DAY = 24 * 3_600_000L;
    /** A listing can sit on the page this long before it turns into the sale. */
    private static final long PAIR_WINDOW = 48 * 3_600_000L;

    /**
     * One item's page: value and its day-on-day change, the lowest asking
     * price over time, price ranges, and the recent sales with whatever a
     * client saw attached to the same listing on the page. The feed hides
     * enchantments (its {@code levels} is null on every row it has ever
     * sent), so the attachment comes from pairing a sale with an in-game
     * observation of the same item, count and price shortly before it sold.
     * A plain key covers every variant of the item; a hashed key is exact.
     */
    private Map<String, Object> market(Database db, String itemKey, long since, int limit, long now) throws Exception {
        boolean exact = itemKey.indexOf('#') >= 0;
        String itemId = exact ? itemKey.substring(0, itemKey.indexOf('#')) : itemKey;
        String column = exact ? "item_key" : "item_id";
        java.sql.Connection c = db.connection();
        List<Sale> sales = new ArrayList<>();
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT transaction_hash, sold_at, seller_uuid, seller_name, item_key, item_id, item_count, total_price "
                        + "FROM transactions WHERE " + column + " = ? AND sold_at > ? ORDER BY sold_at")) {
            ps.setString(1, itemKey);
            ps.setLong(2, since);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sales.add(new Sale(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getLong(8)));
            }
        }
        List<Object[]> observed = new ArrayList<>();
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT observed_at, count, total_price, seller_name, descriptor_key, descriptor_json FROM component_listings "
                        + "WHERE item_id = ? AND observed_at > ? ORDER BY observed_at DESC LIMIT 5000")) {
            ps.setString(1, itemId);
            ps.setLong(2, since - PAIR_WINDOW);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) observed.add(new Object[]{rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)});
            }
        }
        List<Sale> window = sales.size() > limit ? sales.subList(sales.size() - limit, sales.size()) : sales;
        List<Map<String, Object>> rows = new ArrayList<>(window.size());
        for (Sale s : window) {
            Map<String, Object> row = saleRow(s);
            for (Object[] o : observed) {
                if (pairs(o, s)) {
                    attach(row, o);
                    break;
                }
            }
            rows.add(row);
        }
        List<Map<String, Object>> ranges = new ArrayList<>();
        ranges.add(range(c, column, itemKey, now - DAY, now, "24 h"));
        ranges.add(range(c, column, itemKey, now - 7 * DAY, now, "7 days"));
        ranges.add(range(c, column, itemKey, now - 30 * DAY, now, "30 days"));
        ranges.add(range(c, column, itemKey, 0, now, "all time"));
        Map<String, Object> today = ranges.get(0);
        Map<String, Object> previous = range(c, column, itemKey, now - 2 * DAY, now - DAY, "previous 24 h");
        Double value = (long) today.get("count") > 0 ? (Double) today.get("median")
                : (long) ranges.get(1).get("count") > 0 ? (Double) ranges.get(1).get("median") : null;
        Double change = null;
        if ((long) today.get("count") > 0 && (long) previous.get("count") > 0) {
            double prev = (Double) previous.get("median");
            if (prev > 0) change = ((Double) today.get("median") - prev) / prev;
        }
        List<double[]> asks = new ArrayList<>();
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT (observed_at / 300000) * 300000, MIN(unit_price) FROM listing_snapshots WHERE " + column
                        + " = ? AND observed_at > ? GROUP BY 1 ORDER BY 1")) {
            ps.setString(1, itemKey);
            ps.setLong(2, since);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) asks.add(new double[]{rs.getLong(1), rs.getDouble(2)});
            }
        }
        MarketStats stats = null;
        for (MarketStats s : collector.latestStats()) {
            if (s.itemKey().equals(itemKey) && (stats == null || s.sampleCount() > stats.sampleCount())) stats = s;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("itemKey", itemKey);
        out.put("itemId", itemId);
        out.put("stats", stats);
        out.put("value", value);
        out.put("change24h", change);
        out.put("sales24h", today.get("count"));
        out.put("ranges", ranges);
        out.put("asks", asks);
        out.put("sales", rows);
        return out;
    }

    private static boolean pairs(Object[] o, Sale s) {
        long at = (long) o[0];
        if (at > s.soldAt() + 60_000L || at < s.soldAt() - PAIR_WINDOW) return false;
        if ((int) o[1] != s.itemCount() || (long) o[2] != s.totalPrice()) return false;
        String seller = (String) o[3];
        return seller == null || seller.isBlank() || s.sellerName() == null || seller.equalsIgnoreCase(s.sellerName());
    }

    private static void attach(Map<String, Object> row, Object[] o) throws IOException {
        row.put("descriptorKey", o[4]);
        row.put("components", JSON.readTree((String) o[5]));
    }

    private static Map<String, Object> saleRow(Sale s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("soldAt", s.soldAt());
        row.put("sellerName", s.sellerName());
        row.put("itemKey", s.itemKey());
        row.put("itemId", s.itemId());
        row.put("itemCount", s.itemCount());
        row.put("totalPrice", s.totalPrice());
        return row;
    }

    /** The newest in-game observation that pairs with this sale, or null. */
    private static Object[] observedFor(java.sql.Connection c, Sale s) throws Exception {
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT observed_at, count, total_price, seller_name, descriptor_key, descriptor_json FROM component_listings "
                        + "WHERE item_id = ? AND count = ? AND total_price = ? AND observed_at > ? AND observed_at <= ? "
                        + "ORDER BY observed_at DESC LIMIT 1")) {
            ps.setString(1, s.itemId());
            ps.setInt(2, s.itemCount());
            ps.setLong(3, s.totalPrice());
            ps.setLong(4, s.soldAt() - PAIR_WINDOW);
            ps.setLong(5, s.soldAt() + 60_000L);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Object[] o = {rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6)};
                return pairs(o, s) ? o : null;
            }
        }
    }

    /** Count, low, median and high unit price of one market's sales in a window. */
    private static Map<String, Object> range(java.sql.Connection c, String column, String value, long from, long to, String label) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("label", label);
        long count;
        try (java.sql.PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*), MIN(unit_price), MAX(unit_price) FROM transactions WHERE " + column + " = ? AND sold_at > ? AND sold_at <= ?")) {
            ps.setString(1, value);
            ps.setLong(2, from);
            ps.setLong(3, to);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                count = rs.getLong(1);
                out.put("count", count);
                out.put("low", rs.getDouble(2));
                out.put("high", rs.getDouble(3));
            }
        }
        double median = 0;
        if (count > 0) {
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT unit_price FROM transactions WHERE " + column + " = ? AND sold_at > ? AND sold_at <= ? ORDER BY unit_price LIMIT 1 OFFSET ?")) {
                ps.setString(1, value);
                ps.setLong(2, from);
                ps.setLong(3, to);
                ps.setLong(4, count / 2);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) median = rs.getDouble(1);
                }
            }
        }
        out.put("median", median);
        return out;
    }

    /**
     * The newest listings the live lane has seen, with the market's quick-sale
     * price beside each so a reader sees at once which rows are under it.
     */
    private List<Map<String, Object>> listings(Database db, long now, long since, int limit) throws Exception {
        Map<String, MarketStats> byMarket = new HashMap<>();
        for (MarketStats st : collector.latestStats()) {
            if (st.hasPrices()) byMarket.put(st.itemKey() + "|" + st.bucket().name(), st);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try (java.sql.PreparedStatement ps = db.connection().prepareStatement(
                "SELECT observed_at, seller_name, item_key, item_id, item_count, total_price, time_left FROM listing_snapshots "
                        + "WHERE observed_at > ? ORDER BY observed_at DESC LIMIT ?")) {
            ps.setLong(1, since);
            ps.setInt(2, limit);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long count = Math.max(1, rs.getInt(5));
                    long price = rs.getLong(6);
                    row.put("observedAt", rs.getLong(1));
                    row.put("sellerName", rs.getString(2));
                    row.put("itemKey", rs.getString(3));
                    row.put("itemId", rs.getString(4));
                    row.put("itemCount", count);
                    row.put("totalPrice", price);
                    long timeLeft = rs.getLong(7);
                    if (!rs.wasNull()) row.put("timeLeftMillis", timeLeft);
                    MarketStats st = byMarket.get(rs.getString(3) + "|" + dev.doughbay.core.model.StackBucket.of((int) count).name());
                    if (st != null) {
                        row.put("quick", st.quickSalePrice());
                        row.put("median", st.weightedMedian());
                        row.put("salesPerHour", st.salesPerHour());
                        row.put("discount", 1.0 - ((double) price / count) / st.quickSalePrice());
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** Sellers of the last 24 hours ranked by sales, with the flipper heuristics the mod uses. */
    private static List<Map<String, Object>> rivals(Database db, long now) throws Exception {
        List<Sale> sales = new TransactionRepository(db).findAllSince(now - 24 * 3_600_000L);
        Map<String, long[]> agg = new HashMap<>();
        Map<String, Set<String>> items = new HashMap<>();
        Map<String, boolean[]> hours = new HashMap<>();
        for (Sale s : sales) {
            String name = s.sellerName() == null ? "" : s.sellerName();
            if (name.isBlank()) continue;
            long[] a = agg.computeIfAbsent(name, k -> new long[3]);
            a[0]++;
            a[1] += s.totalPrice();
            a[2] = Math.max(a[2], s.soldAt());
            items.computeIfAbsent(name, k -> new HashSet<>()).add(s.itemKey());
            int hour = (int) ((s.soldAt() / 3_600_000L) % 24);
            hours.computeIfAbsent(name, k -> new boolean[24])[hour] = true;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            long[] a = e.getValue();
            if (a[0] < 20) continue;
            int active = 0;
            for (boolean b : hours.get(e.getKey())) if (b) active++;
            int distinct = items.get(e.getKey()).size();
            double score = 100 * (0.35 * active / 24.0 + 0.30 * Math.min(1.0, distinct / 10.0) + 0.15 * Math.min(1.0, a[0] / 150.0));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seller", e.getKey());
            row.put("sales", a[0]);
            row.put("revenue", a[1]);
            row.put("items", distinct);
            row.put("activeHours", active);
            row.put("lastSaleAt", a[2]);
            row.put("botScore", Math.round(score));
            out.add(row);
        }
        out.sort((x, y) -> Long.compare((long) y.get("sales"), (long) x.get("sales")));
        return out.size() > 200 ? out.subList(0, 200) : out;
    }

    private boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) window.pollFirst();
            if (window.size() >= REQUESTS_PER_MINUTE) return false;
            window.addLast(now);
            return true;
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static long longParam(Map<String, String> q, String name, long fallback) {
        try {
            return q.containsKey(name) ? Long.parseLong(q.get(name)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(body));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static String loadResource(String name) throws IOException {
        try (InputStream in = Api.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) return "<h1>DoughBay data server</h1>";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
