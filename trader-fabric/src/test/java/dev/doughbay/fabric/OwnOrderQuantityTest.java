package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnOrderQuantityTest {
    @Test void exact1024IsRecognizedWithoutAThousandItemBlanketBan() {
        assertEquals(1024, OwnOrderQuantity.exact("1024").orElseThrow());
        assertEquals(1024, OwnOrderQuantity.exact("1,024").orElseThrow());
        assertEquals(1024, OwnOrderQuantity.exact("1.024K").orElseThrow());
        assertEquals(1024, OwnOrderQuantity.exact("1.0240k").orElseThrow());
        assertEquals(0, OwnOrderQuantity.exact("0").orElseThrow());
    }
    @Test void roundedRequestedAndDeliveredCountsCannotInvent1024OrCompletion() {
        for (String value : new String[]{"1K","1.0K","1.02K","1.2K","1.2M","1.000B"})
            assertTrue(OwnOrderQuantity.exact(value).isEmpty(), value);
        // Two equal rounded displays do not establish that all 1,024 items were delivered.
        assertTrue(OwnOrderQuantity.exact("1.0K").isEmpty());
    }
    @Test void rejectsMalformedOverflowAndNonIntegralValues() {
        for (String value : new String[]{"1,02","1,024K","1.5","-1024","2147483648","NaN","1e3","1 K",""})
            assertTrue(OwnOrderQuantity.exact(value).isEmpty(), value);
        assertTrue(OwnOrderQuantity.exact(null).isEmpty());
    }
}
