package dev.doughbay.fabric;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.storage.Database;
import dev.doughbay.storage.PositionRepository;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The real performance ledger, built from the position table every few
 * seconds on a background thread so the Stats tab never touches the
 * database on the render thread.
 */
public final class LedgerStatsProvider implements PerformanceStatsProvider {
    private static final long REFRESH_MILLIS = 8_000;

    private final Path databasePath;
    private volatile PerformanceStatsSnapshot real =
            PerformanceStatsSnapshot.empty(PerformanceStatsSnapshot.Mode.REAL, "Reading the ledger");
    private volatile PerformanceStatsSnapshot paper =
            PerformanceStatsSnapshot.empty(PerformanceStatsSnapshot.Mode.PAPER, "No paper ledger");
    private volatile long refreshedAt;
    record CapitalCache(Map<String,long[]> byAccount,long at,long unattributed) {
        CapitalBreakdown.Stock account(String name){
            if(at<=0||name==null||name.isBlank())return null;
            long[] costs=byAccount.getOrDefault(name.toLowerCase(Locale.ROOT),new long[4]);
            return new CapitalBreakdown.Stock(at,costs[0],costs[1],costs[2],costs[3],unattributed);
        }
    }
    private volatile CapitalCache capitalCache=new CapitalCache(Map.of(),0,0);
    public CapitalBreakdown.Stock capital(String account) {
        return capitalCache.account(account);
    }
    static CapitalCache readCapital(Database db,long now)throws Exception{
        Map<String,long[]> rows=new LinkedHashMap<>();long unknown=0;
        try(var stmt=db.connection().prepareStatement("SELECT client,status,COALESCE(SUM(purchase_price),0),COALESCE(SUM(target_price),0) FROM positions WHERE mode='REAL' AND status IN ('PURCHASED','LISTED','UNRESOLVED','EXPIRED') GROUP BY client,status")){try(var rs=stmt.executeQuery()){
            while(rs.next()){
                String owner=rs.getString(1),state=rs.getString(2);long cost=Math.max(0,rs.getLong(3));
                if(owner==null||owner.isBlank()){unknown=Math.addExact(unknown,cost);continue;}
                long[] row=rows.computeIfAbsent(owner.toLowerCase(Locale.ROOT),key->new long[4]);
                if(state.equals("PURCHASED"))row[0]=Math.addExact(row[0],cost);
                else if(state.equals("LISTED")){row[1]=Math.addExact(row[1],cost);row[2]=Math.addExact(row[2],Math.max(0,rs.getLong(4)));}
                else row[3]=Math.addExact(row[3],cost);
            }
        }}
        return new CapitalCache(Map.copyOf(rows),now,unknown);
    }
    private volatile Map<ItemBoard.Range, ItemBoard.Board> boards = Map.of();

    LedgerStatsProvider(Path databasePath) {
        this.databasePath = databasePath;
        Thread thread = new Thread(this::loop, "doughbay-ledger-stats");
        thread.setDaemon(true);
        thread.start();
    }

    public long refreshedAt() {
        return refreshedAt;
    }

    /** What each item has done over {@code range}, as of the last read of the ledger. */
    public ItemBoard.Board board(ItemBoard.Range range) {
        ItemBoard.Board board = boards.get(range);
        return board == null ? ItemBoard.Board.empty(range) : board;
    }

