package dev.doughbay.fabric;

import dev.doughbay.core.model.Position;
import dev.doughbay.storage.Database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Who else is flipping on the auction house, when they are on, what they
 * trade and how they price, rebuilt once a minute from the ledger on a
 * background thread.
 *
 * <p>The sales feed and the listing snapshots both name the seller, so the
 * sell side of every rival is fully visible. Their buys are not in any
 * feed, but a flip leaves a fingerprint: a completed sale of the same item
 * and stack size by someone else, followed within minutes by the rival
 * listing it higher. Those pairs are the reconstructed trades.
 *
 * <p>Buyers are only visible on the mod's own sales, from the chat line,
 * and are kept in the {@code sale_buyers} table.
 */
public final class RivalIntel {
    private static final long REFRESH_MILLIS = 60_000;
    private static final long WINDOW_MILLIS = 24 * 3_600_000L;
    private static final long ACTIVE_MILLIS = 10 * 60_000L;
    private static final long QUIET_MILLIS = 60 * 60_000L;
    private static final long LISTING_LOOKBACK_MILLIS = 2 * 3_600_000L;
    private static final long FLIP_BUY_WINDOW_MILLIS = 30 * 60_000L;
    /** A rival is a seller with this much volume across this many items in a day. */
    private static final int MIN_SALES = 60;
    private static final int MIN_ITEMS = 6;
    private static final int MAX_RIVALS = 12;
    private static final int FLIP_RIVALS = 8;
    private static final int MAX_LISTINGS_PER_RIVAL = 120;
    /** A seller with this many sales in a day is a trader worth classifying. */
    private static final int TRADER_MIN_SALES = 20;
    /** Bot-likelihood score at or above which a trader counts as automated. */
    private static final int BOT_SCORE_THRESHOLD = 60;
    /** Margin assumed for a rival whose flips could not be reconstructed. */
    private static final double DEFAULT_MARGIN = 0.20;

    /** One reconstructed rival trade; soldPrice is 0 while the listing is still up. */
    public record Flip(String itemKey, int count, long buyPrice, long listPrice, long soldPrice,
                       long boughtAt, long listedAt, long soldAt) {
        public long margin() {
            return (soldPrice > 0 ? soldPrice : listPrice) - buyPrice;
        }

        public double marginPercent() {
            return buyPrice > 0 ? margin() * 100.0 / buyPrice : 0;
        }
    }

    public record Rival(String name, int sales, int items, long revenue, int overlap,
                        long lastSaleAt, long lastListingAt, int[] hourly, List<String> topItems,
                        List<Flip> flips, double averageMarginPercent, long medianTurnaroundMillis,
                        int botScore, int activeHours, long estimatedProfit, boolean profitFromFlips) {
        public long lastSeenAt() {
            return Math.max(lastSaleAt, lastListingAt);
        }

        public long averageTicket() {
            return sales > 0 ? revenue / sales : 0;
        }

        public boolean active(long now) {
            return now - lastSeenAt() <= ACTIVE_MILLIS;
        }

        public String status(long now) {
            long age = now - lastSeenAt();
            return age <= ACTIVE_MILLIS ? "active" : age <= QUIET_MILLIS ? "quiet" : "offline";
        }
    }

    public record Buyer(String name, int purchases, long spent, long lastAt, String lastItem) {
    }

    /**
     * @param traders    sellers with at least {@link #TRADER_MIN_SALES} sales in the day
     * @param likelyBots those traders whose pattern scores as automated
     */
    /**
     * A market a rival has proven with reconstructed flips: what they pay,
     * what they get, how often. The Underdog mode buys at their buy price
     * and lists a notch under their sale price.
     */
    public record ShadowMarket(String itemId, int count, String rival, int flips,
                               long medianBuy, long medianSale, long medianTurnaroundMillis) {
        public double marginPercent() {
            return medianBuy > 0 ? (medianSale - medianBuy) * 100.0 / medianBuy : 0;
        }
    }

    public record Snapshot(long refreshedAt, String status, List<Rival> rivals, int activeNow,
                           Set<String> crowded, Map<String, Integer> proven, List<Buyer> buyers,
                           int traders, int likelyBots, List<ShadowMarket> shadows) {
        public static Snapshot empty(String status) {
            return new Snapshot(0, status, List.of(), 0, Set.of(), Map.of(), List.of(), 0, 0, List.of());
        }

        public double botSharePercent() {
            return traders > 0 ? likelyBots * 100.0 / traders : 0;
        }

        /** Rivals are known and none has done anything for ten minutes. */
        public boolean quietField() {
            return refreshedAt > 0 && !rivals.isEmpty() && activeNow == 0;
        }

        public boolean crowded(String itemId, int count) {
            return crowded.contains(marketKey(itemId, count));
        }

        public int provenFlips(String itemId, int count) {
            return proven.getOrDefault(marketKey(itemId, count), 0);
        }
    }

