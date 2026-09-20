package dev.doughbay.fabric.automation;

import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.fabric.StackProfile;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RetailPricingTest {
    private static final String ITEM = "minecraft:diamond_block";
    private static final long NOW = 1_000_000;
    private static final StackProfile.Shape HISTORY = new StackProfile.Shape(.7, .6, 20_000, 12_500, 50, 500);
    private static BidPriceEvidence.Quote quote(int count, long at, long price) {
        return new BidPriceEvidence.Quote(ITEM, count, at, List.of(price, price + 10, price + 20));
    }
    private static RetailPricing.Plan plan(FeeConfig fees, double profit) {
        return RetailPricing.plan(ITEM, 64, HISTORY, quote(1, NOW, 20_000), quote(64, NOW, 800_000), fees, profit, .10, NOW);
    }
    @Test void batchMarginUsesAllSinglesWithFeesChargedOnEveryListing() {
        var fees = new FeeConfig(1000, 2, 3);
        var result = plan(fees, 100_000);
        assertNotNull(result);
        assertEquals(19_400, result.singlePrice());
        assertEquals(fees.netSale(19_400) * 64, result.netProceeds());
        assertEquals(15_370, result.ceilingUnit());
        assertTrue(result.profit(result.ceilingUnit()) >= 100_000);
        assertTrue(result.profit(result.ceilingUnit()) / (result.ceilingUnit() * 64.0) >= .1);
        assertTrue(result.profit(10_000) < FeeConfig.zero().netSale(19_400) * 64 - 640_000);
    }
    @Test void profitTiersReferToThePurchasedBatchNotOneItem() {
        var result = plan(FeeConfig.zero(), 100_000);
        assertEquals("100K-500K", result.tier(15_000));
        assertEquals("500K-1M", result.tier(10_000));
        assertEquals("over 1M", result.tier(1000));
        assertTrue(result.singlePrice() - 10_000 < 100_000);
    }
    @Test void staleThinWrongSizeOrUnprovenQuotesCannotAuthorizeRetailBuying() {
        var current = quote(64, NOW, 800_000);
        assertNull(RetailPricing.plan(ITEM, 64, HISTORY, quote(1, NOW - BidPriceEvidence.QUOTE_TTL, 20_000), current, FeeConfig.zero(), 100_000, .1, NOW));
        assertNull(RetailPricing.plan(ITEM, 64, HISTORY, quote(64, NOW, 20_000), current, FeeConfig.zero(), 100_000, .1, NOW));
        assertNull(RetailPricing.plan(ITEM, 64, HISTORY, new BidPriceEvidence.Quote(ITEM, 1, NOW, List.of(20_000L)), current, FeeConfig.zero(), 100_000, .1, NOW));
        assertNull(RetailPricing.plan(ITEM, 64, null, quote(1, NOW, 20_000), current, FeeConfig.zero(), 100_000, .1, NOW));
        assertNull(RetailPricing.plan(ITEM, 64, HISTORY, quote(1, NOW + 1, 20_000), current, FeeConfig.zero(), 100_000, .1, NOW));
    }
    @Test void feeBurdenAndFallingSinglesCanEliminateTheRetailAdvantage() {
        assertNull(plan(new FeeConfig(10_000, 0, 0), 100_000));
        assertNull(RetailPricing.plan(ITEM, 64, HISTORY, quote(1, NOW, 10_000), quote(64, NOW, 800_000), FeeConfig.zero(), 100_000, .1, NOW));
        assertNull(plan(FeeConfig.zero(), 2_000_000));
        assertNull(plan(FeeConfig.zero(), Double.NaN));
    }
}
