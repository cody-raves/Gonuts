package dev.doughbay.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class DonutSaleMessageParserTest {

    @Test
    void parsesObservedSaleMessage() {
        DonutSaleMessageParser.SaleNotice sale = requireSale(
                "RPM43551 bought your Ender Chest for $15K");

        assertEquals("RPM43551", sale.buyer());
        assertEquals("Ender Chest", sale.itemText());
        assertTrue(sale.count().isEmpty());
        assertEquals(15_000L, sale.price());
    }

    @Test
    void abbreviatedPricesCarryTheirBandAndBedrockNamesAreAccepted() {
        DonutSaleMessageParser.SaleNotice sale = requireSale(
                ".JAMESTHEB8312 bought your Ender Chest for $12.2K");
        assertEquals(".JAMESTHEB8312", sale.buyer());
        assertEquals(12_200L, sale.price());
        assertEquals(12_299L, sale.priceUpperBound());
        assertTrue(sale.couldBe(12_225));
        assertFalse(sale.couldBe(12_300));

        DonutSaleMessageParser.SaleNotice whole = requireSale(
                "MemoL1 bought your Stone Bricks for $9K");
        assertEquals(9_000L, whole.price());
        assertEquals(9_999L, whole.priceUpperBound());

        DonutSaleMessageParser.SaleNotice exact = requireSale(
                "Player123 bought your Diamond for $1,234,567");
        assertEquals(1_234_567L, exact.priceUpperBound());
    }

    @Test
    void extractsTrailingStackCount() {
        DonutSaleMessageParser.SaleNotice sale = requireSale(
                "Buyer_Name bought your Experience Bottle x64 for $325k");

        assertEquals("Experience Bottle", sale.itemText());
        assertEquals(64, sale.count().orElseThrow());
        assertEquals(325_000L, sale.price());
    }

    @Test
    void acceptsUnicodeCountMarkerAndLegacyFormatting() {
        DonutSaleMessageParser.SaleNotice sale = requireSale(
                "\u00a7aPlayer123 \u00a7fbought   your  Ender Pearl \u00d7 16 for \u00a7a$4.1K\u00a7r");

        assertEquals("Player123", sale.buyer());
        assertEquals("Ender Pearl", sale.itemText());
        assertEquals(16, sale.count().orElseThrow());
        assertEquals(4_100L, sale.price());
    }

    @Test
    void normalizesSupportedCurrencyFormatsExactly() {
        assertEquals(1_234_567L, requireSale(
                "Player123 bought your Diamond for $1,234,567").price());
        assertEquals(3_900_000L, requireSale(
                "Player123 bought your Obsidian for $3.9m").price());
        assertEquals(2_500_000_000L, requireSale(
                "Player123 bought your Redstone for $2.5B").price());
    }

    @Test
    void rejectsMessagesThatAreNotExactServerSaleShape() {
        assertRejected("[VIP] Player123 bought your Diamond for $15K");
        assertRejected("Player123 bought Diamond for $15K");
        assertRejected("Player123 bought your Diamond for 15K");
        assertRejected("Player123 bought your Diamond for $15K today");
        assertRejected("Player123 sold your Diamond for $15K");
        assertRejected("Player123: bought your Diamond for $15K");
        assertRejected("Player123 bought your Diamond\nfor $15K");
    }

    @Test
    void rejectsMalformedOrAmbiguousPrices() {
        assertRejected("Player123 bought your Diamond for $0");
        assertRejected("Player123 bought your Diamond for $-15K");
        assertRejected("Player123 bought your Diamond for $1,2K");
        assertRejected("Player123 bought your Diamond for $1.2345K");
        assertRejected("Player123 bought your Diamond for $15T");
        assertRejected("Player123 bought your Diamond for $$15K");
    }

    @Test
    void rejectsAmbiguousItemAndCountText() {
        assertRejected("Player123 bought your Box x0 for $15K");
        assertRejected("Player123 bought your Box x65 for $15K");
        assertRejected("Player123 bought your Item for $5 for $15K");
    }

    @Test
    void rejectsInvalidBuyerNamesAndNull() {
        assertRejected("ab bought your Diamond for $15K");
        assertRejected("Player-123 bought your Diamond for $15K");
        assertFalse(DonutSaleMessageParser.parse(null).isPresent());
    }

    private static DonutSaleMessageParser.SaleNotice requireSale(String text) {
        Optional<DonutSaleMessageParser.SaleNotice> parsed = DonutSaleMessageParser.parse(text);
        assertTrue(parsed.isPresent(), () -> "Expected sale message to parse: " + text);
        return parsed.orElseThrow();
    }

    private static void assertRejected(String text) {
        assertFalse(DonutSaleMessageParser.parse(text).isPresent(),
                () -> "Expected message to be rejected: " + text);
    }
}
