package dev.doughbay.fabric;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OwnedListingIdentityTest {
    private ItemDescriptor gear(String wear, Map<String, Integer> enchants) {
        return new ItemDescriptor("minecraft:diamond_leggings", 1, enchants,
                "", List.of(), "", false, wear, List.of());
    }

    @Test
    void undamagedPlainGearKeepsItsPlainKey() {
        assertEquals("minecraft:diamond_leggings", gear("", Map.of()).ownedListingKey(false));
    }

    @Test
    void evenDamageTooSmallForAPricingWearBandUsesDescriptorVerification() {
        var nearNew = gear("", Map.of());
        assertFalse(nearNew.hasParts());
        assertEquals(nearNew.itemId() + "#" + nearNew.hash(), nearNew.ownedListingKey(true));
        var worn = gear("50", Map.of());
        assertEquals(worn.itemId() + "#" + worn.hash(), worn.ownedListingKey(true));
        assertNotEquals(nearNew.ownedListingKey(true), worn.ownedListingKey(true));
    }

    @Test
    void enchantedGearRetainsItsExistingDescriptorKey() {
        var enchanted = gear("", Map.of("minecraft:protection", 4));
        assertEquals(enchanted.itemId() + "#" + enchanted.hash(), enchanted.ownedListingKey(false));
    }
}
