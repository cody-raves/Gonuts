package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoughBayConfigPermissionPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void stringTrueCannotEnableAuthorizedExecution() {
        ObjectNode root = JSON.createObjectNode();
        root.put("authorizedExecutionEnabled", "true");
        root.putArray("authorizedServers").add("private.example.net");

        assertLocked(DoughBayConfig.parseCommandPermission(root,
                "authorizedExecutionEnabled", "authorizedServers"));
    }

    @Test
    void nonBooleanCannotEnablePreflight() {
        ObjectNode root = JSON.createObjectNode();
        root.put("preflightEnabled", 1);
        root.putArray("preflightServers").add("private.example.net");

        assertLocked(DoughBayConfig.parseCommandPermission(root,
                "preflightEnabled", "preflightServers"));
    }

    @Test
    void malformedCommandAllowlistEntryLocksPermissionAndClearsList() {
        ObjectNode root = JSON.createObjectNode();
        root.put("authorizedExecutionEnabled", true);
        root.putArray("authorizedServers")
                .add("private.example.net")
                .add(42);

        assertLocked(DoughBayConfig.parseCommandPermission(root,
                "authorizedExecutionEnabled", "authorizedServers"));
    }

    @Test
    void validCommandAllowlistIsNormalizedAndDeduplicated() {
        ObjectNode root = JSON.createObjectNode();
        root.put("authorizedExecutionEnabled", true);
        root.putArray("authorizedServers")
                .add("  Private.Example.NET:25565  ")
                .add("private.example.net:25565");

        DoughBayConfig.CommandPermission permission =
                DoughBayConfig.parseCommandPermission(root,
                        "authorizedExecutionEnabled", "authorizedServers");

        assertTrue(permission.valid());
        assertTrue(permission.enabled());
        assertEquals(List.of("private.example.net:25565"), permission.servers());
    }

    @Test
    void missingCommandFieldsRemainSafelyDisabled() {
        DoughBayConfig.CommandPermission permission =
                DoughBayConfig.parseCommandPermission(JSON.createObjectNode(),
                        "preflightEnabled", "preflightServers");

        assertTrue(permission.valid());
        assertFalse(permission.enabled());
        assertEquals(List.of(), permission.servers());
    }

    @Test
    void notificationsKeepOnlyValidNormalizedExactIdentities() {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("notificationServers")
                .add(" DonutSMP.NET ")
                .add("donutsmp.net")
                .add("*")
                .add(42)
                .add("https://donutsmp.net");

        assertEquals(List.of("donutsmp.net"),
                DoughBayConfig.parseNotificationServers(root));
    }

    @Test
    void malformedNotificationListShapeTrustsNobody() {
        ObjectNode root = JSON.createObjectNode();
        root.put("notificationServers", "donutsmp.net");

        assertEquals(List.of(), DoughBayConfig.parseNotificationServers(root));
    }

    @Test
    void missingNotificationListPreservesSafeDonutDefaults() {
        assertEquals(List.of("donutsmp.net", "play.donutsmp.net"),
                DoughBayConfig.parseNotificationServers(JSON.createObjectNode()));
    }

    private static void assertLocked(DoughBayConfig.CommandPermission permission) {
        assertFalse(permission.valid());
        assertFalse(permission.enabled());
        assertEquals(List.of(), permission.servers());
    }
}
