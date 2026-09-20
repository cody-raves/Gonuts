package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JackpotTest {

    @Test
    void fiveQuickSinglesAreAJackpotAndTenAreAnotherCall() {
        Jackpot jackpot = new Jackpot();
        long t = 1_000_000L;
        for (int i = 1; i <= 4; i++) assertNull(jackpot.onSingleSold("skull", 26_000, t += 20_000, 5));
        Jackpot.Hit five = jackpot.onSingleSold("skull", 26_000, t += 20_000, 5);
        assertNotNull(five);
        assertEquals(5, five.sold());
        assertEquals(130_000, five.profit());
        for (int i = 6; i <= 9; i++) assertNull(jackpot.onSingleSold("skull", 26_000, t += 20_000, 5));
        assertEquals(10, jackpot.onSingleSold("skull", 26_000, t += 20_000, 5).sold());
    }

    @Test
    void aLongPauseEndsTheStreak() {
        Jackpot jackpot = new Jackpot();
        long t = 0;
        for (int i = 1; i <= 4; i++) jackpot.onSingleSold("skull", 1, t += 10_000, 5);
        // Past the gap: this sale starts a new run of one, not the fifth.
        assertNull(jackpot.onSingleSold("skull", 1, t + Jackpot.GAP_MILLIS + 1, 5));
    }

    @Test
    void itemsKeepTheirOwnStreaks() {
        Jackpot jackpot = new Jackpot();
        long t = 0;
        for (int i = 1; i <= 4; i++) {
            jackpot.onSingleSold("skull", 1, t += 10_000, 5);
            jackpot.onSingleSold("hopper", 1, t += 10_000, 5);
        }
        assertEquals("skull", jackpot.onSingleSold("skull", 1, t += 10_000, 5).item());
    }

    @Test
    void zeroTurnsItOff() {
        Jackpot jackpot = new Jackpot();
        for (int i = 1; i <= 20; i++) assertNull(jackpot.onSingleSold("skull", 1, i * 1_000L, 0));
    }
}
