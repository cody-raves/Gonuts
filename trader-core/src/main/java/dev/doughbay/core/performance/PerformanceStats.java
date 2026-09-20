package dev.doughbay.core.performance;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Deterministic lifetime trade-performance summary for one account mode.
 *
 * <p>Money totals never infer a result from a target price. A completed sale
 * contributes only when it has a positive recorded sale price and a finite
 * realized profit. This keeps an incomplete or crash-recovered position from
 * turning into invented performance.</p>
 */
public record PerformanceStats(
        PerformanceMode mode,
        long currentSessionActiveMillis,
        long lifetimeAutomationActiveMillis,
        long capitalInvested,
        long settledCapital,
        long openCapital,
        long returnedInventoryCost,
        long unreconciledCost,
        long grossSales,
        long totalQuantitySold,
        double realizedProfit,
        double realizedRoiPercent,
        double averageRoiPercent,
        double medianRoiPercent,
        int purchasedTrades,
        int closedTrades,
        int openTrades,
        int completedSales,
        int returnedInventoryTrades,
        int unreconciledTrades,
        int wins,
        int losses,
        int breakEvenTrades,
        double winRatePercent,
        double averageProfit,
        double medianProfit,
        double averageHoldMinutes,
        double medianHoldMinutes,
        Optional<ItemPerformance> bestItemByProfit,
        Optional<ItemPerformance> mostSoldItem,
        Optional<ItemPerformance> mostTradedItem,
        Optional<Flip> largestFlip,
        int maxConsecutiveProfitableTrades,
        double bestRoiPercent,
        double fastestPositiveSaleMinutes,
        int distinctSoldItems,
        double currentCumulativeProfit,
        double peakCumulativeProfit,
        List<ItemPerformance> itemsByProfit,
        List<PerformancePoint> performanceSeries,
        int ignoredPositions,
        int incompleteSettlements
) {
    /** Durable automation runtime supplied by the storage/controller boundary. */
    public record RuntimeSummary(long currentSessionActiveMillis,
                                 long lifetimeActiveMillis,
                                 long sessionStartedAt,
                                 boolean sessionOpen,
                                 long recordedAt) {
        public static RuntimeSummary empty() {
            return new RuntimeSummary(0, 0, 0, false, 0);
        }

        private RuntimeSummary normalized() {
            long session = Math.max(0, currentSessionActiveMillis);
            return new RuntimeSummary(
                    session,
                    Math.max(session, lifetimeActiveMillis),
                    Math.max(0, sessionStartedAt),
                    sessionOpen,
                    Math.max(0, recordedAt));
        }
    }

    /** Observed cash plus inventory. Both values must be non-negative. */
    public record AccountValuePoint(long at, long balance, long inventoryValue) {
        public boolean isValid() {
            return at > 0 && balance >= 0 && inventoryValue >= 0;
        }
    }

    /**
     * One chart point. Equity is authoritative only when {@code equityObserved}
     * is true; cumulative profit is always derived from durable settled sales.
     */
    public record PerformancePoint(long at, long cumulativeCapitalInvested,
                                   double cumulativeProfit,
                                   long equity, boolean equityObserved) {
    }

    /** Per-item lifetime result, suitable for leaderboards and achievements. */
    public record ItemPerformance(String itemKey, int completedSales,
                                  long quantitySold, long capitalInvested,
                                  long grossSales, double realizedProfit,
                                  double roiPercent) {
    }

    /** Best individual completed flip. */
    public record Flip(long positionId, String itemKey, int quantity,
                       long purchasePrice, long salePrice, double profit,
                       double roiPercent, double holdMinutes, long closedAt) {
    }

    private record Settled(Position position, double profit, double holdMinutes) {
    }

    private static final Comparator<Position> POSITION_ORDER =
            Comparator.comparingLong(Position::closedAt)
                    .thenComparingLong(Position::positionId)
                    .thenComparing(Position::itemKey);

    /** Computes a report from persisted positions and optional account values. */
    public static PerformanceStats compute(PerformanceMode mode,
                                           Collection<Position> positions,
                                           Collection<AccountValuePoint> accountValues) {
        return compute(mode, positions, accountValues, RuntimeSummary.empty());
    }

    /** Computes a report including durable automation active time. */
    public static PerformanceStats compute(PerformanceMode mode,
                                           Collection<Position> positions,
                                           Collection<AccountValuePoint> accountValues,
                                           RuntimeSummary rawRuntime) {
        if (mode == null) throw new IllegalArgumentException("mode is required");
        RuntimeSummary runtime = rawRuntime == null
                ? RuntimeSummary.empty() : rawRuntime.normalized();

        List<Position> matching = new ArrayList<>();
        if (positions != null) {
            for (Position position : positions) {
                if (position != null && mode.matches(position.mode())) matching.add(position);
            }
        }
        matching.sort(Comparator.comparingLong(Position::positionId)
                .thenComparingLong(Position::purchasedAt)
                .thenComparing(position -> safeKey(position.itemKey())));

        long capital = 0;
        long openCapital = 0;
        long returnedInventoryCost = 0;
        long unreconciledCost = 0;
        int purchased = 0;
        int open = 0;
        int returnedInventory = 0;
        int unreconciled = 0;
        int ignored = 0;
        int incomplete = 0;
        List<Settled> settled = new ArrayList<>();
        List<Position> validPurchases = new ArrayList<>();

        for (Position position : matching) {
            if (!validPurchase(position)) {
                // Signals and misses are not trades and therefore are not data
                // errors. A record claiming a purchase with malformed values is.
                if (claimsPurchase(position)) ignored++;
                continue;
            }
            purchased++;
            validPurchases.add(position);
            capital = saturatedAdd(capital, position.purchasePrice());
            if (isCurrentOpen(position.status())) {
                open++;
                openCapital = saturatedAdd(openCapital, position.purchasePrice());
            } else if (position.status() == PositionStatus.EXPIRED) {
                returnedInventory++;
                returnedInventoryCost = saturatedAdd(
                        returnedInventoryCost, position.purchasePrice());
            }

            boolean soldStatus = completedStatus(mode, position.status());
            if (!soldStatus && !isCurrentOpen(position.status())
                    && position.status() != PositionStatus.EXPIRED) {
                unreconciled++;
                unreconciledCost = saturatedAdd(unreconciledCost, position.purchasePrice());
            }
            if (!soldStatus) continue;
            if (!validSettlement(position)) {
                incomplete++;
                unreconciled++;
                unreconciledCost = saturatedAdd(unreconciledCost, position.purchasePrice());
                continue;
            }
            long holdStartedAt = position.listedAt() > 0
                    && position.listedAt() >= position.purchasedAt()
                    && position.listedAt() <= position.closedAt()
                    ? position.listedAt()
                    : position.purchasedAt();
            settled.add(new Settled(position, position.realizedProfit(),
                    (position.closedAt() - holdStartedAt) / 60_000.0));
        }

        settled.sort(Comparator.comparing((Settled value) -> value.position(), POSITION_ORDER));
        long settledCapital = 0;
        long grossSales = 0;
        long quantitySold = 0;
        double realizedProfit = 0;
        int wins = 0;
        int losses = 0;
        int breakEven = 0;
        List<Double> profits = new ArrayList<>();
        List<Double> holds = new ArrayList<>();
        List<Double> rois = new ArrayList<>();
        Map<String, MutableItem> itemMap = new HashMap<>();
        Flip largest = null;
        int currentWinStreak = 0;
        int maxWinStreak = 0;
        double bestRoi = Double.NEGATIVE_INFINITY;
        double fastestPositive = Double.POSITIVE_INFINITY;
        double peakProfit = 0;
        double runningProfit = 0;

        for (Settled result : settled) {
            Position position = result.position();
            settledCapital = saturatedAdd(settledCapital, position.purchasePrice());
            grossSales = saturatedAdd(grossSales, position.salePrice());
            quantitySold = saturatedAdd(quantitySold, position.quantity());
            realizedProfit = finiteAdd(realizedProfit, result.profit());
            profits.add(result.profit());
            holds.add(result.holdMinutes());
            rois.add(percent(result.profit(), position.purchasePrice()));
            if (result.profit() > 0) wins++;
            else if (result.profit() < 0) losses++;
            else breakEven++;
            if (result.profit() > 0) {
                currentWinStreak++;
                maxWinStreak = Math.max(maxWinStreak, currentWinStreak);
                fastestPositive = Math.min(fastestPositive, result.holdMinutes());
            } else {
                currentWinStreak = 0;
            }
            double tradeRoi = percent(result.profit(), position.purchasePrice());
            bestRoi = Math.max(bestRoi, tradeRoi);
            runningProfit = finiteAdd(runningProfit, result.profit());
            peakProfit = Math.max(peakProfit, runningProfit);

            MutableItem item = itemMap.computeIfAbsent(position.itemKey(), MutableItem::new);
            item.add(position, result.profit());

            Flip candidate = new Flip(position.positionId(), position.itemKey(),
                    position.quantity(), position.purchasePrice(), position.salePrice(),
                    result.profit(), percent(result.profit(), position.purchasePrice()),
                    result.holdMinutes(), position.closedAt());
            if (largest == null || compareFlip(candidate, largest) < 0) largest = candidate;
        }

        List<ItemPerformance> itemResults = itemMap.values().stream()
                .map(MutableItem::freeze)
                .sorted(Comparator.comparingDouble(ItemPerformance::realizedProfit).reversed()
                        .thenComparing(Comparator.comparingInt(
                                ItemPerformance::completedSales).reversed())
                        .thenComparing(ItemPerformance::itemKey))
                .toList();
        Optional<ItemPerformance> bestItem = itemResults.stream().findFirst();
        Optional<ItemPerformance> mostSold = itemResults.stream()
                .sorted(Comparator.comparingLong(ItemPerformance::quantitySold).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                ItemPerformance::realizedProfit).reversed())
                        .thenComparing(ItemPerformance::itemKey))
                .findFirst();
        Optional<ItemPerformance> mostTraded = itemResults.stream()
                .sorted(Comparator.comparingInt(ItemPerformance::completedSales).reversed()
                        .thenComparing(Comparator.comparingLong(
                                ItemPerformance::quantitySold).reversed())
                        .thenComparing(Comparator.comparingDouble(
                                ItemPerformance::realizedProfit).reversed())
                        .thenComparing(ItemPerformance::itemKey))
                .findFirst();

        int outcomes = wins + losses + breakEven;
        return new PerformanceStats(
                mode,
                mode == PerformanceMode.REAL ? runtime.currentSessionActiveMillis() : 0,
                mode == PerformanceMode.REAL ? runtime.lifetimeActiveMillis() : 0,
                capital,
                settledCapital,
                openCapital,
                returnedInventoryCost,
                unreconciledCost,
                grossSales,
                quantitySold,
                finite(realizedProfit),
                percent(realizedProfit, settledCapital),
                average(rois),
                median(rois),
                purchased,
                settled.size(),
                open,
                settled.size(),
                returnedInventory,
                unreconciled,
                wins,
                losses,
                breakEven,
                outcomes == 0 ? 0 : wins * 100.0 / outcomes,
                average(profits),
                median(profits),
                average(holds),
                median(holds),
                bestItem,
                mostSold,
                mostTraded,
                Optional.ofNullable(largest),
                maxWinStreak,
                settled.isEmpty() ? 0 : bestRoi,
                Double.isInfinite(fastestPositive) ? 0 : fastestPositive,
                itemResults.size(),
                finite(realizedProfit),
                finite(peakProfit),
                List.copyOf(itemResults),
                buildSeries(validPurchases, settled, accountValues),
                ignored,
                incomplete);
    }

    public static PerformanceStats compute(PerformanceMode mode,
                                           Collection<Position> positions) {
        return compute(mode, positions, List.of());
    }

    /** Human-readable scope that prevents simulated and actual results mixing. */
    public String scopeLabel() {
        return mode == PerformanceMode.REAL
                ? "This local database"
                : "Simulated estimate";
    }

    private static List<PerformancePoint> buildSeries(
            List<Position> purchases, List<Settled> settled,
            Collection<AccountValuePoint> rawValues) {
        List<AccountValuePoint> values = new ArrayList<>();
        if (rawValues != null) {
            for (AccountValuePoint value : rawValues) {
                if (value != null && value.isValid()) values.add(value);
            }
        }
        values.sort(Comparator.comparingLong(AccountValuePoint::at)
                .thenComparingLong(AccountValuePoint::balance)
                .thenComparingLong(AccountValuePoint::inventoryValue));

        TreeSet<Long> times = new TreeSet<>();
        purchases.forEach(position -> times.add(position.purchasedAt()));
        settled.forEach(result -> times.add(result.position().closedAt()));
        values.forEach(value -> times.add(value.at()));
        if (times.isEmpty()) return List.of();

        List<PerformancePoint> points = new ArrayList<>(times.size());
        int settlementIndex = 0;
        int purchaseIndex = 0;
        int valueIndex = 0;
        long deployed = 0;
        double cumulative = 0;
        AccountValuePoint latestValue = null;
        purchases.sort(Comparator.comparingLong(Position::purchasedAt)
                .thenComparingLong(Position::positionId)
                .thenComparing(position -> safeKey(position.itemKey())));
        for (long time : times) {
            while (purchaseIndex < purchases.size()
                    && purchases.get(purchaseIndex).purchasedAt() <= time) {
                deployed = saturatedAdd(deployed,
                        purchases.get(purchaseIndex).purchasePrice());
                purchaseIndex++;
            }
            while (settlementIndex < settled.size()
                    && settled.get(settlementIndex).position().closedAt() <= time) {
                cumulative = finiteAdd(cumulative, settled.get(settlementIndex).profit());
                settlementIndex++;
            }
            while (valueIndex < values.size() && values.get(valueIndex).at() <= time) {
                latestValue = values.get(valueIndex++);
            }
            boolean observed = latestValue != null;
            long equity = observed
                    ? saturatedAdd(latestValue.balance(), latestValue.inventoryValue())
                    : 0;
            points.add(new PerformancePoint(time, deployed, finite(cumulative),
                    equity, observed));
        }
        return List.copyOf(points);
    }

    private static boolean claimsPurchase(Position position) {
        // SIGNAL/MISSED/pre-buy CANCELLED legitimately retain the quoted buy
        // price while purchased_at remains unset. That is not corrupt data and
        // must never inflate either deployed capital or the exclusion count.
        return position.purchasedAt() > 0
                || position.status() == PositionStatus.PURCHASED
                || position.status() == PositionStatus.LISTED
                || position.status() == PositionStatus.SOLD
                || position.status() == PositionStatus.SIMULATED_PURCHASED
                || position.status() == PositionStatus.SIMULATED_LISTED
                || position.status() == PositionStatus.LIKELY_SOLD;
    }

    private static boolean validPurchase(Position position) {
        return position.status() != null
                && !safeKey(position.itemKey()).isEmpty()
                && position.quantity() > 0
                && position.purchasePrice() > 0
                && position.purchasedAt() > 0;
    }

    private static boolean validSettlement(Position position) {
        return position.closedAt() >= position.purchasedAt()
                && position.closedAt() > 0
                && position.salePrice() > 0
                && Double.isFinite(position.realizedProfit());
    }

    private static boolean completedStatus(PerformanceMode mode, PositionStatus status) {
        return mode == PerformanceMode.REAL
                ? status == PositionStatus.SOLD
                : status == PositionStatus.LIKELY_SOLD;
    }

    private static boolean isCurrentOpen(PositionStatus status) {
        return status == PositionStatus.PURCHASED
                || status == PositionStatus.LISTED
                || status == PositionStatus.SIMULATED_PURCHASED
                || status == PositionStatus.SIMULATED_LISTED;
    }

    private static String safeKey(String itemKey) {
        return itemKey == null ? "" : itemKey.strip();
    }

    private static double average(List<Double> values) {
        if (values.isEmpty()) return 0;
        double sum = 0;
        for (double value : values) sum = finiteAdd(sum, value);
        return finite(sum / values.size());
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) return sorted.get(middle);
        return finite(sorted.get(middle - 1) / 2.0 + sorted.get(middle) / 2.0);
    }

    private static double percent(double numerator, long denominator) {
        if (denominator <= 0 || !Double.isFinite(numerator)) return 0;
        return finite(numerator / denominator * 100.0);
    }

    private static double finiteAdd(double left, double right) {
        double result = left + right;
        if (Double.isFinite(result)) return result;
        return result > 0 ? Double.MAX_VALUE : -Double.MAX_VALUE;
    }

    private static double finite(double value) {
        if (Double.isFinite(value)) return value;
        return value > 0 ? Double.MAX_VALUE : value < 0 ? -Double.MAX_VALUE : 0;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /** Negative means {@code left} should rank before {@code right}. */
    private static int compareFlip(Flip left, Flip right) {
        int byProfit = Double.compare(right.profit(), left.profit());
        if (byProfit != 0) return byProfit;
        int byClosed = Long.compare(left.closedAt(), right.closedAt());
        if (byClosed != 0) return byClosed;
        return Long.compare(left.positionId(), right.positionId());
    }

    private static final class MutableItem {
        private final String key;
        private int completedSales;
        private long quantity;
        private long capital;
        private long grossSales;
        private double profit;

        private MutableItem(String key) {
            this.key = key;
        }

        private void add(Position position, double realized) {
            completedSales++;
            quantity = saturatedAdd(quantity, position.quantity());
            capital = saturatedAdd(capital, position.purchasePrice());
            grossSales = saturatedAdd(grossSales, position.salePrice());
            profit = finiteAdd(profit, realized);
        }

        private ItemPerformance freeze() {
            return new ItemPerformance(key, completedSales, quantity, capital,
                    grossSales, finite(profit), percent(profit, capital));
        }
    }
}
