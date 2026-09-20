package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BidSizerTest {

    private static final BidSizer.Track FAST_AND_PROFITABLE = new BidSizer.Track(150, 148, 12);

    @Test
    void aDeepFastHighMarginMarketGetsTheFullOrder() {
        // Empty maps: a quarter margin, stacks gone in minutes, hundreds a day.
        assertEquals(20, BidSizer.stacks(20, 80, 0.25, FAST_AND_PROFITABLE));
    }

    @Test
    void aThinMarginStaysSmall() {
        // Golden apples at 5%: barely above the floor.
        assertEquals(3, BidSizer.stacks(20, 80, 0.05, FAST_AND_PROFITABLE));
        assertEquals(1, BidSizer.stacks(20, 80, 0.02, FAST_AND_PROFITABLE));
    }

    @Test
    void theMarketsVolumeCapsTheOrder() {
        assertEquals(4, BidSizer.stacks(20, 4, 0.30, FAST_AND_PROFITABLE));
    }

    @Test
    void anUnprovenItemIsHeldToThree() {
        assertEquals(3, BidSizer.stacks(20, 80, 0.30, null));
        assertEquals(3, BidSizer.stacks(20, 80, 0.30, new BidSizer.Track(2, 2, 5)));
        assertEquals(3, BidSizer.stacks(20, 0, 0.30, FAST_AND_PROFITABLE));   // volume unknown
    }

    @Test
    void stacksThatSatOrLostShrinkTheNextOrder() {
        assertEquals(12, BidSizer.stacks(20, 80, 0.25, new BidSizer.Track(50, 50, 30)));
        assertEquals(6, BidSizer.stacks(20, 80, 0.25, new BidSizer.Track(50, 50, 90)));
        assertEquals(10, BidSizer.stacks(20, 80, 0.25, new BidSizer.Track(50, 20, 10)));
    }

    @Test
    void neverBelowOneStack() {
        assertEquals(1, BidSizer.stacks(20, 80, 0.04, new BidSizer.Track(50, 10, 200)));
        assertEquals(1, BidSizer.stacks(0, 80, 0.25, FAST_AND_PROFITABLE));
    }
}
