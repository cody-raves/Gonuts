package dev.doughbay.cli;

import dev.doughbay.core.analysis.RiskAdvisor;
import dev.doughbay.core.analysis.RiskConfig;
import dev.doughbay.core.performance.PerformanceMode;
import dev.doughbay.core.performance.PerformanceStats;
import dev.doughbay.storage.PositionRepository;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Prints how trading actually went, from the recorded positions.
 *
 * <p>The engine already computes {@link PerformanceStats}; until now nothing
 * exposed it outside the game, so "how did it do overnight?" had no answer
 * short of reading the database by hand.
 *
 * <p>Reports only what was settled. A position without a recorded sale price
 * contributes nothing to the money totals and is counted separately as
 * unreconciled — an unfinished trade must never be rounded up into apparent
 * profit.
 */
final class PerformanceReport {

    /** Completed sales before the advisor will suggest a threshold change. */
    private static final int ADVISOR_MIN_TRADES = 30;

    private final PositionRepository positions;
    private final RiskConfig risk;

    PerformanceReport(PositionRepository positions, RiskConfig risk) {
        this.positions = positions;
        this.risk = risk;
    }

    void print(PerformanceMode mode) throws Exception {
        PerformanceStats s = positions.performance(mode);

        System.out.printf(Locale.ROOT, "%n=== DOUGHBAY %s PERFORMANCE ===%n%n", mode);

        if (s.purchasedTrades() == 0) {
            System.out.println("No positions recorded for this mode yet.");
            System.out.println("If you expected trades, check that execution was enabled and that");
            System.out.println("the database path matches the one the mod writes to.");
            return;
        }

        section("RESULT");
        money("Realized profit", s.realizedProfit());
        pct("Return on settled capital", s.realizedRoiPercent());
        money("Capital invested", s.capitalInvested());
        money("Gross sales", s.grossSales());
        money("Still open (at cost)", s.openCapital());

        section("TRADES");
        count("Purchased", s.purchasedTrades());
        count("Completed sales", s.completedSales());
        count("Still open", s.openTrades());
        count("Wins / losses / flat", s.wins(), s.losses(), s.breakEvenTrades());
        pct("Win rate", s.winRatePercent());
        pct("Median ROI", s.medianRoiPercent());
        money("Median profit per trade", s.medianProfit());

        section("SPEED");
        duration("Median hold", s.medianHoldMinutes());
        duration("Average hold", s.averageHoldMinutes());
        if (s.fastestPositiveSaleMinutes() > 0) {
            duration("Fastest profitable sale", s.fastestPositiveSaleMinutes());
        }
        duration("Automation active (session)",
                Duration.ofMillis(s.currentSessionActiveMillis()).toMinutes());
        duration("Automation active (lifetime)",
                Duration.ofMillis(s.lifetimeAutomationActiveMillis()).toMinutes());
        if (s.lifetimeAutomationActiveMillis() > 0) {
            double hours = s.lifetimeAutomationActiveMillis() / 3_600_000.0;
            money("Profit per active hour", s.realizedProfit() / Math.max(0.01, hours));
        }

        section("BEST AND WORST");
        s.largestFlip().ifPresent(f -> System.out.printf(Locale.ROOT,
                "  %-28s %s x%d  bought %,d  sold %,d  profit %,.0f (%.0f%%)%n",
                "Largest flip", shortName(f.itemKey()), f.quantity(),
                f.purchasePrice(), f.salePrice(), f.profit(), f.roiPercent()));
        s.bestItemByProfit().ifPresent(i -> System.out.printf(Locale.ROOT,
                "  %-28s %s  %,.0f over %d sales%n",
                "Most profitable market", shortName(i.itemKey()),
                i.realizedProfit(), i.completedSales()));
        s.mostSoldItem().ifPresent(i -> System.out.printf(Locale.ROOT,
                "  %-28s %s  %d units%n",
                "Highest volume", shortName(i.itemKey()), i.quantitySold()));
        pct("Best single ROI", s.bestRoiPercent());
        count("Best winning streak", s.maxConsecutiveProfitableTrades());
        count("Distinct markets sold", s.distinctSoldItems());

        List<PerformanceStats.ItemPerformance> byProfit = s.itemsByProfit();
        if (!byProfit.isEmpty()) {
            section("BY MARKET");
            System.out.printf(Locale.ROOT, "  %-26s %7s %12s %12s %8s%n",
                    "MARKET", "SALES", "INVESTED", "PROFIT", "ROI");
            for (PerformanceStats.ItemPerformance i : byProfit.subList(0, Math.min(10, byProfit.size()))) {
                System.out.printf(Locale.ROOT, "  %-26s %7d %,12d %,12.0f %7.0f%%%n",
                        shortName(i.itemKey()), i.completedSales(),
                        i.capitalInvested(), i.realizedProfit(), i.roiPercent());
            }
        }

        // Anything the totals deliberately exclude, stated rather than hidden.
        if (s.unreconciledTrades() > 0 || s.returnedInventoryTrades() > 0
                || s.ignoredPositions() > 0 || s.incompleteSettlements() > 0) {
            section("NOT COUNTED AS PROFIT");
            if (s.unreconciledTrades() > 0) {
                System.out.printf(Locale.ROOT, "  %-28s %d (%,d at cost)%n",
                        "Unreconciled", s.unreconciledTrades(), s.unreconciledCost());
            }
            if (s.returnedInventoryTrades() > 0) {
                System.out.printf(Locale.ROOT, "  %-28s %d (%,d at cost)%n",
                        "Returned to inventory", s.returnedInventoryTrades(), s.returnedInventoryCost());
            }
            count("Incomplete settlements", s.incompleteSettlements());
            count("Ignored positions", s.ignoredPositions());
            System.out.println("  These are excluded from the money totals on purpose:");
            System.out.println("  an unfinished trade is not profit until it settles.");
        }

        // Feed the outcomes back at the risk thresholds — suggestions only, so
        // the numbers that decide what real money buys stay the operator's.
        if (risk != null) {
            List<RiskAdvisor.Suggestion> suggestions =
                    RiskAdvisor.review(s, risk, ADVISOR_MIN_TRADES);
            if (!suggestions.isEmpty()) {
                section("TUNING SUGGESTIONS");
                for (RiskAdvisor.Suggestion suggestion : suggestions) {
                    System.out.printf(Locale.ROOT, "  %s%n", suggestion.describe());
                }
                System.out.println("  Suggestions only, from settled outcomes; apply by hand in the config.");
            }
        }

        section("EQUITY");
        money("Cumulative profit now", s.currentCumulativeProfit());
        money("Peak cumulative profit", s.peakCumulativeProfit());
        double drawdown = s.peakCumulativeProfit() - s.currentCumulativeProfit();
        if (drawdown > 0) {
            money("Below peak by", drawdown);
        }
        System.out.println();
    }

