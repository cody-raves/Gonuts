package dev.doughbay.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemFingerprintTest {

    @Test
    void enchantmentOrderDoesNotChangeIdentity() {
        ItemFingerprint a = ItemFingerprint.builder("minecraft:diamond_sword")
                .enchantment("minecraft:sharpness", 5)
                .enchantment("minecraft:unbreaking", 3)
                .enchantment("minecraft:mending", 1)
                .build();
        ItemFingerprint b = ItemFingerprint.builder("minecraft:diamond_sword")
                .enchantment("minecraft:mending", 1)
                .enchantment("minecraft:unbreaking", 3)
                .enchantment("minecraft:sharpness", 5)
                .build();
        assertEquals(a.itemKey(), b.itemKey());
        assertEquals(a, b);
    }

    @Test
    void sameItemIdDifferentMetadataAreDifferentMarkets() {
        ItemFingerprint plain = ItemFingerprint.builder("minecraft:diamond_sword").build();
        ItemFingerprint enchanted = ItemFingerprint.builder("minecraft:diamond_sword")
                .enchantment("minecraft:sharpness", 5).build();
        ItemFingerprint named = ItemFingerprint.builder("minecraft:diamond_sword")
                .displayName("Collector Sword").build();
        ItemFingerprint lored = ItemFingerprint.builder("minecraft:diamond_sword")
                .lore("A custom lore line").build();

        assertNotEquals(plain.itemKey(), enchanted.itemKey());
        assertNotEquals(plain.itemKey(), named.itemKey());
        assertNotEquals(plain.itemKey(), lored.itemKey());
        assertNotEquals(enchanted.itemKey(), named.itemKey());
    }

    @Test
    void plainItemUsesBareIdAsKey() {
        ItemFingerprint plain = ItemFingerprint.builder("minecraft:ender_pearl").build();
        assertTrue(plain.isPlain());
        assertEquals("minecraft:ender_pearl", plain.itemKey());
    }

    @Test
    void namespaceIsNormalized() {
        assertEquals(ItemFingerprint.builder("ender_pearl").build().itemKey(),
                ItemFingerprint.builder("MINECRAFT:ENDER_PEARL").build().itemKey());
    }

    @Test
    void enchantedItemIsNotPlain() {
        ItemFingerprint fp = ItemFingerprint.builder("minecraft:diamond_sword")
                .enchantment("minecraft:sharpness", 5).build();
        assertFalse(fp.isPlain());
        assertTrue(fp.itemKey().startsWith("minecraft:diamond_sword#"));
    }

    @Test
    void equivalentJsonNamesAndLoreFormattingHaveTheSameIdentity() {
        ItemFingerprint first = ItemFingerprint.builder("diamond_sword")
                .displayName("{\"text\":\"Sword\",\"bold\":true}")
                .lore("Line one\r\n\u00a7ASecond")
                .build();
        ItemFingerprint second = ItemFingerprint.builder("minecraft:diamond_sword")
                .displayName("{ \"bold\": true, \"text\": \"Sword\" }")
                .lore("Line one\n\u00a7aSecond")
                .build();

        assertEquals(first, second);
        assertEquals(first.itemKey(), second.itemKey());
    }

    @Test
    void validatesEnchantmentsAndCanonicalizesTrimIds() {
        ItemFingerprint trim = ItemFingerprint.builder("diamond_chestplate")
                .trim("GOLD", "SENTRY")
                .build();
        assertEquals("minecraft:gold", trim.trimMaterial());
        assertEquals("minecraft:sentry", trim.trimPattern());

        assertThrows(IllegalArgumentException.class,
                () -> ItemFingerprint.builder("book").enchantment("sharpness", 0));
        assertThrows(IllegalArgumentException.class,
                () -> ItemFingerprint.builder("book").enchantment("sharpness", 256));
        assertThrows(IllegalArgumentException.class,
                () -> ItemFingerprint.builder("book").trim("gold", null));
        assertThrows(IllegalArgumentException.class,
                () -> ItemFingerprint.builder("book")
                        .enchantment("sharpness", 4)
                        .enchantment("sharpness", 5));
    }
}
