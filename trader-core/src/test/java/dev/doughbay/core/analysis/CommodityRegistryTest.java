package dev.doughbay.core.analysis;

import dev.doughbay.core.model.ItemFingerprint;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommodityRegistryTest {

    @Test
    void focusedPocUniverseIsAValidatedSubsetOfSupportedCommodities() {
        assertFalse(CommodityRegistry.DEFAULT_FOCUSED_COMMODITIES.isEmpty());
        assertTrue(CommodityRegistry.DEFAULT_COMMODITIES.containsAll(
                CommodityRegistry.DEFAULT_FOCUSED_COMMODITIES));
        CommodityRegistry focused = CommodityRegistry.focusedDefaults();
        assertEquals(CommodityRegistry.DEFAULT_FOCUSED_COMMODITIES,
                focused.trackedIds());
    }

    @Test
    void normalizesConfiguredIdsAndAllowsOnlyPlainTrackedItems() {
        CommodityRegistry registry = new CommodityRegistry(Set.of("ENDER_PEARL", "DONUT:TOKEN"));

        assertEquals(Set.of("minecraft:ender_pearl", "donut:token"), registry.trackedIds());
        assertTrue(registry.isEligible(ItemFingerprint.builder("ender_pearl").build()));
        assertFalse(registry.isEligible(ItemFingerprint.builder("ender_pearl")
                .displayName("Special").build()));
        assertFalse(registry.isEligible(ItemFingerprint.builder("diamond").build()));
    }

    @Test
    void aDescriptorMarketIsPricedByItsKitRatherThanRejectedForHavingOne() {
        CommodityRegistry registry = new CommodityRegistry(
                Set.of("netherite_chestplate", "diamond_sword"), Set.of(),
                Set.of("NETHERITE_CHESTPLATE"));

        assertTrue(registry.pricedByDescriptor("minecraft:netherite_chestplate"));
        assertFalse(registry.pricedByDescriptor("minecraft:diamond_sword"));

        ItemFingerprint kitted = ItemFingerprint.builder("netherite_chestplate")
                .enchantment("minecraft:protection", 4)
                .enchantment("minecraft:mending", 1)
                .trim("minecraft:quartz", "minecraft:silence")
                .build();
        // Enchantments, trim and being wearable gear are the identity here.
        assertTrue(registry.isEligible(kitted));
        // Each kit is its own market, so the key has to tell them apart.
        assertNotEquals(kitted.itemKey(),
                ItemFingerprint.builder("netherite_chestplate")
                        .enchantment("minecraft:protection", 4).build().itemKey());

        // The same make-up on an item nobody listed stays out.
        assertFalse(registry.isEligible(ItemFingerprint.builder("diamond_sword")
                .enchantment("minecraft:sharpness", 5).build()));
        // Lore is still unknowable whatever the market.
        assertFalse(registry.isEligible(ItemFingerprint.builder("netherite_chestplate")
                .lore("soulbound").build()));
    }

    @Test
    void reportsAllReasonsInsteadOfSilentlyMixingUnsafeMarkets() {
        CommodityRegistry registry = new CommodityRegistry(Set.of("diamond_sword"));
        CommodityRegistry.Assessment assessment = registry.assess(
                ItemFingerprint.builder("diamond_sword")
                        .displayName("Collector Sword")
                        .enchantment("sharpness", 5)
                        .lore("One of one")
                        .build());

        assertFalse(assessment.eligible());
        assertTrue(assessment.rejectionReasons().contains(
                CommodityRegistry.RejectionReason.DAMAGEABLE_GEAR));
        assertTrue(assessment.rejectionReasons().contains(
                CommodityRegistry.RejectionReason.CUSTOM_NAME));
        assertTrue(assessment.rejectionReasons().contains(
                CommodityRegistry.RejectionReason.ENCHANTMENTS));
        assertTrue(assessment.rejectionReasons().contains(
                CommodityRegistry.RejectionReason.LORE));
    }

    @Test
    void unsafeCategoriesRemainRejectedEvenWhenConfiguredAsTracked() {
        CommodityRegistry registry = new CommodityRegistry(Set.of(
                "potion", "written_book", "blue_shulker_box", "diamond_pickaxe"));

        assertFalse(registry.isEligible(ItemFingerprint.builder("potion").build()));
        assertFalse(registry.isEligible(ItemFingerprint.builder("written_book").build()));
        assertFalse(registry.isEligible(ItemFingerprint.builder("blue_shulker_box").build()));
        assertFalse(registry.isEligible(ItemFingerprint.builder("diamond_pickaxe").build()));
    }

    @Test
    void blankMapIsFungibleWhileMapArtIsNot() {
        // minecraft:map is paper and a compass, stackable, with no NBT until
        // used. minecraft:filled_map holds unique art, so two of them are never
        // interchangeable no matter how identical the fingerprints look.
        CommodityRegistry registry = new CommodityRegistry(
                Set.of("minecraft:map", "minecraft:filled_map"));

        assertTrue(registry.isEligible(
                ItemFingerprint.builder("minecraft:map").build()));
        assertFalse(registry.isEligible(
                ItemFingerprint.builder("minecraft:filled_map").build()));
    }

    @Test
    void aBlankMapCarryingMetadataIsStillRejected() {
        // Defence in depth: relaxing the id class must not let a named or
        // otherwise annotated map through as a plain commodity.
        CommodityRegistry registry = new CommodityRegistry(Set.of("minecraft:map"));

        assertFalse(registry.isEligible(ItemFingerprint.builder("minecraft:map")
                .displayName("Spawn Art")
                .build()));
    }

    @Test
    void explicitDenyListWinsAndMalformedConfigurationFailsFast() {
        CommodityRegistry registry = new CommodityRegistry(Set.of("ender_pearl"), Set.of("ender_pearl"));
        CommodityRegistry.Assessment result = registry.assess(
                ItemFingerprint.builder("ender_pearl").build());
        assertTrue(result.rejectionReasons().contains(
                CommodityRegistry.RejectionReason.EXPLICITLY_REJECTED));

        assertThrows(IllegalArgumentException.class,
                () -> new CommodityRegistry(Set.of("not a valid id")));
    }
}