    private void loop() {
        while (true) {
            try {
                refresh();
            } catch (Throwable t) {
                real = PerformanceStatsSnapshot.empty(PerformanceStatsSnapshot.Mode.REAL,
                        "Ledger read failed: " + t);
            }
            try {
                Thread.sleep(REFRESH_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void refresh() throws Exception {
        List<Position> positions;
        try (Database db = new Database(databasePath)) {
            positions = new PositionRepository(db).findAll("REAL");
            try { capitalCache=readCapital(db,System.currentTimeMillis()); } catch(Exception unavailable) { capitalCache=new CapitalCache(Map.of(),0,0); }
        }
        long now = System.currentTimeMillis();
        real = build(PerformanceStatsSnapshot.Mode.REAL, positions, now);
        // The same ledger, item by item, for each stretch of time the item board offers.
        Map<ItemBoard.Range, ItemBoard.Board> built = new java.util.EnumMap<>(ItemBoard.Range.class);
        for (ItemBoard.Range range : ItemBoard.Range.values()) {
            built.put(range, ItemBoard.build(positions, now, range, java.time.ZoneId.systemDefault(),
                    LedgerStatsProvider::displayName));
        }
        boards = built;
        refreshedAt = System.currentTimeMillis();
    }

    @Override
    public PerformanceStatsSnapshot snapshot(PerformanceStatsSnapshot.Mode mode, boolean demoFeed,
                                             long nowMillis) {
        return mode == PerformanceStatsSnapshot.Mode.PAPER ? paper : real;
    }

    static PerformanceStatsSnapshot build(PerformanceStatsSnapshot.Mode mode,
                                          List<Position> positions, long now) {
        long totalInvested = 0;
        long settledCapital = 0;
        long realizedProfit = 0;
        long openCost = 0;
        long unitsSold = 0;
        int purchased = 0;
        int sales = 0;
        int open = 0;
        int profitable = 0;
        int negative = 0;
        int breakEven = 0;
        double bestRoi = 0;
        long bestProfit = 0;
        long firstPurchase = 0;
        List<Long> holds = new ArrayList<>();
        List<Position> sold = new ArrayList<>();
        Map<String, long[]> byItem = new LinkedHashMap<>();
        for (Position p : positions) {
            if (p.status() == PositionStatus.SIGNAL
                    || p.status() == PositionStatus.MISSED_BEFORE_PURCHASE
                    || p.status() == PositionStatus.CANCELLED) continue;
            purchased++;
            totalInvested += Math.max(0, p.purchasePrice());
            if (firstPurchase == 0 || (p.purchasedAt() > 0 && p.purchasedAt() < firstPurchase)) {
                firstPurchase = p.purchasedAt();
            }
            if (p.status() == PositionStatus.SOLD) {
                sales++;
                long profit = Double.isFinite(p.realizedProfit())
                        ? Math.round(p.realizedProfit()) : p.salePrice() - p.purchasePrice();
                settledCapital += Math.max(0, p.purchasePrice());
                realizedProfit += profit;
                unitsSold += Math.max(0, p.quantity());
                if (profit > 0) profitable++;
                else if (profit < 0) negative++;
                else breakEven++;
                if (p.purchasePrice() > 0) {
                    bestRoi = Math.max(bestRoi, profit * 100.0 / p.purchasePrice());
                }
                bestProfit = Math.max(bestProfit, profit);
                if (p.closedAt() > 0 && p.purchasedAt() > 0 && p.closedAt() >= p.purchasedAt()) {
                    holds.add(p.closedAt() - p.purchasedAt());
                }
                sold.add(p);
                long[] agg = byItem.computeIfAbsent(baseKey(p.itemKey()), k -> new long[4]);
                agg[0]++;
                agg[1] += Math.max(0, p.quantity());
                agg[2] += Math.max(0, p.purchasePrice());
                agg[3] += profit;
            } else if (p.status() == PositionStatus.LISTED || p.status() == PositionStatus.PURCHASED) {
                open++;
                openCost += Math.max(0, p.purchasePrice());
            }
        }
        sold.sort(Comparator.comparingLong(Position::closedAt));
        List<PerformanceStatsSnapshot.ProfitPoint> history = new ArrayList<>();
        long cumulative = 0;
        long deployed = 0;
        for (Position p : sold) {
            long profit = Double.isFinite(p.realizedProfit())
                    ? Math.round(p.realizedProfit()) : p.salePrice() - p.purchasePrice();
            cumulative += profit;
            deployed += Math.max(0, p.purchasePrice());
            history.add(new PerformanceStatsSnapshot.ProfitPoint(p.closedAt(), cumulative, deployed));
        }
        holds.sort(null);
        long medianHold = holds.isEmpty() ? 0 : holds.get(holds.size() / 2);
        long lifetime = firstPurchase > 0 ? Math.max(0, now - firstPurchase) : 0;
        Long profitPerHour = lifetime > 360_000
                ? Math.round(realizedProfit / (lifetime / 3_600_000.0)) : null;
        List<PerformanceStatsSnapshot.ItemPerformance> items = new ArrayList<>();
        for (Map.Entry<String, long[]> e : byItem.entrySet()) {
            long[] a = e.getValue();
            items.add(new PerformanceStatsSnapshot.ItemPerformance(e.getKey(),
                    displayName(e.getKey()), (int) a[0], a[1], a[2], a[3]));
        }
        return new PerformanceStatsSnapshot(mode, false,
                sales + " sales, " + open + " open",
                totalInvested, settledCapital, realizedProfit, openCost, 0,
                unitsSold, purchased, sales, open, 0, profitable, negative, breakEven,
                bestRoi, bestProfit, medianHold, null, null, profitPerHour,
                0, lifetime, history, items, List.of());
    }

    private static String baseKey(String itemKey) {
        if (itemKey == null) return "unknown";
        int hash = itemKey.indexOf('#');
        return hash >= 0 ? itemKey.substring(0, hash) : itemKey;
    }

    static String displayName(String itemId) {
        String client = AutomatedExecutionDriver.displayNameFor(itemId);
        if (!client.isEmpty()) return client;
        String base = itemId.substring(itemId.indexOf(':') + 1).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : base.split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
