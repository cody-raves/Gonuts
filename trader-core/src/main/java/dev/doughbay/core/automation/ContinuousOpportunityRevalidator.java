package dev.doughbay.core.automation;

import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.analysis.RiskConfig;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.StackBucket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure fail-closed revaluation at the live-execution boundary.
 *
 * <p>The initial opportunity is only a ceiling. A later completed-sale market
 * and a complete targeted active book may lower its resale target, confidence,
 * and score, but can never increase them. This prevents a stale opportunity
 * DTO from authorizing a purchase or listing after the market moved.
 */
public final class ContinuousOpportunityRevalidator {
    /**
     * The risk floor applied on top of the session policy. Configured, not
     * fixed: a live session ran the detector on the operator's thresholds and
     * this check on the library defaults, so every candidate the operator's
     * rules allowed was rejected here on stricter rules nobody had set.
     */
    private final RiskConfig baseRisk;

    public ContinuousOpportunityRevalidator() {
        this(RiskConfig.defaults());
    }

    public ContinuousOpportunityRevalidator(RiskConfig baseRisk) {
        this.baseRisk = baseRisk == null ? RiskConfig.defaults() : baseRisk;
    }

    public Result revalidate(Opportunity original,
                             MarketStats latestStats,
                             List<Listing> completeActiveBook,
                             ContinuousAutomationPolicy policy,
                             FeeConfig fees,
                             long nowMillis,
                             long statsMustBeAfterMillis,
                             boolean requireCandidateActive) {
        if (original == null || original.listing() == null || original.stats() == null
                || latestStats == null || policy == null || !validFees(fees)) {
            return Result.rejected(
                    "Missing opportunity, statistics, policy, or valid confirmed fees");
        }
        Listing originalListing = original.listing();
        List<Listing> activeBook = completeActiveBook == null
                ? List.of() : List.copyOf(completeActiveBook);

        if (!originalListing.isValid()
                || !originalListing.itemKey().equals(latestStats.itemKey())
                || originalListing.bucket() != latestStats.bucket()) {
            return Result.rejected("Latest completed-sale statistics do not match the exact market");
        }
        if (latestStats.calculatedAt() <= statsMustBeAfterMillis
                || latestStats.calculatedAt() <= 0
                || latestStats.calculatedAt() > nowMillis
                || nowMillis - latestStats.calculatedAt() > policy.maximumSnapshotAgeMillis()) {
            return Result.rejected("Latest completed-sale statistics are not new and fresh enough");
        }
        if (!latestStats.hasPrices()
                || !finitePositive(latestStats.quickSalePrice())
                || !finitePositive(latestStats.weightedMedian())) {
            return Result.rejected("Latest completed-sale valuation is unusable");
        }
        if (latestStats.sampleCount() < baseRisk.minimumSamples()) {
            return Result.rejected("Latest completed-sale sample count is below the base risk gate");
        }
        if (latestStats.newestSaleAt() <= 0
                || latestStats.newestSaleAt() > nowMillis
                || nowMillis - latestStats.newestSaleAt()
                > baseRisk.maximumNewestSaleAgeMillis()) {
            return Result.rejected("Latest completed sale is missing, future-dated, or stale");
        }
        if (!Double.isFinite(latestStats.robustVolatility())
                || latestStats.robustVolatility() < 0
                || latestStats.robustVolatility() > baseRisk.maximumVolatility()) {
            return Result.rejected("Latest robust volatility exceeds the base risk gate");
        }
        if (!Double.isFinite(latestStats.trend()) || latestStats.trend() < 0) {
            return Result.rejected("Latest completed-sale market trend is falling or unknown");
        }
        if (!Double.isFinite(latestStats.confidence())
                || latestStats.confidence() < Math.max(
                policy.minimumConfidence(), baseRisk.minimumConfidence())
                || latestStats.confidence() > 1) {
            return Result.rejected("Latest completed-sale confidence is below policy");
        }

        Listing reboundCandidate = originalListing;
        if (requireCandidateActive) {
            List<Listing> rebound = activeBook.stream()
                    .filter(listing -> sameListingIdentity(originalListing, listing))
                    .toList();
            if (rebound.size() != 1) {
                return Result.rejected(rebound.isEmpty()
                        ? "Selected listing disappeared before purchase"
                        : "Selected listing identity is duplicated or ambiguous");
            }
            reboundCandidate = rebound.getFirst();
        }
        final Listing candidate = reboundCandidate;

        long quickCap = stackTarget(latestStats.quickSalePrice(), latestStats, candidate);
        if (quickCap <= 0) return Result.rejected("Completed-sale quick band is invalid");

        Optional<Long> nextAsk = activeBook.stream()
                .filter(listing -> comparable(listing, candidate))
                .map(listing -> comparableStackPrice(listing, candidate))
                .filter(price -> price > 1)
                .min(Comparator.naturalOrder());
        long target = Math.min(original.recommendedSellPrice(), quickCap);
        if (nextAsk.isPresent()) target = Math.min(target, nextAsk.orElseThrow() - 1);
        if (target <= candidate.totalPrice()) {
            return Result.rejected("Fresh completed sales or active competition removed the margin");
        }

        double originalGross = (double) original.recommendedSellPrice() - original.buyPrice();
        if (!Double.isFinite(original.expectedNetProfit())
                || original.expectedNetProfit() > originalGross + 1.0e-9) {
            return Result.rejected("Original opportunity profit is internally inconsistent");
        }
        double expectedNetProfit = Math.min(
                original.expectedNetProfit(), fees.netSale(target) - candidate.totalPrice());
        double roiPercent = expectedNetProfit / candidate.totalPrice() * 100.0;
        if (!Double.isFinite(expectedNetProfit)
                || expectedNetProfit < Math.max(
                policy.minimumProfit(), baseRisk.minimumProfit())) {
            return Result.rejected("Fresh expected profit is below policy");
        }
        if (!Double.isFinite(roiPercent) || roiPercent < Math.max(
                policy.minimumRoiPercent(), baseRisk.minimumRoiPercent())) {
            return Result.rejected("Fresh expected ROI is below policy");
        }

        final long finalTarget = target;
        long listingsAhead = activeBook.stream()
                .filter(listing -> comparable(listing, candidate))
                .map(listing -> comparableStackPrice(listing, candidate))
                .filter(price -> price <= finalTarget)
                .count();
        double holdHours = latestStats.salesPerHour() > 0
                ? (listingsAhead + 1.0) / latestStats.salesPerHour()
                : Double.POSITIVE_INFINITY;
        if (!Double.isFinite(holdHours) || holdHours < 0
                || holdHours * 3_600_000.0 > policy.maximumHoldMillis()
                || holdHours > baseRisk.maximumExpectedHoldHours()) {
            return Result.rejected("Fresh full-book hold estimate exceeds policy");
        }

        double confidence = Math.min(original.confidence(), latestStats.confidence());
        if (!Double.isFinite(confidence) || confidence < Math.max(
                policy.minimumConfidence(), baseRisk.minimumConfidence())) {
            return Result.rejected("Fresh confidence is below policy");
        }
        double saleProbability = Math.min(original.saleProbability(),
                saleProbability(target, latestStats, candidate));
        double score = Math.min(original.score(), expectedNetProfit
                * saleProbability * confidence
                / candidate.totalPrice() / Math.max(0.05, holdHours));
        List<String> reasons = new ArrayList<>(original.reasons() == null
                ? List.of() : original.reasons());
        reasons.add(String.format(Locale.ROOT,
                "Live boundary revalidated at %d: target %d, %.1f min hold",
                latestStats.calculatedAt(), target, holdHours * 60));

        return Result.accepted(new Opportunity(
                candidate, latestStats, candidate.totalPrice(), target,
                expectedNetProfit, roiPercent, holdHours, saleProbability,
                confidence, score, List.copyOf(reasons)));
    }

