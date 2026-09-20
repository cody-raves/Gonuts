package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoughBayConfigContinuousPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void absentFieldsPreserveSafeDisabledDefaultsForOlderConfigs() {
        DoughBayConfig.ContinuousPolicy policy =
                DoughBayConfig.parseContinuousPolicy(JSON.createObjectNode());

        assertTrue(policy.valid());
        assertFalse(policy.enabled());
        assertEquals(List.of(), policy.servers());
        assertEquals(0L, policy.maxPurchasePrice());
        assertEquals(0L, policy.maxSessionSpend());
        assertEquals(1, policy.maxTradesPerSession());
        assertEquals(5_000L, policy.minimumProfit());
        assertEquals(12.0, policy.minimumRoiPercent());
        assertEquals(80.0, policy.minimumConfidencePercent());
        assertEquals(10, policy.cooldownSeconds());
        assertEquals(8, policy.reservedHotbarSlot());
        assertEquals(120, policy.maximumHoldMinutes());
    }

    @Test
    void enabledPolicyRequiresEveryExplicitSafetyField() {
        ObjectNode root = validEnabledPolicy();
        root.remove("continuousCooldownSeconds");

        assertLocked(DoughBayConfig.parseContinuousPolicy(root));
    }

    @Test
    void fullyExplicitEnabledPolicyParses() {
        DoughBayConfig.ContinuousPolicy policy =
                DoughBayConfig.parseContinuousPolicy(validEnabledPolicy());

        assertTrue(policy.valid());
        assertTrue(policy.enabled());
        assertEquals(List.of("private.example.net:25565"), policy.servers());
        assertEquals(100_000L, policy.maxPurchasePrice());
        assertEquals(250_000L, policy.maxSessionSpend());
        assertEquals(2, policy.maxTradesPerSession());
    }

    @Test
    void everyMalformedOrOutOfRangeRiskFieldLocksWholePolicy() {
        List<Consumer<ObjectNode>> invalidMutations = List.of(
                root -> root.put("continuousAutomationEnabled", "true"),
                root -> root.put("continuousAutomationServers", "private.example.net"),
                root -> root.withArray("continuousAutomationServers").set(0,
                        JSON.getNodeFactory().textNode("Private.example.net")),
                root -> root.put("continuousMaxPurchasePrice", -1),
                root -> root.set("continuousMaxPurchasePrice",
                        JSON.getNodeFactory().numberNode(new BigInteger("1000000000000001"))),
                root -> root.put("continuousMaxSessionSpend", 1.5),
                root -> root.put("continuousMaxTradesPerSession", 0),
                root -> root.put("continuousMinimumProfit", 0),
                root -> root.set("continuousMinimumRoiPercent", DoubleNode.valueOf(Double.NaN)),
                root -> root.put("continuousMinimumConfidencePercent", 101),
                root -> root.put("continuousCooldownSeconds", 0),
                root -> root.put("continuousReservedHotbarSlot", 9),
                root -> root.put("continuousMaximumHoldMinutes", 0));

        for (Consumer<ObjectNode> mutation : invalidMutations) {
            ObjectNode root = validEnabledPolicy();
            mutation.accept(root);
            assertLocked(DoughBayConfig.parseContinuousPolicy(root));
        }
    }

    @Test
    void enabledPolicyRejectsEmptyOrNonExactServerListsAndZeroCaps() {
        List<Consumer<ObjectNode>> invalidMutations = List.of(
                root -> root.set("continuousAutomationServers", JSON.createArrayNode()),
                root -> root.withArray("continuousAutomationServers").add("*"),
                root -> root.withArray("continuousAutomationServers").add(42),
                root -> root.withArray("continuousAutomationServers").add(" duplicate "),
                root -> root.put("continuousMaxPurchasePrice", 0),
                root -> root.put("continuousMaxSessionSpend", 0));

        for (Consumer<ObjectNode> mutation : invalidMutations) {
            ObjectNode root = validEnabledPolicy();
            mutation.accept(root);
            assertLocked(DoughBayConfig.parseContinuousPolicy(root));
        }
    }

    private static ObjectNode validEnabledPolicy() {
        ObjectNode root = JSON.createObjectNode();
        root.put("continuousAutomationEnabled", true);
        ArrayNode servers = root.putArray("continuousAutomationServers");
        servers.add("private.example.net:25565");
        root.put("continuousMaxPurchasePrice", 100_000L);
        root.put("continuousMaxSessionSpend", 250_000L);
        root.put("continuousMaxTradesPerSession", 2);
        root.put("continuousMinimumProfit", 5_000L);
        root.put("continuousMinimumRoiPercent", 12.0);
        root.put("continuousMinimumConfidencePercent", 80.0);
        root.put("continuousCooldownSeconds", 10);
        root.put("continuousReservedHotbarSlot", 8);
        root.put("continuousMaximumHoldMinutes", 120);
        return root;
    }

    private static void assertLocked(DoughBayConfig.ContinuousPolicy policy) {
        assertFalse(policy.valid());
        assertFalse(policy.enabled());
        assertEquals(List.of(), policy.servers());
        assertEquals(0L, policy.maxPurchasePrice());
        assertEquals(0L, policy.maxSessionSpend());
        assertEquals(0, policy.maxTradesPerSession());
        assertEquals(0L, policy.minimumProfit());
        assertEquals(0.0, policy.minimumRoiPercent());
        assertEquals(0.0, policy.minimumConfidencePercent());
        assertEquals(0, policy.cooldownSeconds());
        assertEquals(0, policy.reservedHotbarSlot());
        assertEquals(0, policy.maximumHoldMinutes());
    }
}
