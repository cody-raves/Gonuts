package dev.doughbay.paper;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.stats.RobustStats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Strategy performance summary for the paper account. */
public record PaperMetrics(
        long startingBankroll,
        long endingBalance,
        long unrealizedInventoryValue,
        double realizedProfit,
        double maxDrawdown,
        int totalSignals,
        int likelySold,
        int expired,
        int missedBeforePurchase,
        int openPositions,
        double winRatePercent,
        double medianRoiPercent,
        double medianHoldMinutes,
        double profitPerHour
) {
    public static PaperMetrics compute(long startingBankroll, long balance, long inventoryValue,
                                       Collection<Position> positions,
                                       List<PaperTradingEngine.BalancePoint> history,
                                       long now) {
        int signals = positions.size();
        int sold = 0, expired = 0, missed = 0, open = 0, wins = 0, closedTrades = 0;
        double realized = 0;
        List<Double> rois = new ArrayList<>();
        List<Double> holds = new ArrayList<>();
        long firstActivity = Long.MAX_VALUE;

        for (Position p : positions) {
            switch (p.status()) {
                case LIKELY_SOLD -> {
                    sold++;
                    closedTrades++;
                    realized += p.realizedProfit();
                    if (p.realizedProfit() > 0) wins++;
                    rois.add(p.realizedProfit() / p.purchasePrice() * 100.0);
                    if (p.closedAt() > p.listedAt() && p.listedAt() > 0) {
                        holds.add((p.closedAt() - p.listedAt()) / 60000.0);
                    }
                }
                case EXPIRED -> {
                    expired++;
                    closedTrades++;
                }
                case MISSED_BEFORE_PURCHASE, CANCELLED -> missed++;
                default -> {
                    if (!p.status().isClosed()) open++;
                }
            }
            if (p.purchasedAt() > 0) firstActivity = Math.min(firstActivity, p.purchasedAt());
        }

        double maxDrawdown = 0;
        long peak = Long.MIN_VALUE;
        for (PaperTradingEngine.BalancePoint point : history) {
            long equity = point.balance() + point.inventoryValue();
            peak = Math.max(peak, equity);
            if (peak > 0) {
                maxDrawdown = Math.max(maxDrawdown, (double) (peak - equity) / peak);
            }
        }

        double hoursActive = firstActivity == Long.MAX_VALUE
                ? 0 : Math.max(1.0 / 60, (now - firstActivity) / 3_600_000.0);

        return new PaperMetrics(
                startingBankroll, balance, inventoryValue, realized, maxDrawdown,
                signals, sold, expired, missed, open,
                closedTrades == 0 ? 0 : (double) wins / closedTrades * 100.0,
                rois.isEmpty() ? 0 : RobustStats.median(rois),
                holds.isEmpty() ? 0 : RobustStats.median(holds),
                hoursActive == 0 ? 0 : realized / hoursActive);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Starting bankroll:     %,d%n", startingBankroll));
        sb.append(String.format(Locale.ROOT, "Cash balance:          %,d%n", endingBalance));
        sb.append(String.format(Locale.ROOT, "Unrealized inventory:  %,d%n", unrealizedInventoryValue));
        sb.append(String.format(Locale.ROOT, "Realized profit:       %,.0f%n", realizedProfit));
        sb.append(String.format(Locale.ROOT, "Max drawdown:          %.1f%%%n", maxDrawdown * 100));
        sb.append(String.format(Locale.ROOT, "Signals:               %d%n", totalSignals));
        sb.append(String.format(Locale.ROOT, "Likely sold:           %d%n", likelySold));
        sb.append(String.format(Locale.ROOT, "Expired unsold:        %d%n", expired));
        sb.append(String.format(Locale.ROOT, "Missed before buy:     %d%n", missedBeforePurchase));
        sb.append(String.format(Locale.ROOT, "Open positions:        %d%n", openPositions));
        sb.append(String.format(Locale.ROOT, "Win rate:              %.1f%%%n", winRatePercent));
        sb.append(String.format(Locale.ROOT, "Median ROI:            %.1f%%%n", medianRoiPercent));
        sb.append(String.format(Locale.ROOT, "Median hold:           %.1f min%n", medianHoldMinutes));
        sb.append(String.format(Locale.ROOT, "Profit/hour:           %,.0f%n", profitPerHour));
        return sb.toString();
    }
}
