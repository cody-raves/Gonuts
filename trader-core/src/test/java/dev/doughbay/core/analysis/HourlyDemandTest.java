package dev.doughbay.core.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HourlyDemandTest {

    private static long atUtcHour(int hour) {
        // An epoch-millis instant whose UTC hour-of-day is `hour`.
        return hour * 3_600_000L;
    }

    @Test
    void boostsAMarketInItsBusyHour() {
        int[] hist = new int[24];
        for (int h = 0; h < 24; h++) hist[h] = 10;
        hist[20] = 40; // 8pm runs hot
        double f = HourlyDemand.factor(hist, atUtcHour(20), 0.5, 2.0, 24);
        assertTrue(f > 1.0, "busy hour should boost, got " + f);
    }

    @Test
    void demotesAMarketInItsDeadHour() {
        int[] hist = new int[24];
        for (int h = 0; h < 24; h++) hist[h] = 10;
        hist[4] = 1; // 4am is dead
        double f = HourlyDemand.factor(hist, atUtcHour(4), 0.5, 2.0, 24);
        assertTrue(f < 1.0, "dead hour should demote, got " + f);
    }

    @Test
    void clampsAFreakBusyHourToTheCeiling() {
        int[] hist = new int[24];
        hist[12] = 1000; // everything in one hour
        double f = HourlyDemand.factor(hist, atUtcHour(12), 0.5, 2.0, 24);
        assertEquals(2.0, f, 1e-9);
    }

    @Test
    void neutralWhenTooSparseToHaveAShape() {
        int[] hist = new int[24];
        hist[9] = 3; // only 3 sales total, below minTotal
        assertEquals(1.0, HourlyDemand.factor(hist, atUtcHour(9), 0.5, 2.0, 24), 1e-9);
    }

    @Test
    void neutralOnMissingOrMalformedHistogram() {
        assertEquals(1.0, HourlyDemand.factor(null, atUtcHour(0), 0.5, 2.0, 24), 1e-9);
        assertEquals(1.0, HourlyDemand.factor(new int[12], atUtcHour(0), 0.5, 2.0, 24), 1e-9);
        assertEquals(1.0, HourlyDemand.factor(new int[]{-1}, atUtcHour(0), 0.5, 2.0, 24), 1e-9);
    }

    @Test
    void aFlatMarketStaysNeutral() {
        int[] hist = new int[24];
        for (int h = 0; h < 24; h++) hist[h] = 10;
        assertEquals(1.0, HourlyDemand.factor(hist, atUtcHour(7), 0.5, 2.0, 24), 1e-9);
    }
}
