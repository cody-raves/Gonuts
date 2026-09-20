package dev.doughbay.fabric;

import dev.doughbay.core.model.Listing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestBuyPickerTest {

    @Test
    void shortNamesGetTheMinecraftNamespace() {
        assertEquals("minecraft:dirt", TestBuyPicker.normalizeItemId(" Dirt "));
        assertEquals("minecraft:oak_log", TestBuyPicker.normalizeItemId("minecraft:oak_log"));
        assertEquals("", TestBuyPicker.normalizeItemId("dirt; drop table"));
        assertEquals("", TestBuyPicker.normalizeItemId(""));
    }

    @Test
    void picksTheCheapestQualifyingListing() {
        List<Listing> book = List.of(
                listing("a", "minecraft:dirt", 1, 40, "alice"),
                listing("b", "minecraft:dirt", 1, 20, "bob"),
                listing("c", "minecraft:dirt", 64, 15, ""),          // no seller: unusable
                listing("d", "minecraft:stone", 1, 5, "dan"),        // wrong item
                listing("e", "minecraft:dirt", 2, 25, "erin"));
        Listing pick = TestBuyPicker.cheapestAtOrUnder(book, "minecraft:dirt", 30).orElseThrow();
        assertEquals("b", pick.listingKey());
        assertTrue(TestBuyPicker.cheapestAtOrUnder(book, "minecraft:dirt", 10).isEmpty());
    }

    @Test
    void theManualOpportunityMatchesTheListingPrice() {
        Listing pick = listing("b", "minecraft:dirt", 1, 20, "bob");
        var opportunity = TestBuyPicker.asManualOpportunity(pick, List.of(), 5_000);
        assertEquals(20, opportunity.buyPrice());
        assertEquals(20.0, opportunity.stats().weightedMedian());
        assertEquals(pick, opportunity.listing());
    }

    private static Listing listing(String key, String item, int count, long price, String seller) {
        return new Listing(key, 1_000, "01234567-89ab-4def-8abc-0123456789ab", seller,
                item, item, count, price, 60_000L);
    }
}
