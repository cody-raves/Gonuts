package dev.doughbay.core.performance;

/**
 * How one market has actually paid off, settled trades only.
 *
 * <p>Realized profit is the sum over closed positions in the market; a trade
 * still open contributes nothing, because an unsettled position is not a result
 * yet. {@code settledSales} is how many closed trades that sum rests on, and it
 * is the guard against acting on noise: one unlucky flip is not evidence a
 * market is bad, a dozen losing ones is.
 *
 * @param itemKey       the market's item key
 * @param realizedProfit summed realized profit across settled trades (may be negative)
 * @param settledSales   how many settled trades the sum rests on
 */
public record MarketProfit(String itemKey, double realizedProfit, int settledSales) {
}
