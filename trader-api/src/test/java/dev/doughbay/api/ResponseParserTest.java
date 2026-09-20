package dev.doughbay.api;

import com.fasterxml.jackson.databind.JsonNode;
import dev.doughbay.api.ResponseParser.ParseResult;
import dev.doughbay.api.ResponseParser.ParsedListing;
import dev.doughbay.api.ResponseParser.ParsedSale;
import dev.doughbay.core.analysis.CommodityRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recorded-fixture style tests: one healthy payload and a page of corrupted
 * records, verifying the collector degrades gracefully instead of crashing.
 */
class ResponseParserTest {

    private final ResponseParser parser = new ResponseParser();

    private static final String HEALTHY_PAGE = """
            {"status":200,"result":[
              {"unixMillisDateSold":1700000000000,
               "price":9340,
               "seller":{"uuid":"abc-123","name":"SellerOne"},
               "item":{"id":"minecraft:ender_pearl","count":16}},
              {"unixMillisDateSold":1700000060000,
               "price":9500,
               "seller":{"uuid":"def-456","name":"SellerTwo"},
               "item":{"id":"minecraft:ender_pearl","count":16,
                       "enchants":{"minecraft:unbreaking":3}}}
            ]}""";

    private static final String CORRUPTED_PAGE = """
            {"status":200,"result":[
              {"unixMillisDateSold":1700000000000,"price":0,
               "seller":{"uuid":"a"},"item":{"id":"minecraft:diamond","count":1}},
              {"unixMillisDateSold":1700000000000,"price":-500,
               "seller":{"uuid":"a"},"item":{"id":"minecraft:diamond","count":1}},
              {"unixMillisDateSold":1700000000000,"price":100,
               "seller":{"uuid":"a"},"item":{"id":"minecraft:diamond","count":0}},
              {"unixMillisDateSold":1700000000000,"price":100,
               "seller":{"uuid":"a"}},
              {"price":100,"seller":{"uuid":"a"},
               "item":{"id":"minecraft:diamond","count":1}},
              {"unixMillisDateSold":1700000099000,"price":4200,
               "seller":{"uuid":"good","name":"Fine"},
               "item":{"id":"minecraft:diamond","count":1}}
            ]}""";

    @Test
    void healthyTransactionsParse() throws Exception {
        JsonNode result = parser.extractResultArray(parser.readTree(HEALTHY_PAGE));
        ParseResult<ParsedSale> parsed = parser.parseTransactions(result);
        assertEquals(2, parsed.records().size());
        assertTrue(parsed.failures().isEmpty());

        ParsedSale plain = parsed.records().get(0);
        assertEquals("minecraft:ender_pearl", plain.sale().itemKey());
        assertEquals(16, plain.sale().itemCount());
        assertEquals(9340, plain.sale().totalPrice());
        assertEquals(9340.0 / 16, plain.sale().unitPrice(), 1e-9);

        // The enchanted pearl is a different market from the plain one.
        ParsedSale enchanted = parsed.records().get(1);
        assertTrue(!enchanted.sale().itemKey().equals(plain.sale().itemKey()));
    }

    @Test
    void corruptedRecordsFailIndividuallyWithoutCrashing() throws Exception {
        JsonNode result = parser.extractResultArray(parser.readTree(CORRUPTED_PAGE));
        ParseResult<ParsedSale> parsed = parser.parseTransactions(result);
        // Only the final well-formed record survives.
        assertEquals(1, parsed.records().size());
        assertEquals(5, parsed.failures().size());
        assertEquals(4200, parsed.records().get(0).sale().totalPrice());
    }

    @Test
    void duplicateTransactionsHashIdentically() throws Exception {
        JsonNode result1 = parser.extractResultArray(parser.readTree(HEALTHY_PAGE));
        JsonNode result2 = parser.extractResultArray(parser.readTree(HEALTHY_PAGE));
        String hash1 = parser.parseTransactions(result1).records().get(0).sale().transactionHash();
        String hash2 = parser.parseTransactions(result2).records().get(0).sale().transactionHash();
        assertEquals(hash1, hash2);
    }

    @Test
    void nonJsonBodyIsRejected() {
        assertThrows(ApiException.class, () -> parser.readTree("<html>maintenance</html>"));
    }

