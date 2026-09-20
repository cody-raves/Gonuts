package dev.doughbay.core.analysis;

import dev.doughbay.core.performance.PerformanceStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads how trading actually went and suggests how the risk thresholds might
 * move, so the placeholders in {@link RiskConfig} can be tuned from evidence
 * instead of intuition.
 *
 * <p>{@code RiskConfig}'s own documentation says its defaults are temporary and
 * meant to be tuned through trading. Nothing closed that loop: the ledger knew
 * the win rate, the realized ROI and the true hold times, and the thresholds
 * that shape every trade were still whatever they were first set to.
 *
 * <p>This only ever <em>suggests</em>, and deliberately so. Thresholds decide
 * what real money buys, and a number nudged automatically from a good week is a
 * number that will be nudged automatically from a bad one; the operator stays
 * in the loop. Each suggestion names the field, the current value, a proposed
 * value and the outcome that motivates it, and none fires until enough trades
 * have settled to rule out a lucky or unlucky run. When nothing has clearly
 * earned a change, it says nothing — silence here means "the thresholds look
 * about right", not "no opinion".
 *
 * <p>Pure and side-effect free.
 */
public final class RiskAdvisor {

    /**
     * @param field     the {@link RiskConfig} field this concerns
     * @param current   its current value
     * @param suggested the value the evidence points to
     * @param why       the outcome that motivates the change
     */
    public record Suggestion(String field, double current, double suggested, String why) {
        /** One line for a report or the log. */
        public String describe() {
            return String.format(Locale.ROOT, "%s: %s -> %s (%s)",
                    field, trim(current), trim(suggested), why);
        }

        private static String trim(double v) {
            return v == Math.rint(v)
                    ? String.format(Locale.ROOT, "%,d", (long) v)
                    : String.format(Locale.ROOT, "%.2f", v);
        }
    }

    private RiskAdvisor() {
    }

    /**
     * @param stats     settled trading outcomes
     * @param risk      the thresholds in force
     * @param minTrades completed sales required before any suggestion is made
     * @return zero or more suggested threshold changes, most worth acting on first
     */
    public static List<Suggestion> review(PerformanceStats stats, RiskConfig risk, int minTrades) {
        if (stats == null) return new ArrayList<>();
        return review(stats.completedSales(), stats.winRatePercent(), stats.medianRoiPercent(),
                stats.wins(), stats.losses(), stats.medianHoldMinutes(), risk, minTrades);
    }

    /**
     * The judgement, over the raw outcome figures, so it can be reasoned about
     * and tested without assembling a whole {@link PerformanceStats}.
     */
    public static List<Suggestion> review(int completed, double winRate, double medianRoi,
                                          int wins, int losses, double medianHoldMinutes,
                                          RiskConfig risk, int minTrades) {
        List<Suggestion> out = new ArrayList<>();
        if (risk == null) return out;
        if (completed < Math.max(1, minTrades)) return out;

        double minRoi = risk.minimumRoiPercent();

        // Too strict: strong win rate with ROI well clear of the floor means the
        // floor is turning away trades that would have paid. Loosen it toward
        // half the achieved median, keeping a sane floor.
        if (winRate >= 70.0 && medianRoi >= 2.0 * minRoi && minRoi > 6.0) {
            double suggested = Math.max(6.0, minRoi - 4.0);
            if (suggested < minRoi) {
                out.add(new Suggestion("minimumRoiPercent", minRoi, suggested,
                        String.format(Locale.ROOT,
                                "win rate %.0f%% at median ROI %.0f%% over %d sales; the %.0f%% floor may be turning away good trades",
                                winRate, medianRoi, completed, minRoi)));
            }
        }

        // Missing too often: raise the confidence floor to be pickier.
        if (winRate < 45.0 && risk.minimumConfidence() < 0.9) {
            double suggested = Math.min(0.9, risk.minimumConfidence() + 0.05);
            out.add(new Suggestion("minimumConfidence", risk.minimumConfidence(), suggested,
                    String.format(Locale.ROOT,
                            "win rate only %.0f%% over %d sales; a higher confidence floor should cut the misses",
                            winRate, completed)));
        }

        // Margins undershooting: realized ROI below target and losers ahead of
        // winners means demand more margin per trade.
        if (medianRoi < minRoi && losses > wins) {
            double suggested = minRoi + 4.0;
            out.add(new Suggestion("minimumRoiPercent", minRoi, suggested,
                    String.format(Locale.ROOT,
                            "median realized ROI %.0f%% is below the %.0f%% target and losers outnumber winners; demand more margin",
                            medianRoi, minRoi)));
        }

        // Hold estimate optimistic: trades take far longer than assumed, which
        // over-ranks markets that only look fast.
        double medianHoldHours = medianHoldMinutes / 60.0;
        double holdCap = risk.maximumExpectedHoldHours();
        if (holdCap > 0 && medianHoldHours > holdCap * 1.5) {
            out.add(new Suggestion("maximumExpectedHoldHours", holdCap, Math.ceil(medianHoldHours),
                    String.format(Locale.ROOT,
                            "trades actually take about %.1f h against a %.1f h assumption; the hold estimate is optimistic",
                            medianHoldHours, holdCap)));
        }

        return out;
    }
}
