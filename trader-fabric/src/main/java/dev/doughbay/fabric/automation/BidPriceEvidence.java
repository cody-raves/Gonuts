package dev.doughbay.fabric.automation;

import dev.doughbay.core.model.MarketStats;
import java.util.List;

/** Live asks can refresh an established market's bid price, not its sales history. */
final class BidPriceEvidence {
    static final long QUOTE_TTL = 5 * 60_000L;
    static final long HISTORY_TTL = 24 * 3_600_000L;

    record Quote(String itemId, int count, long observedAt, List<Long> asks) {
        Quote { asks = List.copyOf(asks); }

        double conservativePrice(MarketStats market, long now) {
            return conservativePrice(market.itemKey(), market.bucket().exactCount(), now);
        }

        double conservativePrice(String item, int exactCount, long now) {
            if (!itemId.equals(item) || count != exactCount || observedAt <= 0
                    || observedAt > now || now - observedAt >= QUOTE_TTL) return 0;
            List<Long> prices = asks.stream().filter(p -> p > 0).sorted().toList();
            if (prices.size() < 3) return 0;
            return prices.get((prices.size() - 1) / 4) * 0.97;
        }
    }

    static boolean refreshable(MarketStats market, double minimumConfidence, double minimumSamples, long now) {
        if (!market.hasPrices() || !Double.isFinite(market.quickSalePrice()) || market.quickSalePrice() <= 0
                || market.bucket().exactCount() <= 0 || market.sampleCount() < minimumSamples
                || market.newestSaleAt() <= 0 || market.newestSaleAt() > now
                || now - market.newestSaleAt() > HISTORY_TTL
                || (Double.isFinite(market.trend()) && market.trend() < -0.05)) return false;
        // Undo only MarketAnalyzer's age penalty, at the time it calculated
        // confidence. The sample, seller, outlier and volatility penalties stay.
        double ageHours = Math.max(0, market.calculatedAt() - market.newestSaleAt()) / 3_600_000.0;
        double historicalQuality = Math.min(1.0, market.confidence() * (1.0 + ageHours / 4.0));
        return Double.isFinite(historicalQuality) && historicalQuality >= minimumConfidence;
    }

    static double resalePrice(MarketStats market, Quote quote, double minimumConfidence,
                              double minimumSamples, long now) {
        double live = quote == null ? 0 : quote.conservativePrice(market, now);
        if (market.confidence() < minimumConfidence
                && (!refreshable(market, minimumConfidence, minimumSamples, now) || live <= 0)) return 0;
        // Even a confident history cannot bid above a lower observed ask.
        return live > 0 ? Math.min(market.quickSalePrice(), live) : market.quickSalePrice();
    }

    private BidPriceEvidence() {}
}
