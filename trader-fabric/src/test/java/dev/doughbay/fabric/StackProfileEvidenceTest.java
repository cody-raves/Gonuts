package dev.doughbay.fabric;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StackProfileEvidenceTest {
    @Test void syntheticPricesAreNotEvidenceThatSinglesActuallySold() {
        assertTrue(StackProfile.completedSale("{\"price\":20000}"));
        assertTrue(StackProfile.completedSale("{\"source\":\"keyless-own\"}"));
        assertFalse(StackProfile.completedSale("{\"source\":\"keyless-orders\"}"));
        assertFalse(StackProfile.completedSale("{\"source\":\"keyless-asks\"}"));
        assertFalse(StackProfile.completedSale("{\"source\":\"unknown\"}"));
        assertFalse(StackProfile.completedSale("broken"));
        assertFalse(StackProfile.completedSale(null));
        assertFalse(StackProfile.completedSale("null"));
        assertFalse(StackProfile.completedSale("[]"));
    }
}
