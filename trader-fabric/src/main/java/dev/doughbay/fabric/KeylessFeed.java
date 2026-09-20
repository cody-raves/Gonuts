package dev.doughbay.fabric;

import dev.doughbay.core.model.Sale;
import dev.doughbay.storage.Database;
import dev.doughbay.storage.TransactionRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Keeps the sales ledger moving without the Donut API.
 *
 * <p>The valuation engine reads completed sales from the local {@code
 * transactions} table; the API only ever fed it. Without the API two in-game
 * sources feed it instead, each written as ordinary sale rows under a {@code
 * keyless:} seller so they can be told apart (and removed) later:
 * <ul>
 *   <li><b>Order-house fills.</b> Every read of the order house shows each
 *   order's delivered count; a rise is units that traded at that order's
 *   price, across the whole server. Orders pay less than the auction, so each
 *   fill is scaled by the item's own auction-to-order ratio, learned from the
 *   weeks when both were visible, and written as full stacks.</li>
 *   <li><b>Our own sales.</b> The chat receipt for a sale of ours is an exact
 *   auction price and is written as it is.</li>
 * </ul>
 */
public final class KeylessFeed {
    static final String ORDER_SELLER = "keyless:orders";
    static final String OWN_SELLER_PREFIX = "keyless:own:";
    static final String ASK_SELLER = "keyless:asks";
    /** A fast sale undercuts the live asks; this is what one is taken to be worth. */
    static final double ASK_DISCOUNT = 0.97;
    static final int MAX_ROWS_PER_SWEEP = 6;
    /** An ask seen again inside this window is the same listing, not a new observation. */
    static final long ASK_WINDOW_MILLIS = 6 * 3_600_000L;
    /** Ratios are learned over this much history before the API went quiet. */
    private static final long RATIO_WINDOW_MILLIS = 14L * 24 * 3_600_000L;
    private static final double MIN_RATIO = 1.0;
    private static final double MAX_RATIO = 4.0;
    /**
     * Fills are price evidence, not a volume count. A bulk order for half a
     * million spruce logs moved by tens of thousands in one read and was
     * written as 36,412 stack sales in four reads, which swamped every
     * statistic of the market. So one fill writes a few stacks at most, and
     * one item a bounded number per pass.
     */
    static final int MAX_STACKS_PER_FILL = 4;
    static final int MAX_STACKS_PER_ITEM_PER_PASS = 16;

    private record OwnSale(String itemKey, int count, long price, long at, String player) { }

    private record Asks(String itemKey, int count, List<Long> asks, long at) { }

    private static final ConcurrentLinkedQueue<Asks> ASKS = new ConcurrentLinkedQueue<>();

