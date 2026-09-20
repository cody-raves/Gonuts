package dev.doughbay.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void oversizedRequestBudgetsCannotBeEnabledThroughJson() throws Exception {
        // Written against the caps themselves rather than the numbers they
        // happened to hold. The point of this test is that configuration
        // cannot raise the ceiling, and it should keep saying so when the
        // ceiling is retuned rather than failing because it was.
        int target = dev.doughbay.api.DonutApiConfig.MAX_TARGET_REQUESTS_PER_MINUTE;
        int hard = dev.doughbay.api.DonutApiConfig.MAX_HARD_REQUESTS_PER_MINUTE;

        Path targetTooHigh = temporaryDirectory.resolve("target-too-high.json");
        Files.writeString(targetTooHigh, """
                {"apiKey":"unit-test-key",
                 "targetRequestsPerMinute":%d,
                 "hardMaxRequestsPerMinute":%d}
                """.formatted(target + 1, hard));
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.load(targetTooHigh));

        Path hardTooHigh = temporaryDirectory.resolve("hard-too-high.json");
        Files.writeString(hardTooHigh, """
                {"apiKey":"unit-test-key",
                 "targetRequestsPerMinute":%d,
                 "hardMaxRequestsPerMinute":%d}
                """.formatted(target, hard + 1));
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.load(hardTooHigh));
    }

    @Test
    void lowerConfiguredBudgetRemainsAvailable() throws Exception {
        Path configFile = temporaryDirectory.resolve("lower-budget.json");
        Files.writeString(configFile, """
                {"apiKey":"unit-test-key",
                 "targetRequestsPerMinute":60,
                 "hardMaxRequestsPerMinute":120}
                """);

        AppConfig config = AppConfig.load(configFile);
        assertEquals(60, config.api().targetRequestsPerMinute());
        assertEquals(120, config.api().hardMaxRequestsPerMinute());
    }
}
