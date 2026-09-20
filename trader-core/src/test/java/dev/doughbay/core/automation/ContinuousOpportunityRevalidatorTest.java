package dev.doughbay.core.automation;

import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.analysis.RiskConfig;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuousOpportunityRevalidatorTest {
    private static final ContinuousAutomationPolicy POLICY =
            new ContinuousAutomationPolicy(3, 100_000, 20_000,
                    1_000, 10, 0.8, 1_000, 0,
                    3_600_000, 90_000, 90_000);
    private final ContinuousOpportunityRevalidator revalidator =
            new ContinuousOpportunityRevalidator();

    @Test
    void fullBookCompetitionCanOnlyLowerTarget() {
        Opportunity original = opportunity(listing("candidate", 2_000, 2_000), stats(2_000));
        Listing competitor = listing("other", 7_501, 2_001);
        var result = revalidator.revalidate(original, stats(2_100),
                List.of(original.listing(), competitor), POLICY, FeeConfig.zero(),
                2_200, 2_000, true);

        assertTrue(result.opportunity().isPresent(), result.detail());
        Opportunity revised = result.opportunity().orElseThrow();
        assertEquals(7_500, revised.recommendedSellPrice());
        assertTrue(revised.recommendedSellPrice() <= original.recommendedSellPrice());
    }

    @Test
    void missingCandidateCannotAuthorizePurchase() {
        Opportunity original = opportunity(listing("candidate", 4_000, 2_000), stats(2_000));
        var result = revalidator.revalidate(original, stats(2_100), List.of(), POLICY,
                FeeConfig.zero(),
                2_200, 2_000, true);
        assertTrue(result.opportunity().isEmpty());
    }

    @Test
    void completedSaleStatsMustBeStrictlyNewerThanEvidence() {
        Opportunity original = opportunity(listing("candidate", 4_000, 2_000), stats(2_000));
        var result = revalidator.revalidate(original, stats(2_000),
                List.of(original.listing()), POLICY, FeeConfig.zero(),
                2_200, 2_000, true);
        assertTrue(result.opportunity().isEmpty());
    }

    @Test
    void policyIsReappliedAfterTargetFalls() {
        Opportunity original = opportunity(listing("candidate", 4_000, 2_000), stats(2_000));
        Listing competitor = listing("other", 4_500, 2_001);
        var result = revalidator.revalidate(original, stats(2_100),
                List.of(original.listing(), competitor), POLICY, FeeConfig.zero(),
                2_200, 2_000, true);
        assertTrue(result.opportunity().isEmpty());
    }

    @Test
    void confirmedFeesAreAppliedAgainAtTheLowerTarget() {
        Opportunity original = opportunity(listing("candidate", 2_000, 2_000), stats(2_000));
        Listing competitor = listing("other", 7_501, 2_001);
        var result = revalidator.revalidate(original, stats(2_100),
                List.of(original.listing(), competitor), POLICY,
                new FeeConfig(500, 0, 0), 2_200, 2_000, true);
        assertTrue(result.opportunity().isPresent(), result.detail());
        assertEquals(5_000.0,
                result.opportunity().orElseThrow().expectedNetProfit(), 1.0e-9);
    }

    @Test
    void freshBoundaryReappliesMinimumCompletedSaleSamples() {
        Opportunity original = opportunity(listing("candidate", 4_000, 2_000), stats(2_000));
        MarketStats thin = statsWith(2_100, 19, 2_000, 0.04);
        var result = revalidator.revalidate(original, thin,
                List.of(original.listing()), POLICY, FeeConfig.zero(),
                2_200, 2_000, true);

        assertTrue(result.opportunity().isEmpty());
    }

    @Test
    void freshBoundaryReappliesNewestSaleAgeAndVolatility() {
        long now = 8_000_000;
        Opportunity original = opportunity(listing("candidate", 4_000, 2_000), stats(2_000));
        MarketStats stale = statsWith(now - 10, 30,
                now - 3_600_001, 0.04);
        MarketStats volatileMarket = statsWith(now - 10, 30,
                now - 100, 0.251);

        assertTrue(revalidator.revalidate(original, stale,
                List.of(original.listing()), POLICY, FeeConfig.zero(),
                now, 2_000, true).opportunity().isEmpty());
        assertTrue(revalidator.revalidate(original, volatileMarket,
                List.of(original.listing()), POLICY, FeeConfig.zero(),
                now, 2_000, true).opportunity().isEmpty());
    }

    @Test
    void theRiskFloorIsTheConfiguredOneNotTheLibraryDefault() {
        Opportunity original = opportunity(listing("candidate", 4_000, 2_000), stats(2_000));
        // 50% volatility: over the library default of 25%, within a configured 60%.
        MarketStats volatileMarket = statsWith(2_100, 30, 2_000, 0.5);
        var byDefault = revalidator.revalidate(original, volatileMarket,
                List.of(original.listing()), POLICY, FeeConfig.zero(), 2_200, 2_000, true);
        assertTrue(byDefault.opportunity().isEmpty(), byDefault.detail());

        RiskConfig configured = new RiskConfig(12, 500, 20.0, 50.0, 10.0, 3, true, true,
                0.60, 120L * 60L * 1000L, 4.0, 0.25, 1);
        var configuredResult = new ContinuousOpportunityRevalidator(configured).revalidate(
                original, volatileMarket, List.of(original.listing()), POLICY,
                FeeConfig.zero(), 2_200, 2_000, true);
        assertTrue(configuredResult.opportunity().isPresent(), configuredResult.detail());
    }

    private static Opportunity opportunity(Listing listing, MarketStats stats) {
        return new Opportunity(listing, stats, listing.totalPrice(), 9_000,
                5_000, 125, 0.1, 0.9, 0.9, 10, List.of("test"));
    }

    private static Listing listing(String key, long price, long observedAt) {
        return new Listing(key, observedAt, "01234567-89ab-4def-8abc-0123456789ab",
                "Seller", "minecraft:ender_pearl", "minecraft:ender_pearl",
                16, price, 60_000L);
    }

    private static MarketStats stats(long calculatedAt) {
        return statsWith(calculatedAt, 30, calculatedAt - 100, 0.04);
    }

    private static MarketStats statsWith(long calculatedAt, int samples,
                                         long newestSaleAt, double volatility) {
        return new MarketStats("minecraft:ender_pearl", StackBucket.X16,
                86_400_000, samples, 0, 10, newestSaleAt,
                8_000, 9_000, 9_500, 10_000, 10_500,
                10, volatility, 0.01, 0.9, calculatedAt);
    }
}