    private static boolean sameListingIdentity(Listing expected, Listing actual) {
        if (actual == null || !actual.isValid()) return false;
        return safe(expected.listingKey()).equals(safe(actual.listingKey()))
                && safe(expected.itemKey()).equals(safe(actual.itemKey()))
                && safe(expected.itemId()).equals(safe(actual.itemId()))
                && expected.itemCount() == actual.itemCount()
                && expected.totalPrice() == actual.totalPrice()
                && safe(expected.sellerUuid()).equalsIgnoreCase(safe(actual.sellerUuid()))
                && safe(expected.sellerName()).equalsIgnoreCase(safe(actual.sellerName()));
    }

    private static boolean comparable(Listing listing, Listing candidate) {
        return listing != null && listing.isValid()
                && safe(listing.itemKey()).equals(candidate.itemKey())
                && listing.bucket() == candidate.bucket()
                && !safe(listing.listingKey()).equals(safe(candidate.listingKey()));
    }

    private static long comparableStackPrice(Listing listing, Listing candidate) {
        double price = listing.itemCount() == candidate.itemCount()
                ? listing.totalPrice()
                : listing.unitPrice() * candidate.itemCount();
        if (!Double.isFinite(price) || price <= 0 || price > Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.floor(price);
    }

    private static long stackTarget(double bandPrice, MarketStats stats, Listing candidate) {
        double target = stats.bucket() == StackBucket.OTHER
                ? bandPrice * candidate.itemCount() : bandPrice;
        if (!Double.isFinite(target) || target <= 0 || target > Long.MAX_VALUE) return -1;
        return (long) Math.floor(target);
    }

    private static double saleProbability(long target, MarketStats stats, Listing candidate) {
        long quick = stackTarget(stats.quickSalePrice(), stats, candidate);
        long fair = stackTarget(stats.weightedMedian(), stats, candidate);
        double priceFactor = target <= quick ? 0.9 : target <= fair ? 0.75 : 0.55;
        double liquidity = Math.min(1.0, 0.5 + stats.salesPerHour() / 12.0);
        return Math.max(0.05, Math.min(0.98, priceFactor * liquidity));
    }

    private static boolean finitePositive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static boolean validFees(FeeConfig fees) {
        return fees != null && fees.listingFeeFlat() >= 0
                && Double.isFinite(fees.listingFeePercent())
                && Double.isFinite(fees.saleTaxPercent())
                && fees.listingFeePercent() >= 0 && fees.saleTaxPercent() >= 0
                && fees.listingFeePercent() <= 100 && fees.saleTaxPercent() <= 100
                && fees.listingFeePercent() + fees.saleTaxPercent() < 100;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record Result(Optional<Opportunity> opportunity, String detail) {
        public Result {
            opportunity = opportunity == null ? Optional.empty() : opportunity;
            detail = detail == null ? "" : detail;
        }

        public static Result accepted(Opportunity opportunity) {
            return new Result(Optional.of(opportunity), "Fresh market and full book passed policy");
        }

        public static Result rejected(String detail) {
            return new Result(Optional.empty(), detail);
        }
    }
}
