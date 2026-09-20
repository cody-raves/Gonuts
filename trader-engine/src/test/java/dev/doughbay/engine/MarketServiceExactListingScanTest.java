package dev.doughbay.engine;

import dev.doughbay.api.ApiException;
import dev.doughbay.api.DonutApiClient;
import dev.doughbay.api.DonutApiConfig;
import dev.doughbay.api.ResponseParser.ParseResult;
import dev.doughbay.api.ResponseParser.ParsedListing;
import dev.doughbay.core.analysis.AnalyzerConfig;
import dev.doughbay.core.analysis.CommodityRegistry;
import dev.doughbay.core.analysis.FeeConfig;
import dev.doughbay.core.analysis.RiskConfig;
import dev.doughbay.core.model.ItemFingerprint;
import dev.doughbay.core.model.Listing;
import dev.doughbay.storage.Database;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketServiceExactListingScanTest {

    private static final String ITEM_ID = "minecraft:ender_pearl";
    private static final ItemFingerprint FINGERPRINT =
            ItemFingerprint.builder(ITEM_ID).build();

    @Test
    void shortFirstPageIsTheWholeBookAndNeverAsksForPageTwo() throws Exception {
        // The common case: a commodity with a handful of listings. Requesting
        // page 2 here is what the live API answers with HTTP 500, so the scan
        // must recognise the book ended without asking.
        StubClient client = new StubClient();
        client.page(1, rows(listing("a", 1_000, 4_100),
                listing("b", 1_000, 4_200),
                listing("c", 2_000, 4_300)));

        try (Database db = Database.inMemory()) {
            MarketService.ExactListingScanReport report = service(client, db)
                    .scanExactCommodityListings(ITEM_ID, 10);

            assertTrue(report.complete());
            assertTrue(report.exhaustionProven());
            assertEquals(1, report.pagesScanned());
            assertEquals(1, client.fetches, "page 2 must never be requested");
            assertEquals(0, report.parseFailures());
            assertEquals(List.of("a", "b", "c"), report.listings().stream()
                    .map(Listing::listingKey).toList());
            assertEquals(1_000, report.listings().getFirst().observedAt());
            try (ResultSet rs = db.connection().createStatement().executeQuery(
                    "SELECT COUNT(*) FROM listing_snapshots")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    @Test
    void materiallyIdenticalDuplicateBlocksAbsenceProofButNotValuation() throws Exception {
        // The listings endpoint publishes no listing id, so one seller's two
        // identical stacks collapse to one derived key. Their price is not in
        // doubt; only "is that exact listing gone" is.
        StubClient client = new StubClient();
        client.page(1, rows(listing("fallback-collision", 1_000, 4_100),
                listing("fallback-collision", 2_000, 4_100)));

        try (Database db = Database.inMemory()) {
            MarketService.ExactListingScanReport report = service(client, db)
                    .scanExactCommodityListings(ITEM_ID, 10);

            assertTrue(report.complete(), "the whole book was still read");
            assertFalse(report.provenForAbsence(), "one row cannot be named");
            assertEquals(0, report.parseFailures(), "a duplicate is not malformed");
            assertEquals(1, report.ambiguousIdentities());
            assertEquals(1, report.listings().size());
        }
    }

    @Test
    void nonEmptyPageAtCapCanNeverProveAbsence() throws Exception {
        StubClient client = new StubClient();
        client.page(1, fullPage("a", 1_000));
        client.page(2, fullPage("b", 2_000));

        try (Database db = Database.inMemory()) {
            MarketService.ExactListingScanReport report = service(client, db)
                    .scanExactCommodityListings(ITEM_ID, 2);

            assertFalse(report.complete());
            assertFalse(report.exhaustionProven());
            assertEquals(2, report.pagesScanned());
            assertTrue(report.detail().contains("page cap"));
        }
    }

    @Test
    void anyParseFailureMakesTerminalCoverageIncomplete() throws Exception {
        StubClient client = new StubClient();
        client.page(1, fullPage("a", 1_000));
        client.page(2, new ParseResult<>(List.of(), List.of("malformed row")));

        try (Database db = Database.inMemory()) {
            MarketService.ExactListingScanReport report = service(client, db)
                    .scanExactCommodityListings(ITEM_ID, 10);

            assertFalse(report.complete());
            assertTrue(report.exhaustionProven());
            assertEquals(1, report.parseFailures());
            assertEquals(2, report.pagesScanned());
        }
    }

    @Test
    void conflictingDuplicateIdentityStillBlocksAbsenceProof() throws Exception {
        StubClient client = new StubClient();
        client.page(1, rows(listing("same-api-id", 1_000, 4_100),
                listing("same-api-id", 2_000, 9_900)));

        try (Database db = Database.inMemory()) {
            MarketService.ExactListingScanReport report = service(client, db)
                    .scanExactCommodityListings(ITEM_ID, 10);

            assertTrue(report.complete());
            assertFalse(report.provenForAbsence());
            assertEquals(1, report.ambiguousIdentities());
            assertEquals(1, report.listings().size());
        }
    }

    @Test
    void aRealParseFailureStillBlocksValuationUnlikeADuplicate() throws Exception {
        // The two must not be conflated: a malformed row means the book was
        // not fully understood, which is a different claim from "two rows look
        // alike", and only the former may lock valuation.
        StubClient client = new StubClient();
        client.page(1, new ParseResult<>(
                List.of(listing("a", 1_000, 4_100)), List.of("malformed row")));

        try (Database db = Database.inMemory()) {
            MarketService.ExactListingScanReport report = service(client, db)
                    .scanExactCommodityListings(ITEM_ID, 10);

            assertFalse(report.complete());
            assertFalse(report.provenForAbsence());
            assertEquals(1, report.parseFailures());
            assertEquals(0, report.ambiguousIdentities());
        }
    }

    @Test
    void aBookOfDuplicatesStillReachesValuation() throws Exception {
        // The live failure: every tracked commodity carried duplicates, so no
        // book was ever admitted and the whole tool published nothing.
        StubClient client = new StubClient();
        client.page(1, rows(listing("dup", 1_000, 4_100),
                listing("dup", 2_000, 4_100),
                listing("unique", 3_000, 4_200)));

        try (Database db = Database.inMemory()) {
            MarketService.ScanReport report = service(client, db)
                    .scanCommodityListings(10);

            assertTrue(report.completeForValuation(),
                    "indistinguishable rows must not lock every market");
            assertEquals(1, report.completeItems());
            assertFalse(report.commodityListings().isEmpty());
        }
    }

    @Test
    void broadValuationScanSuppressesBookWithoutTerminalEmptyPage() throws Exception {
        StubClient client = new StubClient();
        client.page(1, fullPage("a", 1_000));
        client.page(2, fullPage("b", 2_000));

        try (Database db = Database.inMemory()) {
            MarketService.ScanReport report = service(client, db)
                    .scanCommodityListings(2);

            assertEquals(1, report.itemsScanned());
            assertEquals(0, report.completeItems());
            assertEquals(1, report.incompleteItems());
            assertEquals(0, report.listingsSeen());
            assertFalse(report.completeForValuation());
            assertTrue(report.commodityListings().isEmpty(),
                    "partial active-book depth must never reach valuation");
        }
    }

    @Test
    void broadValuationScanAdmitsOnlyCleanTerminalBook() throws Exception {
        StubClient client = new StubClient();
        client.page(1, rows(listing("a", 1_000, 4_100)));

        try (Database db = Database.inMemory()) {
            MarketService.ScanReport report = service(client, db)
                    .scanCommodityListings(10);

            assertEquals(1, report.completeItems());
            assertEquals(0, report.incompleteItems());
            assertEquals(1, report.listingsSeen());
            assertTrue(report.completeForValuation());
            assertEquals(List.of("a"), report.commodityListings().get(ITEM_ID)
                    .stream().map(Listing::listingKey).toList());
        }
    }

    // --- regressions for the two bugs that stopped the live collector ---

    @Test
    void fullPageFollowedByShortPageIsComplete() throws Exception {
        StubClient client = new StubClient();
        client.page(1, fullPage("a", 1_000));
        client.page(2, rows(listing("tail", 2_000, 4_900)));

        try (Database db = Database.inMemory()) {
            MarketService.ExactListingScanReport report = service(client, db)
                    .scanExactCommodityListings(ITEM_ID, 10);

            assertTrue(report.complete());
            assertEquals(2, report.pagesScanned());
            assertEquals(2, client.fetches, "page 3 must never be requested");
            assertEquals(101, report.listings().size());
        }
    }

    @Test
    void emptyBookIsCompleteWithoutASecondRequest() throws Exception {
        StubClient client = new StubClient();
        client.page(1, rows());

        try (Database db = Database.inMemory()) {
            MarketService.ExactListingScanReport report = service(client, db)
                    .scanExactCommodityListings(ITEM_ID, 10);

            assertTrue(report.complete());
            assertEquals(1, client.fetches);
        }
    }

    @Test
    void pageLargerThanTheAssumedSizeRefusesToProveExhaustion() throws Exception {
        // If pages are bigger than this scan believes, "not full" stops meaning
        // "last page", and a partial book must not read as a whole one.
        StubClient client = new StubClient();
        List<ParsedListing> oversized = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            oversized.add(listing("row-" + i, 1_000, 4_100 + i));
        }
        client.page(1, new ParseResult<>(List.copyOf(oversized), List.of()));

        try (Database db = Database.inMemory()) {
            MarketService.ExactListingScanReport report = service(client, db)
                    .scanExactCommodityListings(ITEM_ID, 10);

            assertFalse(report.complete());
            assertFalse(report.exhaustionProven());
            assertTrue(report.detail().contains("exceeded"), report.detail());
        }
    }

    @Test
    void oneUnreachableCommodityDoesNotAbandonTheRestOfTheSweep() throws Exception {
        // The live failure: one book's transport error propagated out of the
        // whole loop and killed the collector thread outright.
        String other = "minecraft:diamond";
        MultiStubClient client = new MultiStubClient(Set.of("ender pearl"));
        client.page("diamond", 1, rows(listing("d", 1_000, 4_100, other)));

        try (Database db = Database.inMemory()) {
            MarketService service = new MarketService(AnalyzerConfig.defaults(),
                    FeeConfig.zero(), RiskConfig.defaults(),
                    new CommodityRegistry(Set.of(ITEM_ID, other)), client, db);
            MarketService.ScanReport report = service.scanCommodityListings(10);

            assertEquals(2, report.itemsScanned());
            assertEquals(1, report.completeItems(), "the healthy book still scanned");
            assertEquals(1, report.incompleteItems());
            // One unprovable book still locks valuation for the whole sweep.
            assertFalse(report.completeForValuation());
        }
    }

    @Test
    void aRunOfConsecutiveFailuresStillAbortsTheSweep() throws Exception {
        // Isolating one bad book must not turn into retrying every commodity
        // against an API that is simply down.
        Set<String> commodities = Set.of(ITEM_ID, "minecraft:diamond",
                "minecraft:emerald", "minecraft:gold_ingot",
                "minecraft:iron_ingot", "minecraft:coal");
        MultiStubClient client = new MultiStubClient(Set.of("ender pearl", "diamond",
                "emerald", "gold ingot", "iron ingot", "coal"));

        try (Database db = Database.inMemory()) {
            MarketService service = new MarketService(AnalyzerConfig.defaults(),
                    FeeConfig.zero(), RiskConfig.defaults(),
                    new CommodityRegistry(commodities), client, db);

            ApiException thrown = assertThrows(ApiException.class,
                    () -> service.scanCommodityListings(10));
            assertTrue(thrown.getMessage().contains("consecutive"), thrown.getMessage());
        }
    }

    private static MarketService service(StubClient client, Database db) {
        return new MarketService(AnalyzerConfig.defaults(), FeeConfig.zero(),
                RiskConfig.defaults(), new CommodityRegistry(Set.of(ITEM_ID)),
                client, db);
    }

    private static ParseResult<ParsedListing> rows(ParsedListing... rows) {
        return new ParseResult<>(List.of(rows), List.of());
    }

    /**
     * A full page of 100 distinct rows. Fixtures of one or two rows would be
     * read as a final page by the real scan, so any test that needs a "there
     * is another page after this" must use a genuinely full one.
     */
    private static ParseResult<ParsedListing> fullPage(String keyPrefix, long observedAt) {
        List<ParsedListing> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(listing(keyPrefix + "-" + i, observedAt, 4_100 + i));
        }
        return new ParseResult<>(List.copyOf(rows), List.of());
    }

    private static ParsedListing listing(String key, long observedAt, long price) {
        return listing(key, observedAt, price, ITEM_ID);
    }

    private static ParsedListing listing(String key, long observedAt, long price,
                                         String itemId) {
        Listing listing = new Listing(key, observedAt,
                "0123456789abcdef0123456789abcdef", "Seller",
                itemId, itemId, 16, price, 60_000L);
        return new ParsedListing(listing, ItemFingerprint.builder(itemId).build(), "{}");
    }

    /**
     * Serves several commodities and answers HTTP 500 for the search terms it
     * is told are unhealthy, mirroring an API that cannot handle one query.
     */
    private static final class MultiStubClient extends DonutApiClient {
        private final Map<String, ParseResult<ParsedListing>> pages = new HashMap<>();
        private final Set<String> failing;

        MultiStubClient(Set<String> failingSearchTerms) {
            super(DonutApiConfig.withDefaults("unit-test-key"));
            this.failing = failingSearchTerms;
        }

        void page(String search, int page, ParseResult<ParsedListing> result) {
            pages.put(search + "#" + page, result);
        }

        @Override
        public ParseResult<ParsedListing> fetchListings(int page, String search)
                throws ApiException, InterruptedException {
            if (failing.contains(search)) {
                throw new ApiException("Temporary server failure (HTTP 500)");
            }
            ParseResult<ParsedListing> result = pages.get(search + "#" + page);
            if (result == null) throw new ApiException("Temporary server failure (HTTP 500)");
            return result;
        }
    }

    private static final class StubClient extends DonutApiClient {
        private final Map<Integer, ParseResult<ParsedListing>> pages = new HashMap<>();

        StubClient() {
            super(DonutApiConfig.withDefaults("unit-test-key"));
        }

        void page(int page, ParseResult<ParsedListing> result) {
            pages.put(page, result);
        }

        int fetches;

        @Override
        public ParseResult<ParsedListing> fetchListings(int page, String search)
                throws ApiException, InterruptedException {
            assertEquals("ender pearl", search);
            fetches++;
            ParseResult<ParsedListing> result = pages.get(page);
            if (result == null) {
                // Exactly what the live API does for a page past the end:
                // "Could not handle your request. This may be because the
                // specified user/page/item does not exist." Returning an empty
                // page here would let the scan pass a test the real service
                // cannot pass.
                throw new ApiException("Temporary server failure (HTTP 500)");
            }
            return result;
        }
    }
}
