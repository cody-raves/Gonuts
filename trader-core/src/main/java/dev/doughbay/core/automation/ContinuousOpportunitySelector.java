package dev.doughbay.core.automation;

import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.NamespacedId;
import dev.doughbay.core.model.Opportunity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.regex.Pattern;

/** Deterministic, side-effect-free fail-closed selection for live automation. */
public final class ContinuousOpportunitySelector {
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public SelectionResult select(List<Opportunity> opportunities,
                                  ContinuousAutomationPolicy policy,
                                  ContinuousAutomationContext context) {
        if (policy == null || context == null) {
            return SelectionResult.blocked("Missing automation policy or context");
        }
        String globalBlocker = globalBlocker(policy, context);
        if (globalBlocker != null) return SelectionResult.blocked(globalBlocker);

        List<Opportunity> source = opportunities == null ? List.of() : List.copyOf(opportunities);
        Map<String, List<String>> rejected = new LinkedHashMap<>();
        List<Opportunity> eligible = new ArrayList<>();
        for (Opportunity opportunity : source) {
            List<String> reasons = candidateRejections(opportunity, policy, context);
            String key = listingKey(opportunity);
            if (reasons.isEmpty()) eligible.add(opportunity);
            else rejected.put(key, reasons);
        }

        eligible.sort(Comparator
                .comparingDouble(ContinuousOpportunitySelector::finiteScore).reversed()
                .thenComparing(Comparator.comparingDouble(
                        ContinuousOpportunitySelector::finiteProfit).reversed())
                .thenComparing(ContinuousOpportunitySelector::listingKey));
        if (eligible.isEmpty()) {
            return new SelectionResult(Status.NO_ELIGIBLE, Optional.empty(),
                    "No current opportunity passed every live-trade gate", rejected);
        }
        Opportunity selected = eligible.getFirst();
        return new SelectionResult(Status.SELECTED, Optional.of(selected),
                "Selected fresh exact listing " + selected.listing().listingKey(), rejected);
    }

    private static String globalBlocker(ContinuousAutomationPolicy policy,
                                        ContinuousAutomationContext context) {
        if (context.sessionStopped()) return "Automation session is stopped";
        if (!context.authorized()) return "Authorized continuous execution gate is closed";
        if (context.demoSnapshot()) return "Demo snapshots can never drive execution";
        if (!context.driverIdle()) return "Execution driver is already active";
        if (context.hasOpenPosition()) return "One position is already open";
        if (context.snapshotUpdatedAtMillis() <= 0
                || context.snapshotUpdatedAtMillis() > context.nowMillis()) {
            return "Market snapshot time is missing or in the future";
        }
        if (context.nowMillis() - context.snapshotUpdatedAtMillis()
                > policy.maximumSnapshotAgeMillis()) {
            return "Market snapshot is stale";
        }
        if (context.nowMillis() < context.cooldownUntilMillis()) {
            return "Post-trade cooldown is active";
        }
        if (context.tradesStarted() >= policy.maxTradesPerSession()) {
            return "Session trade limit reached";
        }
        if (context.committedSpend() >= policy.maxSessionSpend()) {
            return "Session spend limit reached";
        }
        return null;
    }

