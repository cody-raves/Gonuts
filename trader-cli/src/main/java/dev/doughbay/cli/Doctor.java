package dev.doughbay.cli;

import dev.doughbay.engine.MarketService;
import dev.doughbay.api.DonutApiClient;
import dev.doughbay.api.ResponseParser.ParseResult;
import dev.doughbay.api.ResponseParser.ParsedListing;
import dev.doughbay.api.ResponseParser.ParsedSale;
import dev.doughbay.core.model.Sale;
import dev.doughbay.storage.TransactionRepository;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only data-quality health check. Run this (repeatedly) before trusting
 * any number the engine produces, and long before any trade — paper or
 * otherwise. Uses ~4 API requests total and never writes anything to the
 * server; the only local side effect is storing the transactions it fetched.
 */
final class Doctor {

    private final MarketService service;
    private int passed = 0;
    private int warned = 0;
    private int failed = 0;

    Doctor(MarketService service) {
        this.service = service;
    }

    /** @return process exit code: 0 all pass, 1 warnings, 2 failures. */
    int run() throws Exception {
        System.out.println("DoughBay doctor — validating the data pipeline (read-only, ~4 API requests)\n");
        DonutApiClient client = service.client();

        // --- 1. API reachability, auth, and parseability -------------------
        ParseResult<ParsedSale> first;
        try {
            first = client.fetchTransactions(1);
        } catch (Exception e) {
            fail("API fetch", e.getMessage());
            System.out.println("\nCannot reach the API — nothing else can be validated.");
            return 2;
        }
        pass("API fetch", "authenticated, transactions page 1 returned");

        int records = first.records().size();
        int failures = first.failures().size();
        if (records == 0) {
            fail("Transaction parsing", "0 records parsed — the response shape likely "
                    + "doesn't match the parser's field names. Raw pages are archived; "
                    + "inspect one and adjust ResponseParser aliases.");
        } else if (failures > 0 && failures >= records / 4) {
            warn("Transaction parsing", records + " parsed, " + failures + " failed. Samples: "
                    + String.join(" | ", first.failures().subList(0, Math.min(3, failures))));
        } else {
            pass("Transaction parsing", records + " records parsed, " + failures + " failures");
        }

        // --- 2. Field sanity on what was parsed ----------------------------
        if (records > 0) {
            long now = System.currentTimeMillis();
            long monthAgo = now - Duration.ofDays(30).toMillis();
            int badTime = 0, badSeller = 0, badPrice = 0;
            for (ParsedSale p : first.records()) {
                Sale s = p.sale();
                if (s.soldAt() < monthAgo || s.soldAt() > now + 120_000) badTime++;
                if (s.sellerUuid().isBlank()) badSeller++;
                if (s.totalPrice() <= 0 || s.itemCount() <= 0) badPrice++;
            }
            check("Timestamps plausible", badTime, records,
                    "sold_at outside [now-30d, now+2min] — timestamp field/unit may be wrong (seconds vs millis?)");
            check("Sellers present", badSeller, records, "missing seller UUIDs");
            check("Prices/counts positive", badPrice, records, "non-positive prices or counts slipped through");

            Set<String> itemIds = new HashSet<>();
            first.records().forEach(p -> itemIds.add(p.sale().itemId()));
            pass("Market diversity", itemIds.size() + " distinct item ids on page 1");
        }

        // --- 3. Hash stability and dedup -----------------------------------
        if (records > 0) {
            ParseResult<ParsedSale> second = client.fetchTransactions(1);
            Set<String> hashesA = new HashSet<>();
            first.records().forEach(p -> hashesA.add(p.sale().transactionHash()));
            long overlap = second.records().stream()
                    .filter(p -> hashesA.contains(p.sale().transactionHash())).count();
            if (second.records().isEmpty()) {
                warn("Hash stability", "second fetch returned no records; cannot verify");
            } else if (overlap == 0) {
                fail("Hash stability", "two immediate fetches of page 1 share zero transaction "
                        + "hashes — hashing is unstable and dedup will not work");
            } else {
                pass("Hash stability", overlap + "/" + second.records().size()
                        + " records matched across back-to-back fetches");
            }

            MarketService.CollectReport insert1 = storeParsed(first);
            MarketService.CollectReport insert2 = storeParsed(second);
            if (insert2.newRecords() > insert2.duplicates()) {
                warn("Database dedup", "re-inserting the same page created "
                        + insert2.newRecords() + " new rows — investigate before long collection runs");
            } else {
                pass("Database dedup", insert1.newRecords() + " new on first insert, "
                        + insert2.newRecords() + " new / " + insert2.duplicates()
                        + " duplicates on re-insert");
            }
        }

        // --- 4. Listings endpoint ------------------------------------------
        try {
            ParseResult<ParsedListing> listings = client.fetchListings(1, "ender pearl");
            if (listings.records().isEmpty() && listings.failures().isEmpty()) {
                warn("Listings fetch", "search returned nothing — search term semantics may differ");
            } else if (listings.records().isEmpty()) {
                fail("Listings parsing", "all " + listings.failures().size() + " listing records "
                        + "failed to parse. Sample: " + listings.failures().get(0));
            } else {
                pass("Listings fetch", listings.records().size() + " listings parsed, "
                        + listings.failures().size() + " failures");
            }
        } catch (Exception e) {
            fail("Listings fetch", e.getMessage());
        }

        // --- 5. Local database state ---------------------------------------
        TransactionRepository tx = service.transactions();
        long total = tx.count();
        long markets = tx.countDistinctMarkets();
        long outliers = tx.countOutliers();
        long[] range = tx.soldAtRange();
        String span = range == null ? "empty"
                : String.format(Locale.ROOT, "%.1f hours of history",
                        (range[1] - range[0]) / 3_600_000.0);
        pass("Database", total + " transactions, " + markets + " markets, "
                + outliers + " flagged outliers, " + span);
        if (total < 500) {
            warn("History depth", "only " + total + " transactions stored — run `collect --loop` "
                    + "for a while; the engine needs 20+ sales per market before it trusts anything");
        }

        // --- 6. Request budget ---------------------------------------------
        DonutApiClient.ApiHealth health = client.health();
        pass("Rate budget", health.requestsInLastMinute() + "/" + health.budgetPerMinute()
                + " requests used this minute, " + health.totalErrors() + " total errors");

        System.out.printf(Locale.ROOT, "%n%d passed, %d warnings, %d failures%n", passed, warned, failed);
        if (failed > 0) {
            System.out.println("FAILURES above must be fixed before collected data can be trusted.");
            return 2;
        }
        if (warned > 0) {
            System.out.println("Warnings are acceptable for early testing but re-run doctor as data grows.");
            return 1;
        }
        System.out.println("Data pipeline looks healthy. Next: collect --loop, then scan, then paper.");
        return 0;
    }

