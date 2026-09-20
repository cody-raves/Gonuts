package dev.doughbay.fabric;

import dev.doughbay.api.ResponseParser.ParsedSale;
import dev.doughbay.api.ResponseParser.ParseResult;
import dev.doughbay.core.model.Sale;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verdict half of the in-game key test. A key that authenticates but
 * returns unusable history must never read as a pass: that is exactly the
 * state that would otherwise silently poison every later valuation.
 */
class ApiKeyTesterTest {

    private static final long NOW = 1_900_000_000_000L;

    private static ParsedSale sale(long soldAt, int count, long totalPrice) {
        // evaluate reads only the Sale; the fingerprint and raw JSON are
        // irrelevant to the verdict.
        return new ParsedSale(new Sale("hash-" + soldAt + "-" + totalPrice, soldAt,
                "uuid", "Seller", "minecraft:diamond", "minecraft:diamond",
                count, totalPrice), null, null);
    }

    private static ParseResult<ParsedSale> page(int healthy, int failures) {
        List<ParsedSale> records = new ArrayList<>();
        for (int i = 0; i < healthy; i++) {
            records.add(sale(NOW - (i + 1) * 60_000L, 1, 1_000L + i));
        }
        List<String> parseFailures = new ArrayList<>();
        for (int i = 0; i < failures; i++) {
            parseFailures.add("row " + i + " missing price");
        }
        return new ParseResult<>(records, parseFailures);
    }

    @Test
    void healthyHistoryPasses() {
        ApiKeyTester.Report report = ApiKeyTester.evaluate(page(40, 0), NOW);

        assertEquals(ApiKeyTester.State.PASSED, report.state());
        assertTrue(report.detail().contains("40 completed sales parsed"),
                report.detail());
        assertTrue(report.detail().contains("1m ago"), report.detail());
    }

    @Test
    void emptyPageIsSuspectNotPassed() {
        ApiKeyTester.Report report = ApiKeyTester.evaluate(page(0, 0), NOW);

        assertEquals(ApiKeyTester.State.SUSPECT, report.state());
        assertTrue(report.detail().contains("no completed sales"), report.detail());
    }

    @Test
    void allRowsUnparseableIsSuspect() {
        ApiKeyTester.Report report = ApiKeyTester.evaluate(page(0, 12), NOW);

        assertEquals(ApiKeyTester.State.SUSPECT, report.state());
        // The reason must name the field, not just say "shape changed".
        assertTrue(report.detail().contains("row 0 missing price"), report.detail());
    }

    @Test
    void sampleFailuresCollapsesIdenticalReasons() {
        String sample = ApiKeyTester.sampleFailures(
                List.of("Non-integral price", "Non-integral price", "Missing seller uuid"));

        assertEquals("Non-integral price; Missing seller uuid", sample);
    }

    @Test
    void sampleFailuresIsBoundedAndSurvivesBlankReasons() {
        String sample = ApiKeyTester.sampleFailures(List.of("a", "b", "c", "d"));
        assertEquals("a; b", sample);

        assertEquals("unknown", ApiKeyTester.sampleFailures(List.of("  ")));
        assertEquals("no reason reported", ApiKeyTester.sampleFailures(List.of()));
    }

    @Test
    void secondsInsteadOfMillisTimestampsAreSuspect() {
        // The classic unit mistake: soldAt handed back in seconds reads as 1970.
        List<ParsedSale> records = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            records.add(sale(NOW / 1000 - i, 1, 1_000L));
        }
        ApiKeyTester.Report report =
                ApiKeyTester.evaluate(new ParseResult<>(records, List.of()), NOW);

        assertEquals(ApiKeyTester.State.SUSPECT, report.state());
        assertTrue(report.detail().contains("check units"), report.detail());
    }

    @Test
    void nonPositivePricesAreSuspect() {
        List<ParsedSale> records = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            records.add(sale(NOW - 60_000L, 1, 0));
        }
        ApiKeyTester.Report report =
                ApiKeyTester.evaluate(new ParseResult<>(records, List.of()), NOW);

        assertEquals(ApiKeyTester.State.SUSPECT, report.state());
    }

    @Test
    void mostlyHealthyPageStillPassesAndReportsUnparsedRows() {
        ApiKeyTester.Report report = ApiKeyTester.evaluate(page(40, 3), NOW);

        assertEquals(ApiKeyTester.State.PASSED, report.state());
        assertTrue(report.detail().contains("3 unparsed"), report.detail());
        // The reason has to survive onto the passing path too.
        assertTrue(report.detail().contains("row 0 missing price"), report.detail());
    }

    @Test
    void heavyParseFailureRateIsSuspect() {
        ApiKeyTester.Report report = ApiKeyTester.evaluate(page(10, 40), NOW);

        assertEquals(ApiKeyTester.State.SUSPECT, report.state());
        assertTrue(report.detail().contains("failed to parse"), report.detail());
    }

    @Test
    void blankKeyIsRejectedWithoutARequest() {
        ApiKeyTester tester = new ApiKeyTester();

        assertFalse(tester.start("   "));
        assertEquals(ApiKeyTester.State.FAILED, tester.report().state());
        assertTrue(tester.report().detail().contains("No API key"),
                tester.report().detail());
    }
}
