package dev.doughbay.fabric;

import dev.doughbay.api.DonutApiClient;
import dev.doughbay.api.DonutApiConfig;
import dev.doughbay.api.ResponseParser.ParsedSale;
import dev.doughbay.api.ResponseParser.ParseResult;
import dev.doughbay.core.model.Sale;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-shot read-only check that an API key can actually retrieve completed
 * sale history.
 *
 * <p>This is the smallest useful version of {@code trader-cli}'s doctor: a
 * single {@code /v1/auction/transactions/1} request that proves the key
 * authenticates, the response parses, and the timestamps are plausible. It
 * writes nothing to the server and nothing to the local database, so it is
 * safe to run repeatedly while setting a key up.
 *
 * <p>The request runs on a short-lived daemon thread. Neither the key nor any
 * value derived from it ever reaches {@link #report()}.
 */
final class ApiKeyTester {

    /** How far a completed sale may sit outside "now" before it looks wrong. */
    private static final long MAX_HISTORY_AGE_MILLIS = 30L * 24 * 3_600_000L;
    private static final long MAX_CLOCK_SKEW_MILLIS = 120_000L;
    /** Enough to distinguish causes without overflowing one status line. */
    private static final int MAX_SAMPLE_REASONS = 2;

    enum State {
        /** Never run in this session. */
        IDLE,
        RUNNING,
        /** Authenticated and returned parseable, plausible sale history. */
        PASSED,
        /** Authenticated, but the history came back unusable. */
        SUSPECT,
        FAILED
    }

    record Report(State state, String detail, long updatedAtMillis) {

        static Report idle() {
            return new Report(State.IDLE, "Not tested this session", 0);
        }

        boolean running() {
            return state == State.RUNNING;
        }
    }

    private final AtomicReference<Report> report = new AtomicReference<>(Report.idle());

    Report report() {
        return report.get();
    }

    void reset() {
        report.set(Report.idle());
    }

    /**
     * Starts a test against {@code key} unless one is already in flight.
     *
     * @return {@code false} when a test is already running or the key is blank
     */
    synchronized boolean start(String key) {
        if (report.get().running()) return false;
        String candidate = key == null ? "" : key.strip();
        if (candidate.isBlank()) {
            report.set(new Report(State.FAILED, "No API key to test",
                    System.currentTimeMillis()));
            return false;
        }
        report.set(new Report(State.RUNNING, "Requesting completed sales, page 1...",
                System.currentTimeMillis()));
        Thread thread = new Thread(() -> run(candidate), "doughbay-api-key-test");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private void run(String key) {
        try {
            DonutApiClient client = new DonutApiClient(DonutApiConfig.withDefaults(key));
            ParseResult<ParsedSale> page = client.fetchTransactions(1);
            report.set(evaluate(page, System.currentTimeMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            report.set(new Report(State.FAILED, "Test interrupted",
                    System.currentTimeMillis()));
        } catch (Exception e) {
            // DonutApiClient already redacts the key from its messages, so this
            // is safe to display. Fall back to the type when there is no text.
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            report.set(new Report(State.FAILED, message, System.currentTimeMillis()));
        }
    }

    /** Pure verdict, separated from transport so it can be unit tested. */
    static Report evaluate(ParseResult<ParsedSale> page, long now) {
        int records = page.records().size();
        int failures = page.failures().size();

        if (records == 0 && failures == 0) {
            return new Report(State.SUSPECT,
                    "Authenticated, but page 1 held no completed sales at all",
                    now);
        }
        if (records == 0) {
            return new Report(State.SUSPECT,
                    "Authenticated, but all " + failures
                            + " rows failed to parse — " + sampleFailures(page.failures()),
                    now);
        }

        long newest = 0;
        int implausible = 0;
        for (ParsedSale parsed : page.records()) {
            Sale sale = parsed.sale();
            newest = Math.max(newest, sale.soldAt());
            if (sale.soldAt() < now - MAX_HISTORY_AGE_MILLIS
                    || sale.soldAt() > now + MAX_CLOCK_SKEW_MILLIS
                    || sale.totalPrice() <= 0 || sale.itemCount() <= 0) {
                implausible++;
            }
        }

        String age = newest == 0 ? "unknown" : describeAge(now - newest);
        String base = records + " completed sales parsed, newest " + age;

        if (implausible > records / 4) {
            return new Report(State.SUSPECT,
                    base + " — " + implausible
                            + " have impossible timestamps or prices (check units)",
                    now);
        }
        if (failures > records / 4) {
            return new Report(State.SUSPECT,
                    base + ", but " + failures + " rows failed to parse — "
                            + sampleFailures(page.failures()), now);
        }
        // Name the reason even on the passing path. A handful of unparsed rows
        // still locks valuation upstream, so "2 unparsed" alone leaves the one
        // thing worth knowing off the screen.
        return new Report(State.PASSED,
                base + (failures == 0 ? "" : " (" + failures + " unparsed: "
                        + sampleFailures(page.failures()) + ")"), now);
    }

    /**
     * The distinct reasons rows were rejected, newest-first order preserved.
     *
     * <p>"The response shape has likely changed" is true but useless: the
     * whole point of showing this on screen is to name the field that moved,
     * so a shape change is a five-minute fix instead of an investigation.
     * Duplicates are collapsed because a broken field fails every row
     * identically.
     */
    static String sampleFailures(List<String> failures) {
        List<String> distinct = new ArrayList<>();
        for (String failure : failures) {
            String text = failure == null || failure.isBlank() ? "unknown" : failure.strip();
            if (!distinct.contains(text)) distinct.add(text);
            if (distinct.size() == MAX_SAMPLE_REASONS) break;
        }
        if (distinct.isEmpty()) return "no reason reported";
        return String.join("; ", distinct);
    }

    private static String describeAge(long millis) {
        if (millis < 0) return "in the future";
        long minutes = millis / 60_000L;
        if (minutes < 1) return "seconds ago";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 48) return hours + "h ago";
        return (hours / 24) + "d ago";
    }
}
