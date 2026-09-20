package dev.doughbay.fabric;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable view model for one strictly separated performance ledger. */
public record PerformanceStatsSnapshot(
        Mode mode,
        boolean demo,
        String status,
        long totalInvested,
        long settledCapital,
        long realizedProfit,
        long openCostBasis,
        long unreconciledCost,
        long unitsSold,
        int purchasedTrades,
        int completedSales,
        int openTrades,
        int unreconciledTrades,
        int profitableSales,
        int netNegativeSales,
        int breakEvenSales,
        double bestRoiPercent,
        long bestTradeProfit,
        long medianHoldMillis,
        Double maxDrawdownPercent,
        Double capitalUtilizationPercent,
        Long profitPerHour,
        long currentSessionActiveMillis,
        long lifetimeActiveMillis,
        List<ProfitPoint> profitHistory,
        List<ItemPerformance> items,
        List<Milestone> milestones) {

    public enum Mode {
        PAPER,
        REAL;

        Mode next() {
            return this == PAPER ? REAL : PAPER;
        }
    }

    public record ProfitPoint(long atMillis, long cumulativeProfit,
                              long cumulativeCapitalDeployed) {
    }

    public record ItemPerformance(String itemKey, String displayName,
                                  int completedSales, long unitsSold,
                                  long invested, long realizedProfit) {
        public ItemPerformance {
            itemKey = Objects.requireNonNullElse(itemKey, "unknown");
            displayName = Objects.requireNonNullElse(displayName, itemKey);
            if (completedSales < 0 || unitsSold < 0 || invested < 0) {
                throw new IllegalArgumentException("item performance counts cannot be negative");
            }
        }
    }

    public record Milestone(String id, String title, String description,
                            long progress, long target) {
        public Milestone {
            id = Objects.requireNonNullElse(id, "milestone");
            title = Objects.requireNonNullElse(title, "Milestone");
            description = Objects.requireNonNullElse(description, "");
            progress = Math.max(0, progress);
            if (target <= 0) throw new IllegalArgumentException("milestone target must be positive");
        }

        public boolean unlocked() {
            return progress >= target;
        }

        public double completion() {
            return Math.min(1.0, (double) progress / target);
        }
    }

    public PerformanceStatsSnapshot {
        mode = Objects.requireNonNull(mode, "mode");
        status = Objects.requireNonNullElse(status, "unknown");
        profitHistory = List.copyOf(Objects.requireNonNullElse(profitHistory, List.of()));
        items = List.copyOf(Objects.requireNonNullElse(items, List.of()));
        milestones = List.copyOf(Objects.requireNonNullElse(milestones, List.of()));
        if (totalInvested < 0 || settledCapital < 0 || openCostBasis < 0
                || unreconciledCost < 0 || unitsSold < 0 || purchasedTrades < 0
                || completedSales < 0 || openTrades < 0 || unreconciledTrades < 0
                || profitableSales < 0 || netNegativeSales < 0 || breakEvenSales < 0
                || medianHoldMillis < 0
                || currentSessionActiveMillis < 0 || lifetimeActiveMillis < 0) {
            throw new IllegalArgumentException("performance counters cannot be negative");
        }
        if (maxDrawdownPercent != null && !Double.isFinite(maxDrawdownPercent)) {
            throw new IllegalArgumentException("max drawdown must be finite when available");
        }
        if (capitalUtilizationPercent != null && !Double.isFinite(capitalUtilizationPercent)) {
            throw new IllegalArgumentException("capital utilization must be finite when available");
        }
        if (profitableSales + netNegativeSales + breakEvenSales > completedSales) {
            throw new IllegalArgumentException("classified outcomes exceed completed sales");
        }
    }

    public boolean hasHistory() {
        return completedSales > 0 || purchasedTrades > 0 || totalInvested > 0
                || currentSessionActiveMillis > 0 || lifetimeActiveMillis > 0
                || !profitHistory.isEmpty();
    }

    public double realizedRoiPercent() {
        return settledCapital <= 0 ? 0 : (double) realizedProfit / settledCapital * 100.0;
    }

    public double winRatePercent() {
        return completedSales <= 0 ? 0 : (double) profitableSales / completedSales * 100.0;
    }

    public List<ItemPerformance> mostProfitableItems() {
        List<ItemPerformance> ranked = new ArrayList<>(items);
        ranked.sort(Comparator.comparingLong(ItemPerformance::realizedProfit).reversed()
                .thenComparing(ItemPerformance::displayName));
        return List.copyOf(ranked);
    }

    public ItemPerformance mostSoldItem() {
        return items.stream()
                .max(Comparator.comparingLong(ItemPerformance::unitsSold)
                        .thenComparing(ItemPerformance::displayName))
                .orElse(null);
    }

    public static PerformanceStatsSnapshot empty(Mode mode, String status) {
        return new PerformanceStatsSnapshot(mode, false, status,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, null, null, null, 0, 0,
                List.of(), List.of(), List.of());
    }

    /** Stable placeholder dataset used only when the whole market snapshot is demo data. */
    public static PerformanceStatsSnapshot demo(Mode mode, long nowMillis) {
        long day = 24L * 60 * 60 * 1_000;
        int[] daysAgo = {30, 28, 26, 24, 21, 19, 16, 14, 11, 9, 7, 5, 2, 0};
        long[] profit = {0, 8_200, 17_400, 13_900, 32_600, 48_100, 67_200,
                61_900, 94_800, 121_400, 145_300, 137_900, 168_200, 184_600};
        long[] deployed = {0, 110_000, 245_000, 410_000, 625_000, 780_000, 970_000,
                1_190_000, 1_460_000, 1_690_000, 1_920_000, 2_240_000, 2_570_000, 2_840_000};
        List<ProfitPoint> history = new ArrayList<>();
        for (int i = 0; i < daysAgo.length; i++) {
            history.add(new ProfitPoint(nowMillis - daysAgo[i] * day,
                    profit[i], deployed[i]));
        }

        List<ItemPerformance> items = List.of(
                new ItemPerformance("minecraft:obsidian", "Obsidian x64",
                        3, 192, 1_220_000, 93_000),
                new ItemPerformance("minecraft:redstone", "Redstone x64",
                        7, 448, 86_000, 39_800),
                new ItemPerformance("minecraft:ender_pearl", "Ender Pearl x16",
                        5, 80, 142_000, 28_700),
                new ItemPerformance("minecraft:glowstone", "Glowstone x64",
                        3, 192, 94_000, 23_100));

        List<Milestone> milestones = List.of(
                new Milestone("first_sale", "FIRST SALE", "Complete one reconciled sale", 1, 1),
                new Milestone("ten_sales", "MARKET REGULAR", "Complete 10 sales", 18, 10),
                new Milestone("profit_100k", "$100K CLUB", "Realize $100k profit", 184_600, 100_000),
                new Milestone("deployed_1m", "CAPITAL MOVER", "Deploy $1m lifetime", 2_840_000, 1_000_000),
                new Milestone("active_10h", "TEN HOURS", "Run automation 10 active hours",
                        12 * 60 + 34, 10 * 60),
                new Milestone("active_100h", "CENTURY WATCH", "Run automation 100 active hours",
                        12 * 60 + 34, 100 * 60));

        String ledger = mode == Mode.PAPER ? "DEMO PAPER LEDGER" : "DEMO REAL LEDGER";
        return new PerformanceStatsSnapshot(mode, true, ledger,
                2_840_000, 2_594_000, 184_600, 246_000, 0, 912,
                19, 18, 1, 0, 15, 3, 0, 200.0, 58_400,
                16 * 60_000L, 2.7, 61.0, 31_400L,
                47 * 60_000L, (12 * 60 + 34) * 60_000L,
                history, items, milestones);
    }
}
