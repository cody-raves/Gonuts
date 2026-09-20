package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MoneyInputTest {

    @Test
    void readsTheWaysPlayersWriteMoney() {
        assertEquals(5_000_000L, MoneyInput.parse("5m"));
        assertEquals(750_000L, MoneyInput.parse("750k"));
        assertEquals(1_500_000_000L, MoneyInput.parse("1.5b"));
        assertEquals(75_000_000L, MoneyInput.parse("$75,000,000"));
        assertEquals(5_000_000L, MoneyInput.parse("  5 M "));
        assertEquals(500_000L, MoneyInput.parse(".5m"));
        assertEquals(1_234L, MoneyInput.parse("1234"));
    }

    @Test
    void refusesAnythingThatIsNotACleanPositiveAmount() {
        // These become spend limits, so a guess is worse than a refusal.
        assertEquals(-1L, MoneyInput.parse(null));
        assertEquals(-1L, MoneyInput.parse(""));
        assertEquals(-1L, MoneyInput.parse("0"));
        assertEquals(-1L, MoneyInput.parse("-5m"));
        assertEquals(-1L, MoneyInput.parse("abc"));
        assertEquals(-1L, MoneyInput.parse("5mm"));
        assertEquals(-1L, MoneyInput.parse("1e5"));
        assertEquals(-1L, MoneyInput.parse("m"));
    }

    @Test
    void compactsRoundAmountsAndRoundTrips() {
        assertEquals("5m", MoneyInput.compact(5_000_000L));
        assertEquals("75m", MoneyInput.compact(75_000_000L));
        assertEquals("750k", MoneyInput.compact(750_000L));
        assertEquals("2b", MoneyInput.compact(2_000_000_000L));
        assertEquals("12345", MoneyInput.compact(12_345L));
        for (long v : new long[] {5_000_000L, 750_000L, 1_500_000_000L, 12_345L, 75_000_000L}) {
            assertEquals(v, MoneyInput.parse(MoneyInput.compact(v)));
        }
    }
}
