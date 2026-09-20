package dev.doughbay.core.analysis;

import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Sale;
import dev.doughbay.core.model.StackBucket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Answers the core question: given this exact listing, the market's history,
 * and the active competition, is buying now and reselling conservatively likely
 * to produce worthwhile profit?
 *
 * <p>Deterministic: same inputs, same answer. Every rejection carries a reason
 * so filters can be audited and tuned.
 */
public final class OpportunityDetector {

    private final FeeConfig fees;
    private final RiskConfig risk;
    private final ManipulationDetector manipulationDetector = new ManipulationDetector();

    public OpportunityDetector(FeeConfig fees, RiskConfig risk) {
        this.fees = fees;
        this.risk = risk;
    }

    public record Bankroll(long totalBankroll, long availableBalance, int openPositions) {
    }

    public record Evaluation(Optional<Opportunity> opportunity, List<String> rejections) {
        public boolean accepted() {
            return opportunity.isPresent();
        }
    }

    /**
     * @param candidate     the listing under evaluation
     * @param stats         market stats for (candidate.itemKey, candidate.bucket),
     *                      computed only from data available at decision time
     * @param keptSales     the sales behind {@code stats} (for manipulation checks)
     * @param activeListings other currently-active listings for the same market,
     *                      candidate excluded
     */
    public Evaluation evaluate(Listing candidate,
                               MarketStats stats,
                               List<Sale> keptSales,
                               List<Listing> activeListings,
                               Bankroll bankroll) {
        List<String> rejections = new ArrayList<>();

        if (!candidate.isValid()) {
            return reject("Malformed listing");
        }
        if (!stats.hasPrices()) {
            return reject("No usable completed-sale history");
        }

        // --- Data-quality and market-state filters -------------------------
        if (stats.sampleCount() < risk.minimumSamples()) {
            rejections.add("Only " + stats.sampleCount() + " sales (need "
                    + risk.minimumSamples() + ")");
        }
        long newestAge = stats.calculatedAt() - stats.newestSaleAt();
        if (newestAge > risk.maximumNewestSaleAgeMillis()) {
            rejections.add("Last sale " + newestAge / 60000 + "m ago");
        }
        if (stats.robustVolatility() > risk.maximumVolatility()) {
            rejections.add(String.format(Locale.ROOT, "Volatility %.0f%% (max %.0f%%)",
                    stats.robustVolatility() * 100, risk.maximumVolatility() * 100));
        }
        if (risk.skipFallingMarkets() && stats.trend() < -0.10) {
            rejections.add(String.format(Locale.ROOT, "Falling %.0f%%", -stats.trend() * 100));
        }

        // --- Conservative resale price -------------------------------------
        double quickSale = stackTarget(stats.quickSalePrice(), stats, candidate);
        Optional<Listing> nextComparable = activeListings.stream()
                .filter(l -> isComparableActiveAsk(l, candidate))
                .min(Comparator.comparingDouble(l -> comparableStackPrice(l, candidate)));

        double recommended = quickSale;
        if (nextComparable.isPresent()) {
            double undercutTarget = comparableStackPrice(nextComparable.get(), candidate) - risk.undercutAmount();
            recommended = Math.min(quickSale, undercutTarget);
        }
        long recommendedSell = (long) Math.floor(recommended);
        if (recommendedSell <= 0) {
            return reject("No viable resale price");
        }

        // --- Profit after fees ---------------------------------------------
        double netSale = fees.netSale(recommendedSell);
        double expectedProfit = netSale - candidate.totalPrice();
        double roiPercent = expectedProfit / candidate.totalPrice() * 100.0;
        if (expectedProfit < risk.minimumProfit()) {
            rejections.add(String.format(Locale.ROOT, "Profit %.0f (need %d)",
                    expectedProfit, risk.minimumProfit()));
        }
        if (roiPercent < risk.minimumRoiPercent()) {
            rejections.add(String.format(Locale.ROOT, "ROI %.1f%% (need %.0f%%)",
                    roiPercent, risk.minimumRoiPercent()));
        }

        // --- Liquidity and hold time ---------------------------------------
        long listingsAhead = activeListings.stream()
                .filter(l -> comparableStackPrice(l, candidate) <= recommendedSell)
                .count();
        double holdHours = stats.salesPerHour() > 0
                ? (listingsAhead + 1) / stats.salesPerHour()
                : Double.POSITIVE_INFINITY;
        if (holdHours > risk.maximumExpectedHoldHours()) {
            rejections.add(String.format(Locale.ROOT, "Hold %.1fh (max %.0fh)",
                    holdHours, risk.maximumExpectedHoldHours()));
        }

        // --- Bankroll rules ------------------------------------------------
        long maxPosition = (long) (bankroll.totalBankroll() * risk.maximumPositionPercent() / 100.0);
        long reserve = (long) (bankroll.totalBankroll() * risk.bankrollReservePercent() / 100.0);
        if (candidate.totalPrice() > maxPosition) {
            rejections.add("Too large for allocation");
        }
        if (candidate.totalPrice() > bankroll.availableBalance() - reserve) {
            rejections.add("Not enough capital");
        }
        if (bankroll.openPositions() >= risk.maximumConcurrentPositions()) {
            rejections.add("All position slots in use");
        }

        // --- Manipulation / confidence -------------------------------------
        // Confidence is historical: it is derived from completed sales only.
        // Active listings are supply-side context and may cap the resale target,
        // but an ask (or lack of asks) is not evidence that an item actually sold.
        ManipulationDetector.Assessment manipulation =
                manipulationDetector.assess(stats, keptSales);
        double confidence = stats.confidence() * manipulation.penalty();
        if (confidence < risk.minimumConfidence()) {
            rejections.add(String.format(Locale.ROOT, "Confidence %.0f%% (need %.0f%%)",
                    confidence * 100, risk.minimumConfidence() * 100));
        }

        if (!rejections.isEmpty()) {
            return new Evaluation(Optional.empty(), rejections);
        }

        // --- Score and explanation -----------------------------------------
        double saleProbability = saleProbability(recommendedSell, stats, candidate);
        double liquidityFactor = Math.min(1.0, stats.salesPerHour() / 6.0);
        double effectiveHold = Math.max(0.05, holdHours);
        double score = expectedProfit * saleProbability * confidence * liquidityFactor
                / candidate.totalPrice() / effectiveHold * manipulation.penalty();

        double fairValue = stackTarget(stats.weightedMedian(), stats, candidate);
        List<String> reasons = new ArrayList<>();
        reasons.add(String.format(Locale.ROOT, "%.0f%% below conservative value",
                (1.0 - candidate.totalPrice() / quickSale) * 100));
        reasons.add(stats.sampleCount() + " comparable sales in window ("
                + String.format(Locale.ROOT, "%.1f", stats.salesPerHour()) + "/hour recently)");
        reasons.add(String.format(Locale.ROOT, "Volatility %.1f%%, trend %+.1f%%",
                stats.robustVolatility() * 100, stats.trend() * 100));
        reasons.add(String.format(Locale.ROOT, "Fair value %.0f, resale target %d (%s)",
                fairValue, recommendedSell,
                nextComparable.isPresent() && recommended < quickSale
                        ? "undercutting next active listing" : "historical quick-sale value"));
        reasons.add(String.format(Locale.ROOT, "Estimated hold %.0f min, expected net profit %.0f",
                holdHours * 60, expectedProfit));
        reasons.addAll(manipulation.warnings());

        Opportunity opportunity = new Opportunity(
                candidate, stats,
                candidate.totalPrice(), recommendedSell,
                expectedProfit, roiPercent, holdHours,
                saleProbability, confidence, score, reasons);
        return new Evaluation(Optional.of(opportunity), List.of());
    }