    /**
     * What a keyless sweep saw on the auction house for one market: every ask
     * from others, cheapest first. Only while keyless runs.
     */
    public static void recordAsks(String itemKey, int count, List<Long> asks) {
        if (!active || itemKey == null || itemKey.indexOf('#') >= 0 || count <= 0 || asks == null || asks.isEmpty()) return;
        ASKS.add(new Asks(itemKey, count, List.copyOf(asks), System.currentTimeMillis()));
        long cheapest = asks.stream().filter(a -> a != null && a > 0).mapToLong(Long::longValue).min().orElse(0);
        if (cheapest > 0) CHEAPEST.put(itemKey + (count == 1 ? "|1" : "|n"),
                new long[] {cheapest / count, System.currentTimeMillis()});
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> CHEAPEST =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The cheapest unit price anyone was asking for {@code itemKey} at the last
     * sweep within six hours, or 0. An order above it pays more than simply
     * buying the listing: raw mutton was bid at 128K a unit while a stack sat
     * on the auction at 7K a unit.
     */
    public static long cheapestAskUnit(String itemKey, boolean single, long now) {
        long[] seen = CHEAPEST.get(itemKey + (single ? "|1" : "|n"));
        return seen == null || now - seen[1] > ASK_WINDOW_MILLIS ? 0 : seen[0];
    }

    /**
     * The asks worth writing. First everything more than three times off the
     * median either way goes: joke listings (64 experience bottles at 44M,
     * seen live, when the real price was about 200K - a quarter-trim alone let
     * two of them through) and give-away ghosts. Of what is left, when there
     * is more than {@code max}, the middle half is sampled evenly.
     * Package-visible for tests.
     */
    static List<Long> representativeAsks(List<Long> asks, int max) {
        List<Long> sorted = new ArrayList<>(asks);
        sorted.removeIf(a -> a == null || a <= 0);
        sorted.sort(null);
        if (sorted.isEmpty()) return List.of();
        int n0 = sorted.size();
        double median = n0 % 2 == 1 ? sorted.get(n0 / 2) : (sorted.get(n0 / 2 - 1) + sorted.get(n0 / 2)) / 2.0;
        sorted.removeIf(a -> a > median * 3 || a < median / 3);
        int n = sorted.size();
        if (n <= max) return List.copyOf(sorted);
        List<Long> middle = sorted.subList(n / 4, Math.max(n / 4 + 1, n - n / 4));
        List<Long> out = new ArrayList<>(max);
        for (int i = 0; i < max; i++) out.add(middle.get((int) ((long) i * middle.size() / max)));
        return out;
    }

    private static volatile boolean active;
    private static final ConcurrentLinkedQueue<OwnSale> OWN_SALES = new ConcurrentLinkedQueue<>();

    private final Path ratiosFile;
    private final Path singlesFile;

    /**
     * Markets that pay more one at a time, learned once from the API history
     * (keyless-singles.txt). Without the API the stack profile only ever saw
     * stack prices, found no singles market, and peeling stopped; the sweep
     * now also checks single asks for these, which feeds the profile again.
     */
    private static volatile java.util.Set<String> singlesMarkets = java.util.Set.of();

    public static java.util.Set<String> singlesMarkets() {
        return singlesMarkets;
    }
    private final Map<String, Double> ratios = new HashMap<>();
    private final Map<String, Integer> carry = new HashMap<>();
    private final Map<String, Integer> writtenThisPass = new HashMap<>();
    private final OrderFillTracker tracker = new OrderFillTracker();
    private long cursor = -1;
    private long seq;
    private final List<long[]> recent = new ArrayList<>();   // {at, rows} of sale rows written

    public KeylessFeed(Path databasePath) {
        this.ratiosFile = databasePath.resolveSibling("keyless-ratios.txt");
        this.singlesFile = databasePath.resolveSibling("keyless-singles.txt");
    }

    /** Whether the keyless feed is the one running; own sales are only recorded then. */
    public static boolean active() {
        return active;
    }

    static void setActive(boolean on) {
        active = on;
        if (!on) {
            OWN_SALES.clear();
            ASKS.clear();
        }
    }

    /** One of our listings sold. Exact, so it is written as it is; only while keyless runs. */
    public static void recordOwnSale(String itemKey, int count, long price, String player) {
        if (!active || itemKey == null || itemKey.indexOf('#') >= 0 || count <= 0 || price <= 0) return;
        OWN_SALES.add(new OwnSale(itemKey, count, price, System.currentTimeMillis(),
                player == null || player.isBlank() ? "me" : player));
    }

    public int ratioCount() {
        return ratios.size();
    }

    /** Sale rows written in the last hour. */
    public int writtenLastHour(long now) {
        recent.removeIf(r -> now - r[0] > 3_600_000L);
        long n = 0;
        for (long[] r : recent) n += r[1];
        return (int) n;
    }

    /** Loads the auction-to-order ratios, learning them from history the first time. */
    public void prepare(Database db) throws SQLException {
        prepareSingles(db.connection());
        if (!ratios.isEmpty()) return;
        if (Files.exists(ratiosFile)) {
            try {
                for (String line : Files.readAllLines(ratiosFile, StandardCharsets.UTF_8)) {
                    String[] p = line.split("=", 2);
                    if (p.length == 2) ratios.put(p[0].strip(), Double.parseDouble(p[1].strip()));
                }
            } catch (IOException | RuntimeException e) {
                ratios.clear();
            }
            if (!ratios.isEmpty()) {
                DoughBayClient.LOGGER.info("DoughBay keyless: {} auction-to-order price ratios loaded", ratios.size());
                return;
            }
        }
        learnRatios(db.connection());
        if (ratios.isEmpty()) {
            // No API history here to learn from: a fresh install. Without
            // ratios no order-house fill becomes a price, and the bot has only
            // auction asks to go on. Start from the ratios shipped in the jar.
            for (String line : bundled("keyless-ratios.txt")) {
                String[] p = line.split("=", 2);
                if (p.length != 2) continue;
                try {
                    ratios.put(p[0].strip(), Double.parseDouble(p[1].strip()));
                } catch (NumberFormatException ignored) {
                }
            }
            DoughBayClient.LOGGER.info("DoughBay keyless: no history to learn from; {} shipped price ratios loaded", ratios.size());
        }
        StringBuilder out = new StringBuilder();
        ratios.forEach((k, v) -> out.append(k).append('=').append(String.format(Locale.ROOT, "%.4f", v)).append('\n'));
        try {
            Files.writeString(ratiosFile, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DoughBayClient.LOGGER.warn("DoughBay keyless: could not save the price ratios: {}", e.toString());
        }
    }

    private void prepareSingles(Connection c) throws SQLException {
        if (!singlesMarkets.isEmpty()) return;
        java.util.Set<String> found = new java.util.TreeSet<>();
        if (Files.exists(singlesFile)) {
            try {
                for (String line : Files.readAllLines(singlesFile, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) found.add(line.strip());
                }
            } catch (IOException e) {
                found.clear();
            }
        }
        if (found.isEmpty()) {
            long cutoff;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT MAX(sold_at) FROM transactions WHERE seller_uuid NOT LIKE 'keyless:%'");
                 ResultSet rs = ps.executeQuery()) {
                cutoff = rs.next() ? rs.getLong(1) : 0;
            }
            if (cutoff > 0) {
                Map<String, List<Double>> one = new HashMap<>();
                Map<String, List<Double>> many = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT item_key, item_count, unit_price FROM transactions WHERE sold_at > ? AND sold_at <= ? "
                                + "AND is_outlier = 0 AND seller_uuid NOT LIKE 'keyless:%' AND item_key NOT LIKE '%#%' "
                                + "AND (item_count = 1 OR item_count >= 32)")) {
                    ps.setLong(1, cutoff - 3L * 24 * 3_600_000L);
                    ps.setLong(2, cutoff);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            (rs.getInt(2) == 1 ? one : many).computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                    .add(rs.getDouble(3));
                        }
                    }
                }
                for (Map.Entry<String, List<Double>> e : one.entrySet()) {
                    List<Double> stacks = many.get(e.getKey());
                    if (stacks == null || e.getValue().size() < 15 || stacks.size() < 8) continue;
                    if (median(e.getValue()) > median(stacks) * 1.05) found.add(e.getKey());
                }
                try {
                    Files.writeString(singlesFile, String.join(System.lineSeparator(), found)
                            + System.lineSeparator(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    DoughBayClient.LOGGER.warn("DoughBay keyless: could not save the singles markets: {}", e.toString());
                }
            }
        }
        if (found.isEmpty()) {
            for (String line : bundled("keyless-singles.txt")) if (!line.isBlank()) found.add(line.strip());
            if (!found.isEmpty()) {
                try {
                    Files.writeString(singlesFile, String.join(System.lineSeparator(), found)
                            + System.lineSeparator(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    DoughBayClient.LOGGER.warn("DoughBay keyless: could not save the singles markets: {}", e.toString());
                }
            }
        }
        singlesMarkets = java.util.Set.copyOf(found);
        DoughBayClient.LOGGER.info("DoughBay keyless: {} market(s) known to sell better one at a time", found.size());
    }

    /** Lines of a table shipped in the jar, learned from the API weeks; empty when absent. */
    static List<String> bundled(String name) {
        try (java.io.InputStream in = KeylessFeed.class.getResourceAsStream("/doughbay/" + name)) {
            if (in == null) return List.of();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void learnRatios(Connection c) throws SQLException {
        long cutoff;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MAX(sold_at) FROM transactions WHERE seller_uuid NOT LIKE 'keyless:%'");
             ResultSet rs = ps.executeQuery()) {
            cutoff = rs.next() ? rs.getLong(1) : 0;
        }
        if (cutoff <= 0) return;
        long from = cutoff - RATIO_WINDOW_MILLIS;
        Map<String, List<Double>> sales = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_key, unit_price FROM transactions WHERE sold_at > ? AND sold_at <= ? AND is_outlier = 0 "
                        + "AND item_count >= 16 AND seller_uuid NOT LIKE 'keyless:%' AND item_key NOT LIKE '%#%'")) {
            ps.setLong(1, from);
            ps.setLong(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sales.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getDouble(2));
            }
        }
        for (Map.Entry<String, List<Double>> e : sales.entrySet()) {
            if (e.getValue().size() < 8) continue;
            String item = e.getKey();
            OrderFillTracker t = new OrderFillTracker();
            List<long[]> fills = new ArrayList<>();   // {unitPrice, units}
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT observed_at, unit_price, total, delivered FROM orders WHERE item_id = ? "
                            + "AND observed_at > ? AND observed_at <= ? AND parts = '' ORDER BY observed_at")) {
                ps.setString(1, item);
                ps.setLong(2, from);
                ps.setLong(3, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    long readAt = -1;
                    List<OrderFillTracker.Row> read = new ArrayList<>();
                    while (rs.next()) {
                        long at = rs.getLong(1);
                        if (at != readAt && !read.isEmpty()) {
                            for (OrderFillTracker.Fill f : t.read(read, readAt, true)) fills.add(new long[] {f.unitPrice(), f.units()});
                            read.clear();
                        }
                        readAt = at;
                        read.add(new OrderFillTracker.Row(item, rs.getLong(2), rs.getInt(3), rs.getInt(4)));
                    }
                    if (!read.isEmpty()) {
                        for (OrderFillTracker.Fill f : t.read(read, readAt, true)) fills.add(new long[] {f.unitPrice(), f.units()});
                    }
                }
            }
            long units = 0;
            for (long[] f : fills) units += f[1];
            if (units < 128) continue;
            double orderMedian = weightedMedian(fills, units);
            double saleMedian = median(e.getValue());
            if (orderMedian <= 0 || saleMedian <= 0) continue;
            ratios.put(item, Math.max(MIN_RATIO, Math.min(MAX_RATIO, saleMedian / orderMedian)));
        }
        DoughBayClient.LOGGER.info("DoughBay keyless: learned auction-to-order price ratios for {} market(s)", ratios.size());
    }

    /**
     * Writes whatever the order house and our own sales have said since the
     * last call. The first call only learns the book's state from the last few
     * hours of reads, so fills from before the feed started are never written.
     */
    public void ingest(Database db, long now) throws SQLException {
        Connection c = db.connection();
        TransactionRepository transactions = new TransactionRepository(db);
        writtenThisPass.clear();
        int written = 0;
        if (cursor < 0) {
            long maxAt = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT MAX(observed_at) FROM orders");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) maxAt = rs.getLong(1);
            }
            foldReads(c, maxAt - 6 * 3_600_000L, maxAt, false, transactions);
            cursor = maxAt;
        } else {
            long newest = foldReadsAfter(c, cursor, transactions);
            if (newest > cursor) cursor = newest;
        }
        written += writtenSince;
        writtenSince = 0;
        OwnSale own;
        while ((own = OWN_SALES.poll()) != null) {
            String seller = OWN_SELLER_PREFIX + own.player().toLowerCase(Locale.ROOT);
            String hash = Sale.computeHash(own.at(), seller, own.itemKey(), own.count(), own.price());
            Sale sale = new Sale(hash, own.at(), seller, own.player(), own.itemKey(), own.itemKey(), own.count(), own.price());
            if (transactions.insertIfAbsent(sale, "{\"source\":\"keyless-own\"}")) written++;
        }
        Asks seen;
        while ((seen = ASKS.poll()) != null) {
            List<Long> keep = new ArrayList<>(representativeAsks(seen.asks(), MAX_ROWS_PER_SWEEP));
            // A search can show nothing but joke listings - three sand stacks
            // at 87M to 95M each, seen live, when sand trades near 41K - and
            // the asks then agree with each other. So they are held against a
            // price the item has actually traded at: our own sales, order
            // fills, or the API history. More than three times off is dropped.
            double reference = referenceUnitPrice(c, seen.itemKey(), seen.count(), now);
            if (reference > 0) {
                int count = seen.count();
                keep.removeIf(a -> (double) a / count > reference * 3 || (double) a / count < reference / 3);
            }
            java.util.Map<Long, Integer> repeats = new java.util.HashMap<>();
            for (int i = 0; i < keep.size(); i++) {
                long total = Math.round(keep.get(i) * ASK_DISCOUNT);
                if (total <= 0) continue;
                long at = seen.at() + i;   // distinct rows, the same moment
                // The same listing is one observation, however often it is
                // swept. Keyed by the time window rather than the sweep, a lone
                // troll ask (raw mutton at 5M) re-read every few minutes no
                // longer becomes six "samples" and a confident price to bid on.
                int k = repeats.merge(keep.get(i), 1, Integer::sum);
                String hash = Sale.computeHash(seen.at() - seen.at() % ASK_WINDOW_MILLIS, ASK_SELLER,
                        seen.itemKey() + "|" + keep.get(i) + "|" + k, seen.count(), total);
                Sale sale = new Sale(hash, at, ASK_SELLER, "auction asks", seen.itemKey(), seen.itemKey(),
                        seen.count(), total);
                String raw = String.format(Locale.ROOT, "{\"source\":\"keyless-asks\",\"ask\":%d,\"seen\":%d}",
                        keep.get(i), seen.asks().size());
                if (transactions.insertIfAbsent(sale, raw)) written++;
            }
        }
        if (written > 0) recent.add(new long[] {now, written});
        if (now - tradedRefreshedAt > 10 * 60_000L) {
            tradedRefreshedAt = now;
            refreshTraded(c, now);
        }
    }

    private long tradedRefreshedAt;
    private static volatile java.util.Map<String, Double> traded = java.util.Map.of();

    /**
     * The median unit price {@code itemKey} has really traded at lately, singles
     * and stacks apart: our own sales, order fills, the API history. Never asks.
     * 0 when there is not enough of it. The bid desk holds its bids under this,
     * because an ask is only what someone hopes for: a fresh install priced
     * raw mutton off a troll listing and bid 78K a unit for it.
     */
    public static double tradedUnitPrice(String itemKey, boolean single) {
        Double v = traded.get(itemKey + (single ? "|1" : "|n"));
        return v == null ? 0 : v;
    }

    private static void refreshTraded(Connection c, long now) {
        java.util.Map<String, List<Double>> units = new java.util.HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_key, item_count, unit_price FROM transactions WHERE sold_at > ? AND is_outlier = 0 "
                        + "AND seller_uuid <> ? ORDER BY sold_at DESC")) {
            ps.setLong(1, now - 30L * 24 * 3_600_000L);
            ps.setString(2, ASK_SELLER);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1) + (rs.getInt(2) == 1 ? "|1" : "|n");
                    List<Double> list = units.computeIfAbsent(key, x -> new ArrayList<>());
                    if (list.size() < 200) list.add(rs.getDouble(3));
                }
            }
        } catch (SQLException e) {
            return;
        }
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        units.forEach((key, list) -> {
            if (list.size() >= 3) out.put(key, median(list));
        });
        traded = java.util.Map.copyOf(out);
    }

    private int writtenSince;

    /**
     * The median unit price the item has really traded at in the last 30 days
     * (sales, our own receipts, order fills; never asks), or 0 when there is none.
     */
    private static double referenceUnitPrice(Connection c, String itemKey, int count, long now) throws SQLException {
        // Singles against singles, stacks against stacks: a single map sells
        // for about six times a stacked map's unit price, and held against the
        // stack price every honest single ask was thrown out as a joke.
        List<Double> units = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT unit_price FROM transactions WHERE item_key = ? AND sold_at > ? AND is_outlier = 0 "
                        + "AND seller_uuid <> ? AND " + (count == 1 ? "item_count = 1" : "item_count > 1")
                        + " ORDER BY sold_at DESC LIMIT 200")) {
            ps.setString(1, itemKey);
            ps.setLong(2, now - 30L * 24 * 3_600_000L);
            ps.setString(3, ASK_SELLER);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) units.add(rs.getDouble(1));
            }
        }
        return units.size() < 3 ? 0 : median(units);
    }

    private long foldReadsAfter(Connection c, long after, TransactionRepository transactions) throws SQLException {
        return foldReads(c, after, Long.MAX_VALUE, true, transactions);
    }

    /** Feeds every read in (from, to] to the tracker in order; returns the newest read time seen. */
    private long foldReads(Connection c, long from, long to, boolean emit, TransactionRepository transactions)
            throws SQLException {
        long newest = from;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT observed_at, item_key, unit_price, total, delivered FROM orders "
                        + "WHERE observed_at > ? AND observed_at <= ? AND parts = '' ORDER BY observed_at")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                long readAt = -1;
                List<OrderFillTracker.Row> read = new ArrayList<>();
                while (rs.next()) {
                    long at = rs.getLong(1);
                    if (at != readAt && !read.isEmpty()) {
                        write(tracker.read(read, readAt, emit), transactions);
                        read.clear();
                    }
                    readAt = at;
                    newest = Math.max(newest, at);
                    read.add(new OrderFillTracker.Row(rs.getString(2), rs.getLong(3), rs.getInt(4), rs.getInt(5)));
                }
                if (!read.isEmpty()) write(tracker.read(read, readAt, emit), transactions);
            }
        }
        return newest;
    }

    /** Turns fills into full-stack sale rows at the auction-equivalent price. */
    private void write(List<OrderFillTracker.Fill> fills, TransactionRepository transactions) throws SQLException {
        for (OrderFillTracker.Fill f : fills) {
            Double ratio = ratios.get(f.itemKey());
            if (ratio == null) continue;
            int stack = fullStack(f.itemKey());
            int units = Math.min(carry.getOrDefault(f.itemKey(), 0) + f.units(), stack * MAX_STACKS_PER_FILL);
            long total = Math.round(stack * f.unitPrice() * ratio);
            while (units >= stack && total > 0
                    && writtenThisPass.getOrDefault(f.itemKey(), 0) < MAX_STACKS_PER_ITEM_PER_PASS) {
                units -= stack;
                writtenThisPass.merge(f.itemKey(), 1, Integer::sum);
                String hash = Sale.computeHash(f.at(), ORDER_SELLER,
                        f.itemKey() + "|" + f.unitPrice() + "|" + (seq++), stack, total);
                Sale sale = new Sale(hash, f.at(), ORDER_SELLER, "order house", f.itemKey(), f.itemKey(), stack, total);
                String raw = String.format(Locale.ROOT, "{\"source\":\"keyless-orders\",\"orderUnit\":%d,\"ratio\":%.4f}",
                        f.unitPrice(), ratio);
                if (transactions.insertIfAbsent(sale, raw)) writtenSince++;
            }
            carry.put(f.itemKey(), units);
        }
    }

    private static int fullStack(String itemId) {
        try {
            net.minecraft.resources.Identifier ident = net.minecraft.resources.Identifier.tryParse(itemId);
            if (ident == null) return 64;
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(ident);
            return item == null ? 64 : Math.max(1, item.getDefaultMaxStackSize());
        } catch (RuntimeException e) {
            return 64;
        }
    }

    /** Median price over {unitPrice, units} pairs, weighted by units. */
    static double weightedMedian(List<long[]> pairs, long totalUnits) {
        List<long[]> sorted = new ArrayList<>(pairs);
        sorted.sort((a, b) -> Long.compare(a[0], b[0]));
        long half = (totalUnits + 1) / 2;
        long seen = 0;
        for (long[] p : sorted) {
            seen += p[1];
            if (seen >= half) return p[0];
        }
        return 0;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        return n == 0 ? 0 : n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }
}