    @Test
    void missingResultArrayIsRejected() throws Exception {
        JsonNode root = parser.readTree("{\"status\":200}");
        assertThrows(ApiException.class, () -> parser.extractResultArray(root));
    }

    @Test
    void listingsParseWithDerivedListingKey() throws Exception {
        String page = """
                {"status":200,"result":[
                  {"price":4100,
                   "seller":{"uuid":"s-1","name":"S"},
                   "time_left":3600000,
                   "item":{"id":"minecraft:ender_pearl","count":16}}
                ]}""";
        JsonNode result = parser.extractResultArray(parser.readTree(page));
        var parsed = parser.parseListings(result, 1_700_000_000_000L);
        assertEquals(1, parsed.records().size());
        var listing = parsed.records().get(0).listing();
        assertEquals(4100, listing.totalPrice());
        assertEquals(3600000L, listing.timeLeftMillis());
        assertTrue(!listing.listingKey().isBlank());
    }

    @Test
    void unknownItemFieldsAreCanonicalIdentityMetadataAndRejectCommodityMatching()
            throws Exception {
        String firstPage = """
                {"status":200,"result":[
                  {"price":4100,"seller":{"uuid":"s-1","name":"SellerOne"},
                   "item":{"id":"minecraft:diamond","count":1,
                           "components":{"z":2.00,"a":1},"future_flag":true}}
                ]}""";
        String reorderedPage = """
                {"status":200,"result":[
                  {"price":4100,"seller":{"uuid":"s-1","name":"SellerOne"},
                   "item":{"future_flag":true,"count":1,"id":"minecraft:diamond",
                           "components":{"a":1.0,"z":2}}}
                ]}""";

        var first = parser.parseListings(
                parser.extractResultArray(parser.readTree(firstPage)), 1_700_000_000_000L)
                .records().getFirst();
        var reordered = parser.parseListings(
                parser.extractResultArray(parser.readTree(reorderedPage)), 1_700_000_000_000L)
                .records().getFirst();

        assertFalse(first.fingerprint().extraMetadataHash().isBlank());
        assertEquals(first.fingerprint().extraMetadataHash(),
                reordered.fingerprint().extraMetadataHash());
        assertEquals(first.fingerprint().itemKey(), reordered.fingerprint().itemKey());
        assertNotEquals("minecraft:diamond", first.fingerprint().itemKey());

        CommodityRegistry.Assessment assessment =
                CommodityRegistry.defaults().assess(first.fingerprint());
        assertFalse(assessment.eligible());
        assertTrue(assessment.rejectionReasons().contains(
                CommodityRegistry.RejectionReason.UNKNOWN_METADATA));
    }

    @Test
    void surroundingListingAndTransportFieldsDoNotBecomeItemMetadata() throws Exception {
        String page = """
                {"status":200,"result":[
                  {"id":"listing-123","price":4100,"time_left":3600000,
                   "transport_cursor":"opaque",
                   "seller":{"uuid":"s-1","name":"SellerOne","rank":"VIP"},
                   "item":{"id":"minecraft:diamond","count":1}}
                ]}""";

        var parsed = parser.parseListings(
                parser.extractResultArray(parser.readTree(page)), 1_700_000_000_000L);
        assertEquals(1, parsed.records().size());
        assertTrue(parsed.failures().isEmpty());
        assertTrue(parsed.records().getFirst().fingerprint().isPlain());
        assertEquals("minecraft:diamond",
                parsed.records().getFirst().fingerprint().itemKey());
    }

    @Test
    void equivalentItemIdAndNumericStringCountAliasesAreAccepted() throws Exception {
        ParseResult<ParsedListing> parsed = parseListingItem("""
                {"id":"diamond","item_id":"minecraft:diamond",
                 "count":"016","amount":16}
                """);

        assertTrue(parsed.failures().isEmpty());
        assertEquals(1, parsed.records().size());
        assertEquals("minecraft:diamond", parsed.records().getFirst().listing().itemId());
        assertEquals(16, parsed.records().getFirst().listing().itemCount());
    }

