package dev.doughbay.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DonutApiConfigTest {

    @Test
    void defaultsUseTheInvariantInternalCeilings() {
        DonutApiConfig config = DonutApiConfig.withDefaults("unit-test-key");

        assertEquals(DonutApiConfig.MAX_TARGET_REQUESTS_PER_MINUTE,
                config.targetRequestsPerMinute());
        assertEquals(DonutApiConfig.MAX_HARD_REQUESTS_PER_MINUTE,
                config.hardMaxRequestsPerMinute());
    }

    @Test
    void configurationCannotRaiseEitherRequestCeiling() {
        assertThrows(IllegalArgumentException.class, () -> new DonutApiConfig(
                DonutApiConfig.DEFAULT_BASE_URL, "unit-test-key",
                DonutApiConfig.MAX_TARGET_REQUESTS_PER_MINUTE + 1,
                DonutApiConfig.MAX_HARD_REQUESTS_PER_MINUTE, 20));
        assertThrows(IllegalArgumentException.class, () -> new DonutApiConfig(
                DonutApiConfig.DEFAULT_BASE_URL, "unit-test-key",
                DonutApiConfig.MAX_TARGET_REQUESTS_PER_MINUTE,
                DonutApiConfig.MAX_HARD_REQUESTS_PER_MINUTE + 1, 20));
    }

    @Test
    void configurationMayChooseAConservativelyLowerBudget() {
        assertDoesNotThrow(() -> new DonutApiConfig(
                DonutApiConfig.DEFAULT_BASE_URL, "unit-test-key", 60, 120, 20));
    }
}