    /**
     * Converts a stats-band price to the candidate's stack. For exact buckets
     * stats are already per-stack; for OTHER they are per-unit.
     */
    private static double stackTarget(double bandPrice, MarketStats stats, Listing candidate) {
        return stats.bucket() == StackBucket.OTHER ? bandPrice * candidate.itemCount() : bandPrice;
    }

    /** A competitor's price expressed on the candidate's stack size. */
    private static double comparableStackPrice(Listing competitor, Listing candidate) {
        if (competitor.itemCount() == candidate.itemCount()) {
            return competitor.totalPrice();
        }
        return competitor.unitPrice() * candidate.itemCount();
    }

    private static boolean isComparableActiveAsk(Listing listing, Listing candidate) {
        if (listing == null || !listing.isValid()) return false;
        if (!listing.itemKey().equals(candidate.itemKey())) return false;
        if (listing.bucket() != candidate.bucket()) return false;
        if (listing == candidate) return false;
        return candidate.listingKey() == null
                || !candidate.listingKey().equals(listing.listingKey());
    }

    /**
     * Heuristic probability the resale fills within its listing lifetime:
     * selling below the quick-sale band in a liquid market is very likely,
     * selling above fair value in a slow market is not. Calibrated later
     * against paper-trading outcomes.
     */
    private static double saleProbability(long recommendedSell, MarketStats stats, Listing candidate) {
        double quick = stackTarget(stats.quickSalePrice(), stats, candidate);
        double fair = stackTarget(stats.weightedMedian(), stats, candidate);
        double priceFactor;
        if (recommendedSell <= quick) {
            priceFactor = 0.9;
        } else if (recommendedSell <= fair) {
            priceFactor = 0.75;
        } else {
            priceFactor = 0.55;
        }
        double liquidityFactor = Math.min(1.0, 0.5 + stats.salesPerHour() / 12.0);
        return Math.max(0.05, Math.min(0.98, priceFactor * liquidityFactor));
    }

    private static Evaluation reject(String reason) {
        return new Evaluation(Optional.empty(), List.of(reason));
    }
}
