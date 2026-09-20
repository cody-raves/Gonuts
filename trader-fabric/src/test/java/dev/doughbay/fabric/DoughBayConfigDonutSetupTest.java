package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-step DonutSMP setup: a fresh install arrives ready but switched off,
 * and the Autopilot card's Enable writes a config that every startup gate
 * accepts. Each case runs the written file back through the same parsers
 * {@link DoughBayConfig#load()} uses, so "saved" and "actually unlocked" can't
 * drift apart.
 */
final class DoughBayConfigDonutSetupTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private JsonNode written() throws IOException {
        return JSON.readTree(Files.readString(dir.resolve("config.json")));
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) if (value.equals(n.asText())) return true;
        return false;
    }

    @Test
    void shippedTemplateIsReadyButSwitchedOff() throws IOException {
        JsonNode root = JSON.readTree(DoughBayConfig.DEFAULT_CONFIG_JSON);

        DoughBayConfig.ContinuousPolicy continuous = DoughBayConfig.parseContinuousPolicy(root);
        assertTrue(continuous.valid());
        assertFalse(continuous.enabled());
        assertEquals(DoughBayConfig.DONUT_SERVERS, continuous.servers());
        assertEquals(5_000_000L, continuous.maxPurchasePrice());
        assertEquals(75_000_000L, continuous.maxSessionSpend());
        assertEquals(50_000, continuous.maxTradesPerSession());

        DoughBayConfig.CommandPermission authorized = DoughBayConfig.parseCommandPermission(
                root, "authorizedExecutionEnabled", "authorizedServers");
        assertTrue(authorized.valid());
        assertFalse(authorized.enabled());

        DoughBayConfig.FeePolicy fees = DoughBayConfig.parseFeePolicy(root);
        assertTrue(fees.valid());
        assertTrue(fees.confirmed());
    }

    @Test
    void enablingAFreshInstallOpensEveryGate() throws IOException {
        DoughBayConfig.saveDonutAutomation(dir, true, 5_000_000L, 75_000_000L, "donutsmp.net");
        JsonNode root = written();

        DoughBayConfig.ContinuousPolicy continuous = DoughBayConfig.parseContinuousPolicy(root);
        assertTrue(continuous.valid());
        assertTrue(continuous.enabled());
        assertTrue(continuous.servers().contains("donutsmp.net"));

        DoughBayConfig.CommandPermission authorized = DoughBayConfig.parseCommandPermission(
                root, "authorizedExecutionEnabled", "authorizedServers");
        assertTrue(authorized.valid());
        assertTrue(authorized.enabled());
        assertTrue(authorized.servers().contains("donutsmp.net"));

        assertTrue(DoughBayConfig.parseFeePolicy(root).confirmed());
        assertTrue(DoughBayConfig.parseNotificationServers(root).contains("donutsmp.net"));
    }

    @Test
    void enablingKeepsChosenValuesAndUpgradesTheOldOneTradeDefaults() throws IOException {
        // An install from before this change: the old template's filters, one
        // value the owner changed, and settings the switch must not touch.
        Files.writeString(dir.resolve("config.json"), """
                {
                  "apiBaseUrl": "https://example.test",
                  "customKey": 42,
                  "continuousMaxTradesPerSession": 1,
                  "continuousMinimumProfit": 7777,
                  "continuousMinimumRoiPercent": 12,
                  "continuousMinimumConfidencePercent": 80,
                  "continuousCooldownSeconds": 10,
                  "continuousReservedHotbarSlot": 8,
                  "continuousMaximumHoldMinutes": 120
                }
                """);

        DoughBayConfig.saveDonutAutomation(dir, true, 1_000_000L, 10_000_000L, null);
        JsonNode root = written();

        assertEquals(50_000, root.get("continuousMaxTradesPerSession").asInt());
        assertEquals(7_777L, root.get("continuousMinimumProfit").asLong(), "owner's value kept");
        assertEquals(2, root.get("continuousMinimumRoiPercent").asInt());
        assertEquals(10, root.get("continuousMinimumConfidencePercent").asInt());
        assertEquals(2, root.get("continuousCooldownSeconds").asInt());
        assertEquals(240, root.get("continuousMaximumHoldMinutes").asInt());
        assertEquals(1_000_000L, root.get("continuousMaxPurchasePrice").asLong());
        assertEquals(10_000_000L, root.get("continuousMaxSessionSpend").asLong());
        assertEquals("https://example.test", root.get("apiBaseUrl").asText());
        assertEquals(42, root.get("customKey").asInt());
        assertTrue(DoughBayConfig.parseContinuousPolicy(root).enabled());
    }

    @Test
    void theJoinedDonutAddressIsAllowlistedButNoOtherServerIs() throws IOException {
        DoughBayConfig.saveDonutAutomation(dir, true, 5_000_000L, 75_000_000L, "DonutSMP.net:25565");
        assertTrue(contains(written().get("authorizedServers"), "donutsmp.net:25565"));

        Path other = dir.resolve("other");
        DoughBayConfig.saveDonutAutomation(other, true, 5_000_000L, 75_000_000L, "hypixel.net");
        JsonNode root = JSON.readTree(Files.readString(other.resolve("config.json")));
        assertFalse(contains(root.get("authorizedServers"), "hypixel.net"));
        assertFalse(contains(root.get("continuousAutomationServers"), "hypixel.net"));
    }

    @Test
    void turningOffClearsOnlyTheTwoSwitches() throws IOException {
        DoughBayConfig.saveDonutAutomation(dir, true, 5_000_000L, 75_000_000L, "donutsmp.net");
        DoughBayConfig.saveDonutAutomation(dir, false, 0, 0, null);
        JsonNode root = written();

        assertFalse(root.get("authorizedExecutionEnabled").asBoolean());
        assertFalse(root.get("continuousAutomationEnabled").asBoolean());
        assertEquals(5_000_000L, root.get("continuousMaxPurchasePrice").asLong());
        assertEquals(75_000_000L, root.get("continuousMaxSessionSpend").asLong());
        assertTrue(contains(root.get("authorizedServers"), "donutsmp.net"));
        assertFalse(DoughBayConfig.parseContinuousPolicy(root).enabled());
    }

    @Test
    void badSpendLimitsAreRefusedBeforeAnythingIsWritten() {
        assertThrows(IllegalArgumentException.class,
                () -> DoughBayConfig.saveDonutAutomation(dir, true, 0, 75_000_000L, null));
        assertThrows(IllegalArgumentException.class,
                () -> DoughBayConfig.saveDonutAutomation(dir, true, 10_000_000L, 5_000_000L, null));
        assertFalse(Files.exists(dir.resolve("config.json")));
    }
}