    private MarketService.CollectReport storeParsed(ParseResult<ParsedSale> parsed) throws Exception {
        int added = 0, dupes = 0;
        List<Sale> newSales = new java.util.ArrayList<>();
        for (ParsedSale p : parsed.records()) {
            if (service.transactions().insertIfAbsent(p.sale(), p.rawJson())) {
                added++;
                newSales.add(p.sale());
            } else {
                dupes++;
            }
        }
        return new MarketService.CollectReport(1, parsed.records().size(), added, dupes,
                parsed.failures().size(), newSales);
    }

    private void check(String name, int bad, int total, String problem) {
        if (bad == 0) {
            pass(name, total + "/" + total + " ok");
        } else if (bad < total / 10) {
            warn(name, bad + "/" + total + " " + problem);
        } else {
            fail(name, bad + "/" + total + " " + problem);
        }
    }

    private void pass(String name, String detail) {
        passed++;
        System.out.printf(Locale.ROOT, "  PASS  %-22s %s%n", name, detail);
    }

    private void warn(String name, String detail) {
        warned++;
        System.out.printf(Locale.ROOT, "  WARN  %-22s %s%n", name, detail);
    }

    private void fail(String name, String detail) {
        failed++;
        System.out.printf(Locale.ROOT, "  FAIL  %-22s %s%n", name, detail);
    }
}
