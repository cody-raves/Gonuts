package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Price span for DonutSMP's auction GUI, whose listing lore carries only an
 * abbreviated bare amount such as {@code $ 22K}.
 *
 * <p>The server truncates rather than rounds: a listing of 7,499 was observed
 * live displaying as {@code $ 7.4K}, which rounding cannot produce. A displayed
 * price therefore covers {@code [shown, shown + unit)}.
 *
 * <p>This only decides which slot may be clicked. What is paid is verified
 * against the confirmation screen at zero tolerance.
 */
class AutomatedExecutionDriverPriceTest {

    @Test
    void anUnabbreviatedPriceNamesTheCoin() {
        assertEquals(1, AutomatedExecutionDriver.displayedPriceUnit("361", null));
        assertEquals(1, AutomatedExecutionDriver.displayedPriceUnit("2000", ""));
    }

    @Test
    void wholeThousandsSpanAThousand() {
        // "$ 22K" covers 22,000 through 22,999.
        assertEquals(1_000, AutomatedExecutionDriver.displayedPriceUnit("22", "K"));
        assertEquals(1_000, AutomatedExecutionDriver.displayedPriceUnit("2", "k"));
    }

    @Test
    void oneDecimalNarrowsTheSpanTenfold() {
        // "$ 7.4K" covers 7,400 through 7,499 — the live case that exposed
        // truncation, since 7,499 would round to 7.5K.
        assertEquals(100, AutomatedExecutionDriver.displayedPriceUnit("7.4", "K"));
    }

    @Test
    void millionsAndBillionsScaleWithTheSuffix() {
        assertEquals(1_000_000, AutomatedExecutionDriver.displayedPriceUnit("2", "M"));
        assertEquals(100_000, AutomatedExecutionDriver.displayedPriceUnit("2.4", "M"));
        assertEquals(1_000_000_000, AutomatedExecutionDriver.displayedPriceUnit("1", "B"));
    }

    @Test
    void enoughDecimalsToNameTheCoinIsExactAgain() {
        assertEquals(1, AutomatedExecutionDriver.displayedPriceUnit("22.000", "K"));
    }

    @Test
    void anUnknownSuffixIsTreatedAsCoins() {
        assertEquals(1, AutomatedExecutionDriver.displayedPriceUnit("22", "x"));
    }
}