    @Test
    void conflictingIdentityOrCountAliasesRejectTheWholeRecord() throws Exception {
        for (String item : List.of(
                """
                {"id":"minecraft:diamond","item_id":"minecraft:emerald","count":1}
                """,
                """
                {"id":"minecraft:diamond","count":64,"amount":1}
                """)) {
            ParseResult<ParsedListing> parsed = parseListingItem(item);
            assertTrue(parsed.records().isEmpty());
            assertEquals(1, parsed.failures().size());
        }
    }

    @Test
    void malformedFractionalOrOverflowCountsCanNeverDefaultOrWrap() throws Exception {
        for (String countField : List.of(
                "\"count\":{}",
                "\"count\":1.5",
                "\"count\":2147483648",
                "\"count\":\"not-a-number\"")) {
            ParseResult<ParsedListing> parsed = parseListingItem(
                    "{\"id\":\"minecraft:diamond\"," + countField + "}");
            assertTrue(parsed.records().isEmpty());
            assertEquals(1, parsed.failures().size());
        }
    }

    @Test
    void plainEchoCannotHideCustomNameInSecondaryAlias() throws Exception {
        ParseResult<ParsedListing> parsed = parseListingItem("""
                {"id":"minecraft:diamond","count":1,
                 "display_name":"diamond","custom_name":"Collector Diamond"}
                """);

        assertTrue(parsed.records().isEmpty());
        assertEquals(1, parsed.failures().size());
    }

    @Test
    void emptyPrimaryMetadataCannotHidePopulatedSecondaryAlias() throws Exception {
        ParseResult<ParsedListing> parsed = parseListingItem("""
                {"id":"minecraft:diamond","count":1,
                 "enchants":{},"enchantments":{"minecraft:unbreaking":3}}
                """);

        assertTrue(parsed.records().isEmpty());
        assertEquals(1, parsed.failures().size());
    }

    @Test
    void malformedKnownMetadataShapesNeverBecomePlainCommodities() throws Exception {
        for (String metadata : List.of(
                "\"display_name\":5",
                "\"enchants\":[]",
                "\"lore\":{}",
                "\"trim\":\"none\"",
                "\"contents\":\"[]\"",
                "\"trim\":{\"material\":\"gold\",\"pattern\":\"spire\",\"future\":true}")) {
            ParseResult<ParsedListing> parsed = parseListingItem(
                    "{\"id\":\"minecraft:diamond\",\"count\":1," + metadata + "}");
            assertTrue(parsed.records().isEmpty(), metadata);
            assertEquals(1, parsed.failures().size(), metadata);
        }
    }

    @Test
    void equivalentMetadataAliasesCanonicalizeAcrossOrderAndNumericStrings() throws Exception {
        ParseResult<ParsedListing> parsed = parseListingItem("""
                {"id":"minecraft:diamond","count":1,
                 "enchants":{"unbreaking":"3","mending":1},
                 "enchantments":{"minecraft:mending":1,"minecraft:unbreaking":3},
                 "contents":[{"count":1.0,"id":"minecraft:stone"}],
                 "container_contents":[{"id":"minecraft:stone","count":1}]}
                """);

        assertTrue(parsed.failures().isEmpty());
        assertEquals(1, parsed.records().size());
        assertFalse(parsed.records().getFirst().fingerprint().isPlain());
        assertEquals(2, parsed.records().getFirst().fingerprint().enchantments().size());
        assertFalse(parsed.records().getFirst().fingerprint().containerHash().isBlank());
    }

    @Test
    void jsonTextComponentsAreCanonicalizedBeforeAliasComparison() throws Exception {
        ParseResult<ParsedListing> parsed = parseListingItem("""
                {"id":"minecraft:diamond","count":1,
                 "display_name":{"text":"Collector","bold":true},
                 "custom_name":{"bold":true,"text":"Collector"},
                 "lore":[{"text":"Line","color":"gold"}]}
                """);

        assertTrue(parsed.failures().isEmpty());
        assertEquals(1, parsed.records().size());
        assertFalse(parsed.records().getFirst().fingerprint().displayName().isBlank());
        assertFalse(parsed.records().getFirst().fingerprint().loreHash().isBlank());
    }

