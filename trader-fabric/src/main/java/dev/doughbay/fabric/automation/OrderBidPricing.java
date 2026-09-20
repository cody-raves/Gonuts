package dev.doughbay.fabric.automation;

import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.StackBucket;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Prices a buy order by its resale stack, independently of the total order quantity. */
final class OrderBidPricing {
    static long ceilingUnit(List<MarketStats> markets, String itemId, int requested, int maxStack,
                            ToDoubleFunction<MarketStats> resalePrice, FeeConfig fees,
                            double minimumProfit, double minimumRoi) {
        if (requested <= 0 || maxStack <= 0) return 0;
        int count = Math.min(requested, maxStack);
        // Odd lots are sold in the item's normal stack market. OTHER prices
        // are already per item; dividing those by the whole order (e.g. 576)
        // made ordinary sea-lantern prices look like $4 and cancelled good bids.
        if (StackBucket.of(count) == StackBucket.OTHER) count = maxStack;
        StackBucket bucket = StackBucket.of(count);
        if (bucket == StackBucket.OTHER) return 0;
        for (MarketStats market : markets) {
            if (!market.itemKey().equals(itemId) || market.bucket() != bucket || !market.hasPrices()) continue;
            return ceilingFromResale(resalePrice.applyAsDouble(market), count, fees, minimumProfit, minimumRoi);
        }
        return 0;
    }

    /** The most one item may cost in a lot of {@code count} that resells for {@code resale}; 0 with no price. */
    static long ceilingFromResale(double resale, int count, FeeConfig fees, double minimumProfit, double minimumRoi) {
        if (count <= 0 || !Double.isFinite(resale) || resale <= 0) return 0;
        double net = fees.netSale(resale);
        double perStack = Math.min(net - minimumProfit, net / (1.0 + minimumRoi)) * 0.97;
        return Math.max(0, (long) Math.floor(perStack / count));
    }

    /**
     * The most one item may cost for a lot of {@code count} to come out at no
     * loss when it resells for {@code resale}. The stack half of an item that
     * is also sold as singles is held to this and not to the full margin: the
     * singles carry the margin, the stacks only have to pay for themselves.
     * Held to the full margin, golden apples at 22,220 against stacks that
     * resell for 23,000 fell back to twelve at a time, which gave up every
     * stack the same order used to bring in at a profit.
     */
    static long breakevenUnit(double resale, int count, FeeConfig fees, double minimumProfit) {
        if (count <= 0 || !Double.isFinite(resale) || resale <= 0) return 0;
        return Math.max(0, (long) Math.floor((fees.netSale(resale) - minimumProfit) / count));
    }

    /** Zero means keep the current order rather than chase beyond its resale margin. */
    static long chaseTarget(long currentUnit, long bestUnit, long ceilingUnit) {
        if (currentUnit <= 0 || bestUnit <= currentUnit) return 0;
        long increase = Math.max(1, Math.round(bestUnit * 0.01));
        if (bestUnit > Long.MAX_VALUE - increase) return 0;
        long above = bestUnit + increase;
        return above <= ceilingUnit ? above : 0;
    }

    private OrderBidPricing() {}
}
