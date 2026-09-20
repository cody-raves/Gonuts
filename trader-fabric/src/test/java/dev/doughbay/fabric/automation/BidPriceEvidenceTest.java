package dev.doughbay.fabric.automation;

import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.StackBucket;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BidPriceEvidenceTest {
    private static final long NOW = 100 * 3_600_000L;

    private MarketStats market(double confidence, int samples, int hoursOld, double trend) {
        return new MarketStats("minecraft:bone_meal", StackBucket.X64, 7 * 86_400_000L,
                samples, 0, 8, NOW - hoursOld * 3_600_000L, 6000, 6500, 6700, 6800,
                7000, 5, 0.02, trend, confidence, NOW);
    }

    private BidPriceEvidence.Quote quote(long observedAt, Long... prices) {
        return new BidPriceEvidence.Quote("minecraft:bone_meal", 64, observedAt, List.of(prices));
    }

    private double price(MarketStats market, BidPriceEvidence.Quote quote) {
        return BidPriceEvidence.resalePrice(market, quote, 0.2, 15, NOW);
    }

    @Test void agedEstablishedMarketNeedsLiveEvidenceBeforeBidding() {
        var market = market(0.16, 100, 6, 0);
        assertTrue(BidPriceEvidence.refreshable(market, 0.2, 15, NOW));
        assertEquals(0, price(market, null));
        assertEquals(5820, price(market, quote(NOW, 6000L, 6500L, 7000L)));
    }

    @Test void livePriceCannotRaiseHistoricalResaleEstimate() {
        assertEquals(6500, price(market(0.16, 100, 6, 0), quote(NOW, 9000L, 10000L, 11000L)));
    }

    @Test void lowerLivePriceAlsoCapsAConfidentMarket() {
        assertEquals(4850, price(market(0.4, 100, 0, 0), quote(NOW, 5000L, 6000L, 7000L)));
        assertEquals(6500, price(market(0.4, 100, 0, 0), null));
    }

    @Test void weakOrInsufficientHistoryDoesNotBecomeTradeableFromAsksAlone() {
        var quote = quote(NOW, 6000L, 6500L, 7000L);
        assertEquals(0, price(market(0.04, 100, 6, 0), quote));
        assertEquals(0, price(market(0.16, 14, 6, 0), quote));
        assertEquals(0, price(market(0.16, 100, 25, 0), quote));
        assertEquals(0, price(market(0.16, 100, 6, -0.1), quote));
    }

    @Test void staleFutureAndSparseQuotesCannotUnlockBids() {
        var market = market(0.16, 100, 6, 0);
        assertEquals(0, price(market, quote(NOW - BidPriceEvidence.QUOTE_TTL, 6000L, 6500L, 7000L)));
        assertEquals(0, price(market, quote(NOW + 1, 6000L, 6500L, 7000L)));
        assertEquals(0, price(market, quote(NOW, 6000L, 6500L)));
        assertEquals(0, price(market, quote(NOW, 0L, -1L, 7000L)));
    }

    @Test void quoteMustMatchTheExactMarketAndStackSize() {
        var market = market(0.16, 100, 6, 0);
        assertEquals(0, price(market, new BidPriceEvidence.Quote("minecraft:bone_meal", 1, NOW,
                List.of(6000L, 6500L, 7000L))));
        assertEquals(0, price(market, new BidPriceEvidence.Quote("minecraft:bone_block", 64, NOW,
                List.of(6000L, 6500L, 7000L))));
    }

    @Test void waitingCannotTurnWeakHistoryIntoStrongHistory() {
        var weak = market(0.1, 100, 0, 0);
        assertFalse(BidPriceEvidence.refreshable(weak, 0.2, 15, NOW + 8 * 3_600_000L));
    }
}