    @Test
    void equivalentOuterTransactionAliasesNormalizeToOneRecord() throws Exception {
        ParseResult<ParsedSale> parsed = parseTransactionRecord("""
                {"unixMillisDateSold":1700000000000,"soldAt":"1700000000000",
                 "price":4100,"total_price":"4100",
                 "seller":{"uuid":"01234567-89AB-CDEF-0123-456789ABCDEF","name":"SellerOne"},
                 "player":{"id":"0123456789abcdef0123456789abcdef","username":"sellerone"},
                 "sellerUuid":"0123456789abcdef0123456789abcdef",
                 "sellerName":"SELLERONE",
                 "item":{"id":"minecraft:diamond","count":1}}
                """);

        assertTrue(parsed.failures().isEmpty());
        assertEquals(1, parsed.records().size());
        assertEquals(4_100, parsed.records().getFirst().sale().totalPrice());
        assertEquals(1_700_000_000_000L, parsed.records().getFirst().sale().soldAt());
        assertEquals("0123456789abcdef0123456789abcdef",
                parsed.records().getFirst().sale().sellerUuid());
    }

    @Test
    void conflictingOuterTransactionAliasesRejectTheRecord() throws Exception {
        for (String record : List.of(
                """
                {"unixMillisDateSold":1700000000000,"price":4100,"totalPrice":4200,
                 "seller":{"uuid":"seller-a"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """,
                """
                {"unixMillisDateSold":1700000000000,"sold_at":1700000000001,"price":4100,
                 "seller":{"uuid":"seller-a"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """,
                """
                {"unixMillisDateSold":1700000000000,"price":4100,
                 "seller":{"uuid":"seller-a"},"player":{"id":"seller-b"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """,
                """
                {"unixMillisDateSold":1700000000000,"price":4100,
                 "seller":{"uuid":"seller-a"},"seller_uuid":"seller-b",
                 "item":{"id":"minecraft:diamond","count":1}}
                """)) {
            ParseResult<ParsedSale> parsed = parseTransactionRecord(record);
            assertTrue(parsed.records().isEmpty());
            assertEquals(1, parsed.failures().size());
        }
    }

    @Test
    void malformedOrOverflowOuterNumbersRejectTheRecord() throws Exception {
        // A fractional *price* is no longer here: it is decimal currency and
        // rounds (see fractionalPriceIsRoundedRatherThanDropped). A fractional
        // or non-numeric *timestamp* is still a broken record.
        for (String record : List.of(
                """
                {"unixMillisDateSold":1700000000.5,"price":4100,
                 "seller":{"uuid":"seller-a"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """,
                """
                {"unixMillisDateSold":{},"price":4100,
                 "seller":{"uuid":"seller-a"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """,
                """
                {"unixMillisDateSold":1700000000000,"price":9223372036854775808,
                 "seller":{"uuid":"seller-a"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """)) {
            ParseResult<ParsedSale> parsed = parseTransactionRecord(record);
            assertTrue(parsed.records().isEmpty());
            assertEquals(1, parsed.failures().size());
        }
    }

    @Test
    void listingIdentityAndTimeAliasesMustBeEquivalentAndWellShaped() throws Exception {
        ParseResult<ParsedListing> equivalent = parseListingRecord("""
                {"id":42,"listingId":"42","price":4100,"totalPrice":"4100",
                 "time_left":"3600000","expires_in":3600000,
                 "seller":{"uuid":"seller-a","name":"Seller"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """);
        assertTrue(equivalent.failures().isEmpty());
        assertEquals("42", equivalent.records().getFirst().listing().listingKey());
        assertEquals(3_600_000L,
                equivalent.records().getFirst().listing().timeLeftMillis());

        for (String conflicting : List.of(
                """
                {"id":"a","listing_id":"b","price":4100,
                 "seller":{"uuid":"seller-a"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """,
                """
                {"id":"a","price":4100,"time_left":1000,"timeLeft":2000,
                 "seller":{"uuid":"seller-a"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """,
                """
                {"id":"a","price":4100,"time_left":-1,
                 "seller":{"uuid":"seller-a"},
                 "item":{"id":"minecraft:diamond","count":1}}
                """,
                """
                {"id":"a","price":4100,"seller":"seller-a",
                 "item":{"id":"minecraft:diamond","count":1}}
                """)) {
            ParseResult<ParsedListing> parsed = parseListingRecord(conflicting);
            assertTrue(parsed.records().isEmpty());
            assertEquals(1, parsed.failures().size());
        }
    }