    private static List<String> candidateRejections(
            Opportunity opportunity,
            ContinuousAutomationPolicy policy,
            ContinuousAutomationContext context) {
        List<String> reasons = new ArrayList<>();
        if (opportunity == null || opportunity.listing() == null || opportunity.stats() == null) {
            reasons.add("missing listing or completed-sale statistics");
            return reasons;
        }
        Listing listing = opportunity.listing();
        MarketStats stats = opportunity.stats();

        if (listing.listingKey() == null || listing.listingKey().isBlank()
                || listing.listingKey().toLowerCase(Locale.ROOT).startsWith("demo-")) {
            reasons.add("missing or demo listing identity");
        } else if (context.attemptedListingKeys().contains(listing.listingKey())) {
            reasons.add("listing was already attempted this session");
        }
        if (!listing.isValid() || listing.totalPrice() != opportunity.buyPrice()) {
            reasons.add("listing and opportunity price/count identity do not agree");
        }
        if (listing.sellerName() == null
                || !PLAYER_NAME.matcher(listing.sellerName()).matches()) {
            reasons.add("seller identity is missing or malformed");
        }
        if (!isCanonicalId(listing.itemId())) {
            reasons.add("item id is not a canonical namespaced id");
        }
        // The POC collector only promotes metadata-plain commodities. Requiring
        // itemKey == itemId prevents a future rich fingerprint from silently
        // entering the automatic exact-stack path without component checks.
        if (listing.itemKey() == null || !listing.itemKey().equals(listing.itemId())) {
            reasons.add("automatic sessions accept metadata-plain commodities only");
        }
        if (!Objects.equals(listing.itemKey(), stats.itemKey())
                || listing.bucket() != stats.bucket()) {
            reasons.add("listing is not bound to its exact completed-sale market");
        }
        if (!freshAt(listing.observedAt(), context, policy)
                || !freshAt(stats.calculatedAt(), context, policy)) {
            reasons.add("listing or completed-sale valuation is stale/future-dated");
        }
        if (listing.observedAt() > context.snapshotUpdatedAtMillis()
                || stats.calculatedAt() > context.snapshotUpdatedAtMillis()) {
            reasons.add("candidate is newer than its containing snapshot");
        }
        if (!stats.hasPrices() || stats.sampleCount() <= 0) {
            reasons.add("completed-sale valuation has no usable samples");
        }
        if (!Double.isFinite(stats.quickSalePrice()) || stats.quickSalePrice() <= 0
                || !Double.isFinite(stats.weightedMedian()) || stats.weightedMedian() <= 0) {
            reasons.add("completed-sale price bands are invalid");
        }
        if (!Double.isFinite(stats.trend()) || stats.trend() < 0) {
            reasons.add("market trend is falling or unknown");
        }
        if (opportunity.buyPrice() <= 0
                || opportunity.buyPrice() > policy.maxPurchasePrice()) {
            reasons.add("purchase exceeds per-trade cap");
        }
        if (opportunity.buyPrice() > policy.maxSessionSpend() - context.committedSpend()) {
            reasons.add("purchase exceeds remaining session spend");
        }
        if (opportunity.recommendedSellPrice() <= opportunity.buyPrice()) {
            reasons.add("resale target is not above purchase price");
        }
        long completedSaleCap = completedSaleTargetCap(stats, listing);
        if (completedSaleCap <= 0
                || opportunity.recommendedSellPrice() > completedSaleCap) {
            reasons.add("resale target exceeds the conservative completed-sale quick band");
        }
        double grossProfit = (double) opportunity.recommendedSellPrice()
                - opportunity.buyPrice();
        double grossRoiPercent = opportunity.buyPrice() <= 0
                ? Double.NaN
                : grossProfit / opportunity.buyPrice() * 100.0;
        double recomputedNetRoiPercent = opportunity.buyPrice() <= 0
                ? Double.NaN
                : opportunity.expectedNetProfit() / opportunity.buyPrice() * 100.0;
        if (!Double.isFinite(opportunity.expectedNetProfit())
                || opportunity.expectedNetProfit() > grossProfit + 1.0e-9) {
            reasons.add("expected net profit exceeds independently recomputed gross profit");
        }
        if (!Double.isFinite(opportunity.expectedRoiPercent())
                || !Double.isFinite(recomputedNetRoiPercent)
                || opportunity.expectedRoiPercent() > grossRoiPercent + 1.0e-9
                || opportunity.expectedRoiPercent() > recomputedNetRoiPercent + 1.0e-9) {
            reasons.add("expected ROI exceeds independently recomputed profit ROI");
        }
        if (!Double.isFinite(opportunity.expectedNetProfit())
                || opportunity.expectedNetProfit() < policy.minimumProfit()) {
            reasons.add("expected profit is below policy");
        }
        if (!Double.isFinite(recomputedNetRoiPercent)
                || recomputedNetRoiPercent < policy.minimumRoiPercent()) {
            reasons.add("expected ROI is below policy");
        }
        if (!Double.isFinite(opportunity.confidence())
                || opportunity.confidence() < policy.minimumConfidence()
                || opportunity.confidence() > 1
                || !Double.isFinite(stats.confidence())
                || stats.confidence() < 0 || stats.confidence() > 1
                || opportunity.confidence() > stats.confidence() + 1.0e-9) {
            reasons.add("confidence is below policy or inconsistent with market statistics");
        }
        if (!Double.isFinite(opportunity.estimatedHoldHours())
                || opportunity.estimatedHoldHours() < 0
                || opportunity.estimatedHoldHours() * 3_600_000.0 > policy.maximumHoldMillis()) {
            reasons.add("estimated hold time exceeds policy");
        }
        return List.copyOf(reasons);
    }

