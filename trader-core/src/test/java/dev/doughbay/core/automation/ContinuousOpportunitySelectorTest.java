package dev.doughbay.core.automation;

import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuousOpportunitySelectorTest {
    private static final long NOW = 1_000_000L;

    @Test
    void invalidCapsCannotBecomeAnUnboundedPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new ContinuousAutomationPolicy(
                1, 0, 0, 0, 0, 0.8,
                1_000, 8, 60_000, 30_000, 5_000));
    }

    @Test
    void demoStaleAndUnauthorizedContextsBlockTheWholeSession() {
        ContinuousOpportunitySelector selector = new ContinuousOpportunitySelector();
        var demo = context(true, true, NOW - 1_000, Set.of());
        assertEquals(ContinuousOpportunitySelector.Status.SESSION_BLOCKED,
                selector.select(List.of(opportunity("a", 10)), policy(), demo).status());

        var stale = new ContinuousAutomationContext(NOW, NOW - 90_001,
                false, false, true, true, false, 0, 0, 0, Set.of());
        assertEquals(ContinuousOpportunitySelector.Status.SESSION_BLOCKED,
                selector.select(List.of(opportunity("a", 10)), policy(), stale).status());

        var unauthorized = context(false, false, NOW - 1_000, Set.of());
        assertEquals(ContinuousOpportunitySelector.Status.SESSION_BLOCKED,
                selector.select(List.of(opportunity("a", 10)), policy(), unauthorized).status());
    }

    @Test
    void deterministicSelectionSkipsAttemptedAndUsesScoreThenKey() {
        ContinuousOpportunitySelector selector = new ContinuousOpportunitySelector();
        Opportunity b = opportunity("b", 20);
        Opportunity a = opportunity("a", 20);
        var selected = selector.select(List.of(b, a), policy(),
                context(false, true, NOW - 100, Set.of()));
        assertEquals("a", selected.opportunity().orElseThrow().listing().listingKey());

        var next = selector.select(List.of(b, a), policy(),
                context(false, true, NOW - 100, Set.of("a")));
        assertEquals("b", next.opportunity().orElseThrow().listing().listingKey());
    }

    @Test
    void mismatchedExactMarketAndBudgetOverflowAreRejected() {
        Opportunity valid = opportunity("valid", 10);
        MarketStats wrong = stats("minecraft:diamond", NOW - 500);
        Opportunity mismatch = new Opportunity(valid.listing(), wrong,
                valid.buyPrice(), valid.recommendedSellPrice(), valid.expectedNetProfit(),
                valid.expectedRoiPercent(), valid.estimatedHoldHours(),
                valid.saleProbability(), valid.confidence(), valid.score(), valid.reasons());
        var selector = new ContinuousOpportunitySelector();
        var mismatchResult = selector.select(List.of(mismatch), policy(),
                context(false, true, NOW - 100, Set.of()));
        assertEquals(ContinuousOpportunitySelector.Status.NO_ELIGIBLE,
                mismatchResult.status());
        assertTrue(mismatchResult.rejectedCandidates().get("valid").stream()
                .anyMatch(reason -> reason.contains("exact completed-sale market")));

        var budgetContext = new ContinuousAutomationContext(NOW, NOW - 1_000,
                false, false, true, true, false, 0, 9_500, 0, Set.of());
        var budgetResult = selector.select(List.of(valid), policy(), budgetContext);
        assertEquals(ContinuousOpportunitySelector.Status.NO_ELIGIBLE,
                budgetResult.status());
    }

    @Test
    void opportunityCannotInflateCompletedSaleTargetProfitOrRoi() {
        Opportunity valid = opportunity("inflated", 10);
        Opportunity inflated = new Opportunity(valid.listing(), valid.stats(),
                valid.buyPrice(), 9_000, 8_000, 800,
                valid.estimatedHoldHours(), valid.saleProbability(),
                valid.confidence(), valid.score(), valid.reasons());
        var result = new ContinuousOpportunitySelector().select(List.of(inflated), policy(),
                context(false, true, NOW - 100, Set.of()));
        assertEquals(ContinuousOpportunitySelector.Status.NO_ELIGIBLE, result.status());
        assertTrue(result.rejectedCandidates().get("inflated").stream()
                .anyMatch(reason -> reason.contains("completed-sale quick band")));
    }

    @Test
    void reportedRoiCannotExceedRoiRecomputedFromExpectedProfit() {
        Opportunity valid = opportunity("roi", 10);
        Opportunity inflated = new Opportunity(valid.listing(), valid.stats(),
                valid.buyPrice(), valid.recommendedSellPrice(), 500, 100,
                valid.estimatedHoldHours(), valid.saleProbability(),
                valid.confidence(), valid.score(), valid.reasons());
        var result = new ContinuousOpportunitySelector().select(List.of(inflated), policy(),
                context(false, true, NOW - 100, Set.of()));
        assertEquals(ContinuousOpportunitySelector.Status.NO_ELIGIBLE, result.status());
        assertTrue(result.rejectedCandidates().get("roi").stream()
                .anyMatch(reason -> reason.contains("recomputed profit ROI")));
    }

    private static ContinuousAutomationPolicy policy() {
        return new ContinuousAutomationPolicy(3, 10_000, 5_000,
                100, 10, 0.8, 1_000, 8,
                3_600_000, 90_000, 10_000);
    }

    private static ContinuousAutomationContext context(boolean demo, boolean authorized,
                                                       long updatedAt, Set<String> attempted) {
        return new ContinuousAutomationContext(NOW, updatedAt, demo, false,
                authorized, true, false, 0, 0, 0, attempted);
    }

    private static Opportunity opportunity(String key, double score) {
        Listing listing = new Listing(key, NOW - 500, "uuid", "Seller_1",
                "minecraft:ender_pearl", "minecraft:ender_pearl", 16, 1_000, 60_000L);
        return new Opportunity(listing, stats("minecraft:ender_pearl", NOW - 500),
                1_000, 2_000, 1_000, 100, 0.1,
                0.9, 0.9, score, List.of("test"));
    }

    private static MarketStats stats(String itemKey, long calculatedAt) {
        return new MarketStats(itemKey, StackBucket.X16, 3_600_000,
                30, 0, 10, NOW - 500,
                1_800, 2_000, 2_100, 2_200, 2_300,
                10, 0.03, 0.01, 0.9, calculatedAt);
    }
}