    // The API documents `price` as `number`, not `integer`. A Go float64 of a
    // realistic auction price is emitted in scientific notation, which Jackson
    // reads as a double — the shape every fixture above happens to avoid by
    // using small whole prices.

    @Test
    void acceptsScientificNotationPrice() throws Exception {
        ParseResult<ParsedSale> result = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":1.5e+09,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:diamond\",\"count\":1}}");

        assertEquals(List.of(), result.failures());
        assertEquals(1, result.records().size());
        assertEquals(1_500_000_000L, result.records().get(0).sale().totalPrice());
    }

    @Test
    void acceptsWholeValuedDecimalPrice() throws Exception {
        ParseResult<ParsedSale> result = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":42000.0,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:diamond\",\"count\":1}}");

        assertEquals(List.of(), result.failures());
        assertEquals(42_000L, result.records().get(0).sale().totalPrice());
    }

    @Test
    void fractionalPriceIsRoundedRatherThanDropped() throws Exception {
        // Donut's economy is decimal. Rejecting these discarded roughly six
        // percent of every live page and locked valuation permanently.
        ParseResult<ParsedSale> result = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":1500.75,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:diamond\",\"count\":1}}");

        assertEquals(List.of(), result.failures());
        assertEquals(1_501L, result.records().get(0).sale().totalPrice());
    }

    @Test
    void fractionalPriceRoundsHalfUp() throws Exception {
        ParseResult<ParsedSale> down = parseTransactionRecord(priceRecord("1500.4"));
        ParseResult<ParsedSale> up = parseTransactionRecord(priceRecord("1500.5"));

        assertEquals(1_500L, down.records().get(0).sale().totalPrice());
        assertEquals(1_501L, up.records().get(0).sale().totalPrice());
    }

    @Test
    void priceGivenAsADecimalStringIsAccepted() throws Exception {
        ParseResult<ParsedSale> result = parseTransactionRecord(priceRecord("\"393999999.0\""));

        assertEquals(List.of(), result.failures());
        assertEquals(393_999_999L, result.records().get(0).sale().totalPrice());
    }

    @Test
    void priceThatRoundsToZeroIsStillRejected() throws Exception {
        // Rounding must not manufacture a free sale out of a sub-coin price.
        ParseResult<ParsedSale> result = parseTransactionRecord(priceRecord("0.4"));

        assertEquals(List.of(), result.records());
        assertEquals(1, result.failures().size());
    }

    @Test
    void nonNumericPriceIsStillRejectedWithItsValue() throws Exception {
        ParseResult<ParsedSale> result = parseTransactionRecord(priceRecord("{\"amount\":5}"));

        assertEquals(List.of(), result.records());
        assertTrue(result.failures().get(0).contains("amount"),
                result.failures().get(0));
    }

    @Test
    void fractionalItemCountIsStillMalformed() throws Exception {
        // Money rounds; counts do not. A fractional stack size is a broken
        // record, and loosening that alongside price would have hidden it.
        ParseResult<ParsedSale> result = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":9340,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:diamond\",\"count\":1.5}}");

        assertEquals(List.of(), result.records());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).contains("Non-integral"),
                result.failures().get(0));
    }

    private static String priceRecord(String priceJson) {
        return "{\"unixMillisDateSold\":1700000000000,\"price\":" + priceJson + ","
                + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                + "\"item\":{\"id\":\"minecraft:diamond\",\"count\":1}}";
    }

    @Test
    void acceptsPriceBeyondExactDoubleRange() throws Exception {
        // Real auction prices run past 2^53. The float64 already spent that
        // precision server-side, so refusing here would reject live listings.
        ParseResult<ParsedSale> result = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":1.0e+17,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:diamond\",\"count\":1}}");

        assertEquals(List.of(), result.failures());
        assertEquals(100_000_000_000_000_000L, result.records().get(0).sale().totalPrice());
    }

    @Test
    void rejectsPriceWiderThanALong() throws Exception {
        // Far above anything the live economy produces, but a silently wrong
        // price is worse than a reported failure.
        ParseResult<ParsedSale> result = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":1.0e+25,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:diamond\",\"count\":1}}");

        assertEquals(List.of(), result.records());
        assertEquals(1, result.failures().size());
    }

    // The live API nests enchantments two levels deep inside `enchants` and
    // hangs the armor trim beside them, rather than mapping id -> level flat.

    @Test
    void readsEnchantmentsFromTheNestedApiShape() throws Exception {
        ParseResult<ParsedSale> nested = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":9340,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:diamond_sword\",\"count\":1,"
                        + "\"enchants\":{\"enchantments\":{\"levels\":"
                        + "{\"minecraft:sharpness\":5}}}}}");
        ParseResult<ParsedSale> flat = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":9340,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:diamond_sword\",\"count\":1,"
                        + "\"enchants\":{\"minecraft:sharpness\":5}}}");

        assertEquals(List.of(), nested.failures());
        assertEquals(List.of(), flat.failures());
        // Both spellings must reach one identity, or history split across the
        // shape change would price the same sword as two different items.
        assertEquals(flat.records().get(0).fingerprint().canonicalForm(),
                nested.records().get(0).fingerprint().canonicalForm());
    }

    @Test
    void wrapperCarryingOnlyATrimIsNotAnEnchantmentFailure() throws Exception {
        ParseResult<ParsedSale> result = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":9340,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:iron_chestplate\",\"count\":1,"
                        + "\"enchants\":{\"trim\":{\"material\":\"minecraft:gold\","
                        + "\"pattern\":\"minecraft:coast\"}}}}");

        assertEquals(List.of(), result.failures());
        assertEquals(1, result.records().size());
    }

    @Test
    void nestedTrimReachesTheFingerprint() throws Exception {
        ParseResult<ParsedSale> trimmed = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":9340,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:iron_chestplate\",\"count\":1,"
                        + "\"enchants\":{\"trim\":{\"material\":\"minecraft:gold\","
                        + "\"pattern\":\"minecraft:coast\"}}}}");
        ParseResult<ParsedSale> plain = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":9340,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:iron_chestplate\",\"count\":1}}");

        assertNotEquals(plain.records().get(0).fingerprint().canonicalForm(),
                trimmed.records().get(0).fingerprint().canonicalForm());
    }

    @Test
    void conflictingItemAndNestedTrimIsStillRejected() throws Exception {
        ParseResult<ParsedSale> result = parseTransactionRecord(
                "{\"unixMillisDateSold\":1700000000000,\"price\":9340,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:iron_chestplate\",\"count\":1,"
                        + "\"trim\":{\"material\":\"minecraft:gold\","
                        + "\"pattern\":\"minecraft:coast\"},"
                        + "\"enchants\":{\"trim\":{\"material\":\"minecraft:iron\","
                        + "\"pattern\":\"minecraft:dune\"}}}}");

        assertEquals(List.of(), result.records());
        assertEquals(1, result.failures().size());
    }

    @Test
    void acceptsScientificNotationTimestamp() throws Exception {
        ParseResult<ParsedSale> result = parseTransactionRecord(
                "{\"unixMillisDateSold\":1.7e+12,\"price\":9340,"
                        + "\"seller\":{\"uuid\":\"a\",\"name\":\"S\"},"
                        + "\"item\":{\"id\":\"minecraft:diamond\",\"count\":1}}");

        assertEquals(List.of(), result.failures());
        assertEquals(1_700_000_000_000L, result.records().get(0).sale().soldAt());
    }

    private ParseResult<ParsedListing> parseListingItem(String itemJson) throws Exception {
        String page = "{\"status\":200,\"result\":[{\"price\":4100,"
                + "\"seller\":{\"uuid\":\"s-1\",\"name\":\"SellerOne\"},"
                + "\"item\":" + itemJson + "}]}";
        return parser.parseListings(
                parser.extractResultArray(parser.readTree(page)), 1_700_000_000_000L);
    }

    private ParseResult<ParsedListing> parseListingRecord(String recordJson) throws Exception {
        String page = "{\"status\":200,\"result\":[" + recordJson + "]}";
        return parser.parseListings(
                parser.extractResultArray(parser.readTree(page)), 1_700_000_000_000L);
    }

    private ParseResult<ParsedSale> parseTransactionRecord(String recordJson) throws Exception {
        String page = "{\"status\":200,\"result\":[" + recordJson + "]}";
        return parser.parseTransactions(
                parser.extractResultArray(parser.readTree(page)));
    }
}