    private static long completedSaleTargetCap(MarketStats stats, Listing listing) {
        // Capped at the patient band (p62.5) rather than the quick band: a
        // median-priced relist is a legitimate target, an above-market one
        // is not.
        double band = Math.max(stats.quickSalePrice(), stats.patientSalePrice());
        double target = stats.bucket() == dev.doughbay.core.model.StackBucket.OTHER
                ? band * listing.itemCount()
                : band;
        if (!Double.isFinite(target) || target <= 0 || target > Long.MAX_VALUE) return -1;
        return (long) Math.floor(target);
    }

    private static boolean freshAt(long timestamp, ContinuousAutomationContext context,
                                   ContinuousAutomationPolicy policy) {
        return timestamp > 0 && timestamp <= context.nowMillis()
                && context.nowMillis() - timestamp
                <= policy.maximumSnapshotAgeMillis();
    }

    private static boolean isCanonicalId(String itemId) {
        if (itemId == null || itemId.isBlank()) return false;
        try {
            return NamespacedId.normalize(itemId).equals(itemId);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String listingKey(Opportunity opportunity) {
        if (opportunity == null || opportunity.listing() == null
                || opportunity.listing().listingKey() == null
                || opportunity.listing().listingKey().isBlank()) {
            return "<missing-listing>";
        }
        return opportunity.listing().listingKey();
    }

    private static double finiteScore(Opportunity opportunity) {
        return Double.isFinite(opportunity.score()) ? opportunity.score() : -Double.MAX_VALUE;
    }

    private static double finiteProfit(Opportunity opportunity) {
        return Double.isFinite(opportunity.expectedNetProfit())
                ? opportunity.expectedNetProfit() : -Double.MAX_VALUE;
    }

    public enum Status { SELECTED, NO_ELIGIBLE, SESSION_BLOCKED }

    public record SelectionResult(
            Status status,
            Optional<Opportunity> opportunity,
            String detail,
            Map<String, List<String>> rejectedCandidates
    ) {
        public SelectionResult {
            status = status == null ? Status.SESSION_BLOCKED : status;
            opportunity = opportunity == null ? Optional.empty() : opportunity;
            detail = detail == null ? "" : detail;
            Map<String, List<String>> copy = new LinkedHashMap<>();
            if (rejectedCandidates != null) {
                rejectedCandidates.forEach((key, value) -> copy.put(
                        key == null ? "<missing-listing>" : key,
                        value == null ? List.of() : List.copyOf(value)));
            }
            rejectedCandidates = Map.copyOf(copy);
        }

        private static SelectionResult blocked(String detail) {
            return new SelectionResult(Status.SESSION_BLOCKED, Optional.empty(),
                    detail, Map.of());
        }
    }
}
