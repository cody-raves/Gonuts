package dev.doughbay.fabric.automation;

import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OrderBidPricingTest {
    private static final String ITEM = "minecraft:sea_lantern";

    private MarketStats market(StackBucket bucket, double price) {
        return new MarketStats(ITEM, bucket, 86_400_000L, 100, 0, 8, 1,
                price, price, price, price, price, 10, 0, 0, 0.8, 1);
    }

    private long ceiling(List<MarketStats> markets, int requested, int maxStack) {
        return OrderBidPricing.ceilingUnit(markets, ITEM, requested, maxStack,
                MarketStats::quickSalePrice, FeeConfig.zero(), 500, 0.1);
    }

    @Test void theStackHalfOfASinglesItemOnlyHasToPayForItself() {
        // Golden apples: a stack resells for about 23,000 each, orders stand at 22,220.
        assertEquals(22_992, OrderBidPricing.breakevenUnit(1_472_000, 64, FeeConfig.zero(), 500));
        assertTrue(OrderBidPricing.ceilingFromResale(1_472_000, 64, FeeConfig.zero(), 500, 0.03) < 22_220);
        assertEquals(0, OrderBidPricing.breakevenUnit(0, 64, FeeConfig.zero(), 500));
        assertEquals(0, OrderBidPricing.breakevenUnit(400, 64, FeeConfig.zero(), 500));
    }

    @Test void multiStackOrdersUseTheSamePerItemCeilingAsOneResaleStack() {
        // Regression: 320/576 requested used OTHER's unit price, then divided
        // by the order quantity a second time, cancelling bids at $2,600.
        var markets = List.of(market(StackBucket.OTHER, 3000), market(StackBucket.X64, 192500));
        for (int quantity : new int[]{64, 192, 320, 384, 576, 960}) {
            assertEquals(2652, ceiling(markets, quantity, 64), "order size " + quantity);
        }
    }

    @Test void risingResaleMarketAllowsAProfitableUpwardAdjustment() {
        long before = ceiling(List.of(market(StackBucket.X64, 192500)), 576, 64);
        long after = ceiling(List.of(market(StackBucket.X64, 220000)), 576, 64);
        assertEquals(2652, before);
        assertEquals(3031, after);
        assertEquals(0, OrderBidPricing.chaseTarget(2600, 2800, before));
        assertEquals(2828, OrderBidPricing.chaseTarget(2600, 2800, after));
    }

    @Test void fallingResaleMarketStillLowersTheBidCeiling() {
        long ceiling = ceiling(List.of(market(StackBucket.X64, 150000)), 576, 64);
        assertEquals(2066, ceiling);
        assertTrue(2600 > ceiling);
        assertEquals(0, OrderBidPricing.chaseTarget(2600, 2800, ceiling));
    }

    @Test void unknownStackMarketDoesNotInventALowPriceFromOtherOrSingleRows() {
        assertEquals(0, ceiling(List.of(market(StackBucket.OTHER, 3000), market(StackBucket.X1, 9000)), 576, 64));
    }

    @Test void singlesAndSixteenStackItemsKeepTheirOwnResaleUnits() {
        var markets = List.of(market(StackBucket.X1, 10000), market(StackBucket.X16, 32000),
                market(StackBucket.X64, 192500));
        assertEquals(8818, ceiling(markets, 1, 64));
        assertEquals(1763, ceiling(markets, 160, 16));
        assertEquals(8818, ceiling(markets, 10, 1));
        assertEquals(2652, ceiling(markets, 53, 64));
    }

    @Test void feesProfitFloorAndLiveResaleCapStillConstrainOrders() {
        var markets = List.of(market(StackBucket.X64, 192500));
        assertEquals(2505, OrderBidPricing.ceilingUnit(markets, ITEM, 576, 64,
                MarketStats::quickSalePrice, new FeeConfig(1000, 2, 3), 500, 0.1));
        assertEquals(2066, OrderBidPricing.ceilingUnit(markets, ITEM, 576, 64,
                m -> 150000, FeeConfig.zero(), 500, 0.1));
        assertEquals(0, OrderBidPricing.ceilingUnit(markets, ITEM, 576, 64,
                m -> 0, FeeConfig.zero(), 500, 0.1));
        assertEquals(0, OrderBidPricing.ceilingUnit(markets, ITEM, 576, 64,
                m -> Double.NaN, FeeConfig.zero(), 500, 0.1));
    }

    @Test void chaseOnlyRaisesOutbidOrdersWithinTheCeiling() {
        assertEquals(2626, OrderBidPricing.chaseTarget(2500, 2600, 2626));
        assertEquals(0, OrderBidPricing.chaseTarget(2500, 2600, 2625));
        assertEquals(0, OrderBidPricing.chaseTarget(2600, 2600, 3000));
        assertEquals(0, OrderBidPricing.chaseTarget(2700, 2600, 3000));
        assertEquals(0, OrderBidPricing.chaseTarget(2500, Long.MAX_VALUE, Long.MAX_VALUE));
    }
}
