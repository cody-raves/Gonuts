package dev.doughbay.fabric.automation;

import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.fabric.StackProfile;

/** A retail plan needs actual historical sales AND fresh exact-size quotes. */
final class RetailPricing {
    record Plan(int count, long singlePrice, long ceilingUnit, double netProceeds, double stackPrice) {
        double profit(long unitCost) { return netProceeds - unitCost * (double) count; }
        String tier(long unitCost) {
            double profit = profit(unitCost);
            return profit > 1_000_000 ? "over 1M" : profit > 500_000 ? "500K-1M"
                    : profit >= 100_000 ? "100K-500K" : "under 100K";
        }
    }

    static Plan plan(String item, int count, StackProfile.Shape history,
                     BidPriceEvidence.Quote singles, BidPriceEvidence.Quote stack,
                     FeeConfig fees, double minimumProfit, double minimumRoi, long now) {
        if (count <= 1 || history == null || !history.prefersSingles() || singles == null || stack == null
                || fees == null || !Double.isFinite(minimumProfit) || minimumProfit < 0
                || !Double.isFinite(minimumRoi) || minimumRoi < 0)
            return null;
        double singlePrice = Math.min(history.singlePrice(), singles.conservativePrice(item, 1, now));
        double stackPrice = stack.conservativePrice(item, count, now);
        if (singlePrice <= 0 || stackPrice <= 0 || !Double.isFinite(singlePrice)) return null;
        long target = (long) Math.floor(singlePrice);
        double net = fees.netSale(target) * count;
        // Repeated listing fees must not turn an apparent premium into a loss.
        if (net < fees.netSale(stackPrice) * 1.20 || !Double.isFinite(net)) return null;
        long ceiling = (long) Math.floor(Math.min(net - minimumProfit, net / (1 + minimumRoi)) * 0.97 / count);
        return ceiling > 0 ? new Plan(count, target, ceiling, net, stackPrice) : null;
    }

    private RetailPricing() { }
}