    // ------------------------------------------------------------------ output

    private static void section(String title) {
        System.out.printf(Locale.ROOT, "%n%s%n", title);
    }

    private static void money(String label, double value) {
        System.out.printf(Locale.ROOT, "  %-28s %,.0f%n", label, value);
    }

    private static void pct(String label, double value) {
        System.out.printf(Locale.ROOT, "  %-28s %.1f%%%n", label, value);
    }

    private static void count(String label, int value) {
        System.out.printf(Locale.ROOT, "  %-28s %d%n", label, value);
    }

    private static void count(String label, int a, int b, int c) {
        System.out.printf(Locale.ROOT, "  %-28s %d / %d / %d%n", label, a, b, c);
    }

    private static void duration(String label, double minutes) {
        String text = minutes >= 90
                ? String.format(Locale.ROOT, "%.1f h", minutes / 60)
                : String.format(Locale.ROOT, "%.0f min", minutes);
        System.out.printf(Locale.ROOT, "  %-28s %s%n", label, text);
    }

    private static String shortName(String itemKey) {
        String bare = itemKey.contains(":") ? itemKey.substring(itemKey.indexOf(':') + 1) : itemKey;
        bare = bare.replace('_', ' ');
        return bare.length() > 26 ? bare.substring(0, 26) : bare;
    }
}
