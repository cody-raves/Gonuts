package dev.doughbay.core.analysis;

import dev.doughbay.core.performance.MarketProfit;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Names the markets that have earned their way out of the scan roster by
 * losing money, so selection can spend those slots on something that pays.
 *
 * <p>Market selection ranks by price confidence and liquidity — how trustworthy
 * a quote is and how fast it fills — because those are what a snapshot can see.
 * What a snapshot cannot see is that a market looked good on every metric and
 * still lost money on trade after trade: a spread that closes the instant we
 * buy, a book that only fills below cost, a commodity everyone is dumping. The
 * ledger has that answer, and nothing was feeding it back into selection.
 *
 * <p>This is deliberately conservative. A market is only a loser if it has
 * settled enough trades to rule out a bad run ({@code minSettledSales}) <em>and</em>
 * its total realized profit is at or below a tolerated floor (a negative
 * number). One unlucky flip never prunes a market; a sustained drain does. The
 * filter only removes from the auto-picked pool — a market the operator pinned
 * or is still holding is their explicit choice and is never touched here.
 *
 * <p>Pure and side-effect free: it returns a set to exclude and changes
 * nothing itself.
 */
public final class LosingMarketFilter {

    private LosingMarketFilter() {
    }

    /**
     * @param pnl              realized profit per market, settled trades only
     * @param minSettledSales  settled trades a market must have before its loss
     *                         counts as a verdict rather than a bad run (>= 1)
     * @param toleratedFloor   the least realized profit a market may sit at and
     *                         still be kept; must be {@code <= 0} (e.g. -50000
     *                         tolerates a 50k drawdown before pruning)
     * @return the item keys whose markets should be excluded from auto-selection
     */
    public static Set<String> losers(Collection<MarketProfit> pnl,
                                     int minSettledSales,
                                     double toleratedFloor) {
        Set<String> excluded = new LinkedHashSet<>();
        if (pnl == null) return excluded;
        int minSales = Math.max(1, minSettledSales);
        double floor = Math.min(0.0, toleratedFloor);
        for (MarketProfit m : pnl) {
            if (m == null || m.itemKey() == null) continue;
            if (m.settledSales() < minSales) continue;
            if (m.realizedProfit() <= floor) {
                excluded.add(m.itemKey());
            }
        }
        return excluded;
    }
}
