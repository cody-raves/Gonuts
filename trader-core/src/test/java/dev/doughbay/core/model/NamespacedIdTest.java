package dev.doughbay.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespacedIdTest {

    @Test
    void addsMinecraftNamespaceAndNormalizesCase() {
        assertEquals("minecraft:ender_pearl", NamespacedId.normalize(" ENDER_PEARL "));
        assertEquals("donut:item/path", NamespacedId.normalize("DONUT:ITEM/PATH"));
    }

    @Test
    void rejectsMalformedResourceLocations() {
        for (String malformed : new String[]{"", ":stone", "minecraft:", "a:b:c",
                "mine craft:stone", "minecraft:stone block", "minecraft:#stone"}) {
            assertFalse(NamespacedId.isValid(malformed), malformed);
            assertThrows(IllegalArgumentException.class, () -> NamespacedId.normalize(malformed));
        }
        assertTrue(NamespacedId.isValid("minecraft:oak_log"));
    }
}