    public static String marketKey(String itemId, int count) {
        return itemId + "|" + count;
    }

    private final Path databasePath;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doughbay-rival-writer");
        t.setDaemon(true);
        return t;
    });
    private volatile String ownName = "";
    private volatile Snapshot snapshot = Snapshot.empty("Reading the ledger");
    private volatile boolean indexesReady;

    RivalIntel(Path databasePath) {
        this.databasePath = databasePath;
        Thread thread = new Thread(this::loop, "doughbay-rival-intel");
        thread.setDaemon(true);
        thread.start();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void setOwnName(String name) {
        if (name != null && !name.isBlank()) ownName = name;
    }

    /** Remembers who bought one of the mod's listings, off the render thread. */
    public void recordBuyer(Position sold, String buyer) {
        if (sold == null || buyer == null || buyer.isBlank()) return;
        String name = buyer.startsWith(".") ? buyer.substring(1) : buyer;
        writer.submit(() -> {
            try (Database db = new Database(databasePath)) {
                try (PreparedStatement ps = db.connection().prepareStatement(
                        "INSERT INTO sale_buyers(position_id, buyer_name, item_key, quantity, sale_price, sold_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setLong(1, sold.positionId());
                    ps.setString(2, name);
                    ps.setString(3, sold.itemKey());
                    ps.setInt(4, sold.quantity());
                    ps.setLong(5, sold.salePrice());
                    ps.setLong(6, sold.closedAt() > 0 ? sold.closedAt() : System.currentTimeMillis());
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                DoughBayClient.LOGGER.warn("DoughBay could not record buyer {}: {}", name, e.toString());
            }
        });
    }

    private void loop() {
        while (true) {
            try {
                refresh();
            } catch (Throwable t) {
                snapshot = Snapshot.empty("Rival read failed: " + t);
                DoughBayClient.LOGGER.warn("DoughBay rival intel failed: {}", t.toString());
            }
            try {
                Thread.sleep(REFRESH_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void refresh() throws Exception {
        long now = System.currentTimeMillis();
        long since = now - WINDOW_MILLIS;
        String own = ownName;
        try (Database db = new Database(databasePath)) {
            Connection c = db.connection();
            ensureIndexes(c);
            Set<String> ours = ourMarkets(c, now);

            // One pass over the day's sales, aggregated per seller.
            Map<String, SellerAgg> sellers = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT seller_name, item_key, item_count, total_price, sold_at FROM transactions WHERE sold_at > ?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        if (name == null || name.isBlank()) continue;
                        SellerAgg agg = sellers.computeIfAbsent(name, k -> new SellerAgg());
                        agg.add(rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getLong(5), now);
                    }
                }
            }
            List<Map.Entry<String, SellerAgg>> ranked = new ArrayList<>();
            int traders = 0;
            int likelyBots = 0;
            for (Map.Entry<String, SellerAgg> e : sellers.entrySet()) {
                SellerAgg agg = e.getValue();
                if (e.getKey().equalsIgnoreCase(own)) continue;
                if (agg.sales >= TRADER_MIN_SALES) {
                    traders++;
                    if (agg.botScore() >= (int) Tuning.get("rival.bot_score_threshold")) likelyBots++;
                }
                if (agg.sales < (int) Tuning.get("rival.min_sales")
                        || agg.items.size() < (int) Tuning.get("rival.min_items")) continue;
                agg.overlap = 0;
                for (String key : agg.items.keySet()) if (ours.contains(base(key))) agg.overlap++;
                ranked.add(e);
            }
            ranked.sort((a, b) -> Double.compare(score(b.getValue()), score(a.getValue())));
            if (ranked.size() > MAX_RIVALS) ranked = new ArrayList<>(ranked.subList(0, MAX_RIVALS));

            Set<String> crowded = new HashSet<>();
            Map<String, Integer> proven = new HashMap<>();
            List<Rival> rivals = new ArrayList<>();
            int index = 0;
            for (Map.Entry<String, SellerAgg> e : ranked) {
                String name = e.getKey();
                SellerAgg agg = e.getValue();
                long lastListing = lastListingAt(c, name, now);
                crowded.addAll(agg.recentMarkets);
                crowded.addAll(recentListingMarkets(c, name, now));
                List<Flip> flips = index < FLIP_RIVALS ? reconstructFlips(c, name, since) : List.of();
                double marginSum = 0;
                int marginCount = 0;
                List<Long> turnarounds = new ArrayList<>();
                for (Flip flip : flips) {
                    marginSum += flip.marginPercent();
                    marginCount++;
                    proven.merge(marketKey(flip.itemKey(), flip.count()), 1, Integer::sum);
                    if (flip.soldAt() > 0 && flip.boughtAt() > 0) turnarounds.add(flip.soldAt() - flip.boughtAt());
                }
                Collections.sort(turnarounds);
                long medianTurnaround = turnarounds.isEmpty() ? 0 : turnarounds.get(turnarounds.size() / 2);
                double averageMargin = marginCount == 0 ? 0 : marginSum / marginCount;
                boolean fromFlips = marginCount >= 3;
                double marginFraction = fromFlips ? Math.max(0, averageMargin / 100.0) : DEFAULT_MARGIN;
                long estimatedProfit = Math.round(agg.revenue * marginFraction / (1.0 + marginFraction));
                rivals.add(new Rival(name, agg.sales, agg.items.size(), agg.revenue, agg.overlap,
                        agg.lastSaleAt, lastListing, agg.hourly, agg.topItems(5), flips,
                        averageMargin, medianTurnaround, agg.botScore(), agg.activeHours(),
                        estimatedProfit, fromFlips));
                index++;
            }
            int active = 0;
            for (Rival r : rivals) if (r.active(now)) active++;
            List<Buyer> buyers = buyers(c);
            snapshot = new Snapshot(now, rivals.size() + " rival(s), " + active + " active",
                    List.copyOf(rivals), active, Set.copyOf(crowded), Map.copyOf(proven), buyers,
                    traders, likelyBots, shadowMarkets(rivals, commodityItems(c)));
        }
    }

    private static double score(SellerAgg agg) {
        return agg.sales * (1.0 + agg.overlap);
    }

    /**
     * Every market with enough reconstructed rival flips, described by the
     * rival who works it most: median buy, median sale, median turnaround.
     * Sorted by margin, best first.
     */
    /**
     * Items the collector has marked as plain commodities. The feed keys an
     * enchanted book as "minecraft:enchanted_book" whatever it holds, but
     * the stack that arrives carries enchantments and lore, and the mod
     * only lists exact plain stacks. Shadowing such a market buys something
     * that can never be relisted, so Underdog stays on commodities.
     */
    private static Set<String> commodityItems(Connection c) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_key FROM items WHERE commodity_eligible = 1");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(base(rs.getString(1)));
        }
        return out;
    }

    private static final Set<String> NEVER_SHADOW = Set.of(
            "minecraft:enchanted_book", "minecraft:potion", "minecraft:splash_potion",
            "minecraft:lingering_potion", "minecraft:tipped_arrow", "minecraft:filled_map",
            "minecraft:written_book", "minecraft:writable_book", "minecraft:firework_rocket",
            "minecraft:firework_star", "minecraft:suspicious_stew", "minecraft:player_head",
            "minecraft:goat_horn", "minecraft:ominous_bottle", "minecraft:painting",
            "minecraft:bundle", "minecraft:shield", "minecraft:banner");

    private static List<ShadowMarket> shadowMarkets(List<Rival> rivals, Set<String> commodities) {
        int minFlips = (int) Tuning.get("underdog.min_flips");
        double minMargin = Tuning.get("underdog.min_rival_margin_pct");
        Map<String, List<Flip>> byMarket = new HashMap<>();
        Map<String, Map<String, Integer>> byRival = new HashMap<>();
        for (Rival r : rivals) {
            for (Flip f : r.flips()) {
                if (f.buyPrice() <= 0 || f.soldPrice() <= 0) continue;
                if (NEVER_SHADOW.contains(f.itemKey()) || f.itemKey().endsWith("_banner")) continue;
                if (!commodities.isEmpty() && !commodities.contains(f.itemKey())) continue;
                String key = marketKey(f.itemKey(), f.count());
                byMarket.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
                byRival.computeIfAbsent(key, k -> new HashMap<>()).merge(r.name(), 1, Integer::sum);
            }
        }
        List<ShadowMarket> out = new ArrayList<>();
        for (Map.Entry<String, List<Flip>> e : byMarket.entrySet()) {
            List<Flip> flips = e.getValue();
            if (flips.size() < minFlips) continue;
            List<Long> buys = new ArrayList<>();
            List<Long> sales = new ArrayList<>();
            List<Long> turns = new ArrayList<>();
            for (Flip f : flips) {
                buys.add(f.buyPrice());
                sales.add(f.soldPrice());
                if (f.soldAt() > 0 && f.boughtAt() > 0) turns.add(f.soldAt() - f.boughtAt());
            }
            Collections.sort(buys);
            Collections.sort(sales);
            Collections.sort(turns);
            long buy = buys.get(buys.size() / 2);
            long sale = sales.get(sales.size() / 2);
            if (buy <= 0 || (sale - buy) * 100.0 / buy < minMargin) continue;
            String rival = "";
            int best = 0;
            for (Map.Entry<String, Integer> r : byRival.getOrDefault(e.getKey(), Map.of()).entrySet()) {
                if (r.getValue() > best) {
                    best = r.getValue();
                    rival = r.getKey();
                }
            }
            Flip sample = flips.get(0);
            out.add(new ShadowMarket(sample.itemKey(), sample.count(), rival, flips.size(), buy, sale,
                    turns.isEmpty() ? 0 : turns.get(turns.size() / 2)));
        }
        out.sort((a, b) -> Double.compare(b.marginPercent(), a.marginPercent()));
        // The underdog is silent when it finds nothing, which reads exactly
        // like the underdog being switched off. Say what was looked at.
        int flipsSeen = 0;
        for (List<Flip> f : byMarket.values()) flipsSeen += f.size();
        DoughBayClient.LOGGER.info(
                "DoughBay underdog: {} shadowed market(s) from {} rival flip(s) across {} market(s) "
                        + "(needs {} flips and {}% margin{})",
                out.size(), flipsSeen, byMarket.size(), minFlips, (long) minMargin,
                commodities.isEmpty() ? "" : ", limited to " + commodities.size() + " commodity items");
        return List.copyOf(out);
    }

    private static final class SellerAgg {
        int sales;
        long revenue;
        long lastSaleAt;
        int overlap;
        final Map<String, Integer> items = new HashMap<>();
        final int[] hourly = new int[24];
        final Set<String> recentMarkets = new HashSet<>();

        void add(String itemKey, int count, long price, long soldAt, long now) {
            sales++;
            revenue += Math.max(0, price);
            lastSaleAt = Math.max(lastSaleAt, soldAt);
            if (itemKey != null) {
                items.merge(itemKey, 1, Integer::sum);
                if (now - soldAt <= ACTIVE_MILLIS) recentMarkets.add(marketKey(base(itemKey), count));
            }
            int hour = Instant.ofEpochMilli(soldAt).atZone(ZoneId.systemDefault()).getHour();
            hourly[Math.max(0, Math.min(23, hour))]++;
        }

        int activeHours() {
            int n = 0;
            for (int v : hourly) if (v > 0) n++;
            return n;
        }

        /**
         * 0 to 100. A person sells in bursts, during their day, in the few
         * things they have; an auto-trader sells around the clock, through
         * the night, across many unrelated items, in volume.
         */
        int botScore() {
            double hours = activeHours() / 24.0;
            int night = 0;
            for (int h = 1; h <= 5; h++) if (hourly[h] > 0) night++;
            double nightShare = night / 5.0;
            double breadth = Math.min(1.0, items.size() / 10.0);
            double volume = Math.min(1.0, sales / 150.0);
            return (int) Math.round(100 * (0.35 * hours + 0.20 * nightShare + 0.30 * breadth + 0.15 * volume));
        }

        List<String> topItems(int n) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(items.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, Integer> e : entries) {
                if (out.size() >= n) break;
                out.add(base(e.getKey()));
            }
            return out;
        }
    }

    private void ensureIndexes(Connection c) throws SQLException {
        if (indexesReady) return;
        try (Statement st = c.createStatement()) {
            st.execute("CREATE INDEX IF NOT EXISTS idx_tx_seller_time ON transactions(seller_name, sold_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ls_seller_time ON listing_snapshots(seller_name, observed_at)");
        }
        indexesReady = true;
    }

    private static Set<String> ourMarkets(Connection c, long now) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT item_key FROM positions WHERE mode = 'REAL' AND purchased_at > ?")) {
            ps.setLong(1, now - 7 * WINDOW_MILLIS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(base(rs.getString(1)));
            }
        }
        return out;
    }

    private static long lastListingAt(Connection c, String seller, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT MAX(observed_at) FROM listing_snapshots WHERE seller_name = ? AND observed_at > ?")) {
            ps.setString(1, seller);
            ps.setLong(2, now - LISTING_LOOKBACK_MILLIS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private static Set<String> recentListingMarkets(Connection c, String seller, long now) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT item_key, item_count FROM listing_snapshots WHERE seller_name = ? AND observed_at > ?")) {
            ps.setString(1, seller);
            ps.setLong(2, now - ACTIVE_MILLIS);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(marketKey(base(rs.getString(1)), rs.getInt(2)));
            }
        }
        return out;
    }

    /**
     * The rival's completed sales of the day, each paired with the most
     * recent cheaper sale of the same stack by someone else within the
     * half hour before it: their buy. The sales feed is complete, so this
     * sees every flip that closed; only listings still up are invisible.
     */
    private static List<Flip> reconstructFlips(Connection c, String seller, long since) throws SQLException {
        List<Object[]> sales = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT item_key, item_count, total_price, sold_at FROM transactions "
                        + "WHERE seller_name = ? AND sold_at > ? ORDER BY sold_at DESC LIMIT ?")) {
            ps.setString(1, seller);
            ps.setLong(2, since);
            ps.setInt(3, MAX_LISTINGS_PER_RIVAL);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sales.add(new Object[] {rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)});
                }
            }
        }
        List<Flip> out = new ArrayList<>();
        try (PreparedStatement buy = c.prepareStatement(
                "SELECT total_price, sold_at FROM transactions WHERE item_key = ? AND item_count = ? "
                        + "AND sold_at BETWEEN ? AND ? AND seller_name <> ? AND total_price < ? ORDER BY sold_at DESC LIMIT 1")) {
            for (Object[] row : sales) {
                String itemKey = (String) row[0];
                int count = (Integer) row[1];
                long soldPrice = (Long) row[2];
                long soldAt = (Long) row[3];
                buy.setString(1, itemKey);
                buy.setInt(2, count);
                buy.setLong(3, soldAt - FLIP_BUY_WINDOW_MILLIS);
                buy.setLong(4, soldAt);
                buy.setString(5, seller);
                buy.setLong(6, soldPrice);
                long buyPrice = 0;
                long boughtAt = 0;
                try (ResultSet rs = buy.executeQuery()) {
                    if (rs.next()) {
                        buyPrice = rs.getLong(1);
                        boughtAt = rs.getLong(2);
                    }
                }
                if (buyPrice <= 0) continue;
                out.add(new Flip(base(itemKey), count, buyPrice, soldPrice, soldPrice, boughtAt, boughtAt, soldAt));
            }
        }
        return out;
    }

    private static List<Buyer> buyers(Connection c) throws SQLException {
        Map<String, long[]> agg = new LinkedHashMap<>();
        Map<String, String> lastItem = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT buyer_name, item_key, sale_price, sold_at FROM sale_buyers ORDER BY sold_at DESC LIMIT 5000");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString(1);
                long[] a = agg.computeIfAbsent(name, k -> new long[3]);
                a[0]++;
                a[1] += Math.max(0, rs.getLong(3));
                a[2] = Math.max(a[2], rs.getLong(4));
                lastItem.putIfAbsent(name, base(rs.getString(2)));
            }
        }
        List<Buyer> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : agg.entrySet()) {
            long[] a = e.getValue();
            out.add(new Buyer(e.getKey(), (int) a[0], a[1], a[2], lastItem.getOrDefault(e.getKey(), "")));
        }
        out.sort((x, y) -> Integer.compare(y.purchases(), x.purchases()));
        return out.size() > 25 ? List.copyOf(out.subList(0, 25)) : List.copyOf(out);
    }

    static String base(String itemKey) {
        if (itemKey == null) return "";
        int hash = itemKey.indexOf('#');
        return hash >= 0 ? itemKey.substring(0, hash) : itemKey;
    }

    /** "3 of 8 on" for the HUD; "" when nothing is known yet. */
    public String shortStatus() {
        Snapshot s = snapshot;
        if (s.refreshedAt() == 0 || s.rivals().isEmpty()) return "";
        return "rivals " + s.activeNow() + "/" + s.rivals().size();
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
